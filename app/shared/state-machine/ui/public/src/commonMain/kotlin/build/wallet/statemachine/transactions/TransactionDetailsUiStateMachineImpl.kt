package build.wallet.statemachine.transactions

import androidx.compose.runtime.*
import build.wallet.analytics.events.EventTracker
import build.wallet.analytics.v1.Action.ACTION_APP_ATTEMPT_SPEED_UP_TRANSACTION
import build.wallet.bdk.bindings.BdkError
import build.wallet.bitcoin.BitcoinNetworkType
import build.wallet.bitcoin.explorer.BitcoinExplorer
import build.wallet.bitcoin.explorer.BitcoinExplorerType.Mempool
import build.wallet.bitcoin.fees.BitcoinFeeRateEstimator
import build.wallet.bitcoin.fees.FeeRate
import build.wallet.bitcoin.transactions.*
import build.wallet.bitcoin.transactions.BitcoinTransaction.ConfirmationStatus.Confirmed
import build.wallet.bitcoin.transactions.BitcoinTransaction.ConfirmationStatus.Pending
import build.wallet.bitcoin.transactions.BitcoinTransaction.TransactionType.*
import build.wallet.bitcoin.wallet.SpendingWallet
import build.wallet.bitkey.account.FullAccount
import build.wallet.compose.collections.immutableListOf
import build.wallet.compose.collections.immutableListOfNotNull
import build.wallet.feature.flags.FeeBumpIsAvailableFeatureFlag
import build.wallet.feature.isEnabled
import build.wallet.logging.logFailure
import build.wallet.money.FiatMoney
import build.wallet.money.display.FiatCurrencyPreferenceRepository
import build.wallet.money.exchange.CurrencyConverter
import build.wallet.money.formatter.AmountDisplayText
import build.wallet.money.formatter.MoneyDisplayFormatter
import build.wallet.money.formatter.amountDisplayText
import build.wallet.money.orZero
import build.wallet.platform.web.InAppBrowserNavigator
import build.wallet.statemachine.core.*
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.statemachine.core.form.FormMainContentModel.DataList
import build.wallet.statemachine.core.form.FormMainContentModel.DataList.Data
import build.wallet.statemachine.core.form.FormMainContentModel.DataList.Data.TitleTextType
import build.wallet.statemachine.core.form.RenderContext
import build.wallet.statemachine.data.money.convertedOrNull
import build.wallet.statemachine.data.money.convertedOrZero
import build.wallet.statemachine.send.fee.FeeSelectionEventTrackerScreenId
import build.wallet.statemachine.transactions.TransactionDetailsUiStateMachineImpl.UiState.*
import build.wallet.time.DateTimeFormatter
import build.wallet.time.DurationFormatter
import build.wallet.time.TimeZoneProvider
import build.wallet.ui.model.StandardClick
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.icon.*
import com.github.michaelbull.result.getOrElse
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime

class TransactionDetailsUiStateMachineImpl(
  private val bitcoinExplorer: BitcoinExplorer,
  private val timeZoneProvider: TimeZoneProvider,
  private val dateTimeFormatter: DateTimeFormatter,
  private val currencyConverter: CurrencyConverter,
  private val moneyDisplayFormatter: MoneyDisplayFormatter,
  private val clock: Clock,
  private val durationFormatter: DurationFormatter,
  private val eventTracker: EventTracker,
  private val bitcoinTransactionBumpabilityChecker: BitcoinTransactionBumpabilityChecker,
  private val feeBumpEnabled: FeeBumpIsAvailableFeatureFlag,
  private val fiatCurrencyPreferenceRepository: FiatCurrencyPreferenceRepository,
  private val feeBumpConfirmationUiStateMachine: FeeBumpConfirmationUiStateMachine,
  private val feeRateEstimator: BitcoinFeeRateEstimator,
  private val inAppBrowserNavigator: InAppBrowserNavigator,
  private val transactionsService: TransactionsService,
) : TransactionDetailsUiStateMachine {
  @Composable
  @Suppress("CyclomaticComplexMethod")
  override fun model(props: TransactionDetailsUiProps): ScreenModel {
    val totalAmount =
      when (props.transaction.transactionType) {
        Incoming, UtxoConsolidation -> props.transaction.subtotal
        Outgoing -> props.transaction.total
      }

    val fiatCurrency by fiatCurrencyPreferenceRepository.fiatCurrencyPreference.collectAsState()

    val totalFiatAmount =
      convertedOrZero(
        converter = currencyConverter,
        fromAmount = totalAmount,
        toCurrency = fiatCurrency,
        atTime = props.transaction.broadcastTime ?: props.transaction.confirmationTime()
      ) as FiatMoney

    // When we have a pending transaction, we want to show a ~ ahead of the total fiat amount.
    val usePendingFiatFormat = when {
      props.transaction.confirmationTime() != null -> false
      props.transaction.broadcastTime != null -> false
      else -> true
    }

    val totalAmountTexts = moneyDisplayFormatter.amountDisplayText(
      bitcoinAmount = totalAmount,
      fiatAmount = totalFiatAmount,
      withPendingFormat = usePendingFiatFormat
    )

    val subtotalFiatAmount = convertedOrNull(
      converter = currencyConverter,
      fromAmount = props.transaction.subtotal,
      toCurrency = fiatCurrency,
      atTime = props.transaction.broadcastTime ?: props.transaction.confirmationTime()
    ) as FiatMoney?
    val subtotalAmountTexts = moneyDisplayFormatter.amountDisplayText(
      bitcoinAmount = props.transaction.subtotal,
      fiatAmount = subtotalFiatAmount
    )

    val transactionFee = props.transaction.fee.orZero()
    val consolidationTime = props.transaction.confirmationTime() ?: props.transaction.broadcastTime
    val transactionFeeFiat = convertedOrNull(
      converter = currencyConverter,
      fromAmount = transactionFee,
      toCurrency = fiatCurrency,
      atTime = consolidationTime
    ) as FiatMoney?
    val transactionFeeAmountTexts = moneyDisplayFormatter.amountDisplayText(
      bitcoinAmount = transactionFee,
      fiatAmount = transactionFeeFiat,
      // Utxo Consolidation uses the transaction fee amount as its total row, so we need to appropriately
      // show the fee fiat amount as pending for consolidations.
      withPendingFormat = usePendingFiatFormat && props.transaction.transactionType == UtxoConsolidation
    )

    val transactionsData by remember { transactionsService.transactionsLoadedData() }
      .collectAsState(null)

    val allUtxos = remember(transactionsData) { transactionsData?.utxos?.all.orEmpty() }

    val feeBumpEnabled by remember {
      mutableStateOf(
        feeBumpEnabled.isEnabled() &&
          bitcoinTransactionBumpabilityChecker.isBumpable(
            transaction = props.transaction,
            walletUnspentOutputs = allUtxos.toImmutableList()
          )
      )
    }

    var uiState: UiState by remember { mutableStateOf(ShowingTransactionDetailUiState()) }

    return when (val state = uiState) {
      is SpeedingUpTransactionUiState ->
        feeBumpConfirmationUiStateMachine.model(
          FeeBumpConfirmationProps(
            account = props.account as FullAccount,
            speedUpTransactionDetails = state.speedUpTransactionDetails,
            onExit = { props.onClose() },
            psbt = state.psbt,
            newFeeRate = state.newFeeRate
          )
        )

      is ShowingTransactionDetailUiState ->
        TransactionDetailModel(
          props = props,
          totalAmountTexts = totalAmountTexts,
          subtotalAmountTexts = subtotalAmountTexts,
          transactionFeeAmountTexts = transactionFeeAmountTexts,
          feeBumpEnabled = feeBumpEnabled,
          isLoading = state.isLoading,
          isShowingEducationSheet = state.isShowingEducationSheet,
          onViewSpeedUpEducation = {
            uiState = state.copy(isShowingEducationSheet = true)
          },
          onCloseSpeedUpEducation = {
            uiState = state.copy(isShowingEducationSheet = false)
          },
          onViewTransaction = {
            inAppBrowserNavigator.open(
              bitcoinExplorer.getTransactionUrl(
                txId = props.transaction.id,
                network = props.account.config.bitcoinNetworkType,
                explorerType = Mempool
              ),
              onClose = {}
            )
          },
          onSpeedUpTransaction = {
            eventTracker.track(ACTION_APP_ATTEMPT_SPEED_UP_TRANSACTION)
            when (val details = props.transaction.toSpeedUpTransactionDetails()) {
              null -> FeeLoadingErrorUiState(FeeLoadingError.TransactionMissingRecipientAddress)
              else -> {
                uiState = ShowingTransactionDetailUiState(isLoading = true)
              }
            }
          },
          onInsufficientFunds = {
            uiState = InsufficientFundsUiState
          },
          onFailedToPrepareData = {
            uiState = FeeLoadingErrorUiState(FeeLoadingError.TransactionMissingRecipientAddress)
          },
          onFeeRateTooLow = {
            uiState = FeeRateTooLowUiState
          },
          onSuccessBumpingFee = { psbt, newFeeRate ->
            when (val details = props.transaction.toSpeedUpTransactionDetails()) {
              null -> FeeLoadingErrorUiState(FeeLoadingError.TransactionMissingRecipientAddress)
              else -> {
                uiState = SpeedingUpTransactionUiState(
                  psbt = psbt,
                  newFeeRate = newFeeRate,
                  speedUpTransactionDetails = details
                )
              }
            }
          }
        )

      // TODO [W-5841]: refactor to use common error-handling state machine with FeeSelectionUiStateMachine
      is FeeLoadingErrorUiState ->
        ErrorFormBodyModel(
          title = "We couldn’t speed up this transaction",
          subline = "We are looking into this. Please try again later.",
          primaryButton =
            ButtonDataModel(
              text = "Go Back",
              onClick = { uiState = ShowingTransactionDetailUiState() }
            ),
          eventTrackerScreenId = FeeSelectionEventTrackerScreenId.FEE_ESTIMATION_INSUFFICIENT_FUNDS_ERROR_SCREEN
        ).asModalScreen()

      is InsufficientFundsUiState ->
        ErrorFormBodyModel(
          title = "We couldn’t speed up this transaction",
          subline = "There are not enough funds to speed up the transaction. Please add more funds and try again.",
          primaryButton = ButtonDataModel(
            text = "Go Back",
            onClick = { uiState = ShowingTransactionDetailUiState() }
          ),
          eventTrackerScreenId = null
        ).asModalScreen()
      FeeRateTooLowUiState -> ErrorFormBodyModel(
        title = "We couldn’t speed up this transaction",
        subline = "The current fee rate is too low. Please try again later.",
        primaryButton = ButtonDataModel(
          text = "Go Back",
          onClick = { uiState = ShowingTransactionDetailUiState() }
        ),
        eventTrackerScreenId = null
      ).asModalScreen()
    }
  }

  @Suppress("CyclomaticComplexMethod")
  @Composable
  private fun TransactionDetailModel(
    props: TransactionDetailsUiProps,
    totalAmountTexts: AmountDisplayText,
    subtotalAmountTexts: AmountDisplayText,
    transactionFeeAmountTexts: AmountDisplayText,
    feeBumpEnabled: Boolean,
    isLoading: Boolean,
    isShowingEducationSheet: Boolean,
    onViewSpeedUpEducation: () -> Unit,
    onCloseSpeedUpEducation: () -> Unit,
    onViewTransaction: () -> Unit,
    onSpeedUpTransaction: () -> Unit,
    onInsufficientFunds: () -> Unit,
    onFailedToPrepareData: () -> Unit,
    onFeeRateTooLow: () -> Unit,
    onSuccessBumpingFee: (psbt: Psbt, newFeeRate: FeeRate) -> Unit,
  ): ScreenModel {
    if (isLoading) {
      LaunchedEffect("loading-rates-and-getting-wallet") {
        feeRateEstimator
          .getEstimatedFeeRates(networkType = props.account.config.bitcoinNetworkType)
          .getOrElse {
            onFailedToPrepareData()
            return@LaunchedEffect
          }.getOrElse(EstimatedTransactionPriority.FASTEST) {
            onFailedToPrepareData()
            return@LaunchedEffect
          }.also { feeRate ->
            // For test accounts on Signet, we manually choose a fee rate that is 5 times the previous
            // one. This is particularly useful for QA when testing.
            val newFeeRate = if (props.account.config.isTestAccount &&
              props.account.config.bitcoinNetworkType == BitcoinNetworkType.SIGNET
            ) {
              FeeRate(satsPerVByte = feeRate.satsPerVByte * 5)
            } else {
              feeRate
            }
            val constructionMethod = SpendingWallet.PsbtConstructionMethod.BumpFee(
              txid = props.transaction.id,
              feeRate = newFeeRate
            )

            val wallet = transactionsService.spendingWallet().value
            if (wallet == null) {
              onFailedToPrepareData()
              return@LaunchedEffect
            }
            val psbt = wallet
              .createSignedPsbt(constructionType = constructionMethod)
              .logFailure { "Unable to build fee bump psbt" }
              .getOrElse {
                when (it) {
                  is BdkError.InsufficientFunds -> onInsufficientFunds()
                  is BdkError.FeeRateTooLow -> onFeeRateTooLow()
                  else -> onFailedToPrepareData()
                }
                return@LaunchedEffect
              }

            onSuccessBumpingFee(psbt, newFeeRate)
          }
      }
    }

    val transactionDetailModel = TransactionDetailModel(
      feeBumpEnabled = feeBumpEnabled,
      txStatusModel = when (props.transaction.confirmationStatus) {
        is Pending -> TxStatusModel.Pending(
          transactionType = props.transaction.transactionType,
          recipientAddress = props.transaction.chunkedRecipientAddress(),
          // Some transactions may not have an estimate confirmation time, if they don't, we don't
          // attempt to show "Transaction delayed", just "Transaction pending".
          isLate = props.transaction.estimatedConfirmationTime?.let { estimatedTime ->
            clock.now() > estimatedTime
          } ?: false
        )
        is Confirmed -> TxStatusModel.Confirmed(
          transactionType = props.transaction.transactionType,
          recipientAddress = props.transaction.chunkedRecipientAddress()
        )
      },
      isLoading = false,
      onViewTransaction = onViewTransaction,
      onClose = props.onClose,
      onSpeedUpTransaction = onSpeedUpTransaction,
      content = transactionsDataListContent(
        props = props,
        totalAmountTexts = totalAmountTexts,
        subtotalAmountTexts = subtotalAmountTexts,
        transactionFeeAmountTexts = transactionFeeAmountTexts,
        onViewSpeedUpEducation = onViewSpeedUpEducation
      )
    ).asModalScreen()

    val transactionSpeedUpEducationModel = TransactionSpeedUpEducationModel(
      onSpeedUpTransaction = onSpeedUpTransaction,
      onClose = onCloseSpeedUpEducation
    ).takeIf { isShowingEducationSheet }

    return ScreenModel(
      body = transactionDetailModel.body,
      bottomSheetModel = transactionSpeedUpEducationModel
    )
  }

  private fun transactionsDataListContent(
    props: TransactionDetailsUiProps,
    totalAmountTexts: AmountDisplayText,
    subtotalAmountTexts: AmountDisplayText,
    transactionFeeAmountTexts: AmountDisplayText,
    onViewSpeedUpEducation: () -> Unit,
  ): ImmutableList<DataList> {
    val confirmationData = when (val status = props.transaction.confirmationStatus) {
      is Confirmed ->
        Data(
          title = "Confirmed at",
          sideText =
            dateTimeFormatter.shortDateWithTime(
              localDateTime =
                status.blockTime.timestamp.toLocalDateTime(
                  timeZoneProvider.current()
                )
            )
        )

      is Pending -> pendingDataListItem(
        estimatedConfirmationTime = props.transaction.estimatedConfirmationTime,
        onViewSpeedUpEducation = onViewSpeedUpEducation
      )
    }

    val transactionDetails = DataList(
      items = when (props.transaction.transactionType) {
        Incoming -> immutableListOf() // We only display the total row for receiving
        UtxoConsolidation -> {
          immutableListOf(
            Data(
              title = "UTXOs consolidated",
              sideText = "${props.transaction.inputs.size} → 1"
            ),
            Data(
              title = "Consolidation cost",
              titleTextType = TitleTextType.BOLD,
              secondaryTitle = when {
                props.transaction.confirmationTime() != null -> "At time confirmed"
                props.transaction.broadcastTime != null -> "At time sent"
                else -> null
              },
              sideText = transactionFeeAmountTexts.primaryAmountText,
              secondarySideText = transactionFeeAmountTexts.secondaryAmountText
            )
          )
        }

        Outgoing ->
          immutableListOf(
            Data(
              title =
                when (props.transaction.confirmationStatus) {
                  is Pending -> "Recipient receives"
                  is Confirmed -> "Recipient received"
                },
              sideText = subtotalAmountTexts.primaryAmountText,
              secondarySideText = subtotalAmountTexts.secondaryAmountText
            ),
            Data(
              title = "Network fees",
              sideText = transactionFeeAmountTexts.primaryAmountText,
              secondarySideText = transactionFeeAmountTexts.secondaryAmountText
            )
          )
      },
      total = run {
        val shouldDisplayTotal = props.transaction.transactionType != UtxoConsolidation
        if (shouldDisplayTotal) {
          val title = if (props.transaction.transactionType == Incoming) {
            when (props.transaction.confirmationStatus) {
              is Confirmed -> "Amount received"
              Pending -> "Amount receiving"
            }
          } else {
            "Total"
          }
          Data(
            title = title,
            secondaryTitle = when {
              props.transaction.confirmationTime() != null -> "At time confirmed"
              props.transaction.broadcastTime != null -> "At time sent"
              else -> null
            },
            sideText = totalAmountTexts.primaryAmountText,
            sideTextType = Data.SideTextType.BODY2BOLD,
            secondarySideText = totalAmountTexts.secondaryAmountText
          )
        } else {
          null
        }
      }
    )

    return immutableListOfNotNull(
      confirmationData?.let { DataList(items = immutableListOf(it)) },
      transactionDetails
    )
  }

  @Composable
  private fun TransactionSpeedUpEducationModel(
    onSpeedUpTransaction: () -> Unit,
    onClose: () -> Unit,
  ): SheetModel =
    SheetModel(
      size = SheetSize.MIN40,
      onClosed = onClose,
      body = TransactionSpeedUpEducationBodyModel(
        onSpeedUpTransaction = onSpeedUpTransaction,
        onClose = onClose
      )
    )

  private data class TransactionSpeedUpEducationBodyModel(
    val onSpeedUpTransaction: () -> Unit,
    val onClose: () -> Unit,
  ) : FormBodyModel(
      id = null,
      onBack = onClose,
      toolbar = null,
      header = FormHeaderModel(
        headline = "Speed up transactions",
        subline = """
            If your Bitcoin transaction is taking longer than expected, you can try speeding it up.
            
            A common problem that can occur is when someone sends a payment with a fee that isn't high enough to get confirmed, causing it to get stuck in the mempool.
            
            We’ll take the guess work out by providing a fee that should get your transfer confirmed quickly.
        """.trimIndent(),
        iconModel = IconModel(
          icon = Icon.SmallIconSpeed,
          iconSize = IconSize.Small,
          iconTint = IconTint.Primary,
          iconBackgroundType = IconBackgroundType.Circle(
            circleSize = IconSize.Large,
            color = IconBackgroundType.Circle.CircleColor.PrimaryBackground20
          )
        ),
        sublineTreatment = FormHeaderModel.SublineTreatment.REGULAR
      ),
      primaryButton = ButtonModel(
        text = "Try speeding up",
        treatment = ButtonModel.Treatment.Primary,
        size = ButtonModel.Size.Footer,
        onClick = StandardClick(onSpeedUpTransaction)
      ),
      renderContext = RenderContext.Sheet
    )

  private fun pendingDataListItem(
    estimatedConfirmationTime: Instant?,
    onViewSpeedUpEducation: () -> Unit,
  ): Data? {
    if (!feeBumpEnabled.flagValue().value.value || estimatedConfirmationTime == null) {
      return null
    }

    return estimatedConfirmationTime.let { confirmationTime ->
      val currentTime = clock.now()
      if (confirmationTime < currentTime) {
        Data(
          title = "Should have arrived by",
          sideText =
            dateTimeFormatter.shortDateWithTime(
              localDateTime = confirmationTime.toLocalDateTime(timeZoneProvider.current())
            ),
          sideTextTreatment = Data.SideTextTreatment.STRIKETHROUGH,
          sideTextType = Data.SideTextType.REGULAR,
          secondarySideText = "${
            durationFormatter.formatWithAlphabet(
              currentTime - confirmationTime
            )
          } late",
          secondarySideTextType = Data.SideTextType.BOLD,
          secondarySideTextTreatment = Data.SideTextTreatment.WARNING,
          explainer =
            Data.Explainer(
              title = "Taking longer than usual",
              subtitle = "You can either wait for this transaction to be confirmed or speed it up – you'll need to pay a higher network fee.",
              iconButton = IconButtonModel(
                iconModel = IconModel(
                  icon = Icon.SmallIconInformationFilled,
                  iconSize = IconSize.XSmall,
                  iconBackgroundType = IconBackgroundType.Circle(
                    circleSize = IconSize.XSmall
                  ),
                  iconTint = IconTint.Foreground,
                  iconOpacity = 0.20f
                ),
                onClick = StandardClick(onViewSpeedUpEducation)
              )
            )
        )
      } else {
        Data(
          title = "Should arrive by",
          sideText =
            dateTimeFormatter.shortDateWithTime(
              localDateTime = confirmationTime.toLocalDateTime(timeZoneProvider.current())
            )
        )
      }
    }
  }

  private sealed interface UiState {
    /**
     * Customer is viewing transaction details.
     */
    data class ShowingTransactionDetailUiState(
      val isLoading: Boolean = false,
      val isShowingEducationSheet: Boolean = false,
    ) : UiState

    /**
     * Customer is showing speed up confirmation flow.
     */
    data class SpeedingUpTransactionUiState(
      val psbt: Psbt,
      val newFeeRate: FeeRate,
      val speedUpTransactionDetails: SpeedUpTransactionDetails,
    ) : UiState

    /**
     * User currently does not have enough funds to fee bump the transaction.
     */
    data object InsufficientFundsUiState : UiState

    /**
     * Fee rates are currently too low to fee bump the transaction.
     */
    data object FeeRateTooLowUiState : UiState

    /**
     * We failed to construct a fee estimation required to fee bump a transaction.
     */
    data class FeeLoadingErrorUiState(
      val feeLoadingError: FeeLoadingError,
    ) : UiState
  }

  /**
   * Describes different ways loading fees can fail when speeding up a transaction.
   */
  sealed class FeeLoadingError : kotlin.Error() {
    /**
     * The transaction that was loaded to props was missing a recipient address.
     *
     * We do not expect this to happen, but handle it just in case.
     */
    data object TransactionMissingRecipientAddress : FeeLoadingError()
  }
}
