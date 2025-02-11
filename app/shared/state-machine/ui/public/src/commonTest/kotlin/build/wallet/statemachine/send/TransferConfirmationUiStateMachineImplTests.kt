package build.wallet.statemachine.send

import app.cash.turbine.Turbine
import app.cash.turbine.test
import build.wallet.bdk.bindings.BdkError
import build.wallet.bitcoin.transactions.EstimatedTransactionPriority.FASTEST
import build.wallet.bitcoin.transactions.Psbt
import build.wallet.bitcoin.transactions.TransactionPriorityPreferenceFake
import build.wallet.bitcoin.transactions.TransactionsServiceFake
import build.wallet.bitcoin.wallet.SpendingWalletMock
import build.wallet.bitkey.keybox.FullAccountMock
import build.wallet.ktor.result.HttpError.NetworkError
import build.wallet.limit.DailySpendingLimitStatus
import build.wallet.limit.MobilePayEnabledDataMock
import build.wallet.limit.MobilePayServiceMock
import build.wallet.statemachine.core.LoadingSuccessBodyModel
import build.wallet.statemachine.core.awaitScreenWithBody
import build.wallet.statemachine.core.awaitScreenWithBodyModelMock
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.test
import build.wallet.statemachine.nfc.NfcSessionUIStateMachineProps
import build.wallet.statemachine.ui.clickPrimaryButton
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

fun FunSpec.transferConfirmationUiStateMachineTests(
  props: TransferConfirmationUiProps,
  onTransferInitiatedCalls: Turbine<Psbt>,
  onBackCalls: Turbine<Unit>,
  onExitCalls: Turbine<Unit>,
  spendingWallet: SpendingWalletMock,
  transactionsService: TransactionsServiceFake,
  transactionPriorityPreference: TransactionPriorityPreferenceFake,
  mobilePayService: MobilePayServiceMock,
  appSignedPsbt: Psbt,
  appAndHwSignedPsbt: Psbt,
  stateMachine: TransferConfirmationUiStateMachineImpl,
  nfcSessionUIStateMachineId: String,
) {
  test("create unsigned psbt error - insufficent funds") {
    spendingWallet.createSignedPsbtResult =
      Err(BdkError.InsufficientFunds(Exception(""), null))

    stateMachine.test(props) {
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      // Error screen
      awaitScreenWithBody<FormBodyModel> {
        with(header.shouldNotBeNull()) {
          headline.shouldBe("We couldn’t send this transaction")
          sublineModel.shouldNotBeNull().string.shouldBe(
            "The amount you are trying to send is too high. Please decrease the amount and try again."
          )
        }
        with(primaryButton.shouldNotBeNull()) {
          text.shouldBe("Go Back")
          onClick()
        }
      }
      onBackCalls.awaitItem()
    }
  }

  test("create unsigned psbt error - other error") {
    spendingWallet.createSignedPsbtResult = Err(BdkError.Generic(Exception(""), null))

    stateMachine.test(props) {
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }
      awaitScreenWithBody<FormBodyModel> {
        expectGenericErrorMessage()
        clickPrimaryButton()
      }
      onExitCalls.awaitItem()
    }
  }

  test("[app & hw] failure to sign with app key presents error message") {
    spendingWallet.createSignedPsbtResult = Err(BdkError.Generic(Exception(""), null))
    transactionPriorityPreference.preference.shouldBeNull()

    stateMachine.test(props) {
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }
      awaitScreenWithBody<FormBodyModel> {
        expectGenericErrorMessage()
        clickPrimaryButton()
      }
      onExitCalls.awaitItem()
    }

    transactionPriorityPreference.preference.shouldBeNull()
  }

  test("[app & hw] successfully signing, but failing to broadcast presents error") {
    val transactionPriority = FASTEST
    spendingWallet.createSignedPsbtResult = Ok(appSignedPsbt)
    transactionsService.broadcastError = BdkError.Generic(Exception(""), null)

    stateMachine.test(
      props.copy(
        selectedPriority = transactionPriority
      )
    ) {
      // CreatingAppSignedPsbt
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      mobilePayService.getDailySpendingLimitStatusCalls.awaitItem().shouldBe(props.sendAmount)

      // ViewingTransferConfirmation
      awaitScreenWithBody<FormBodyModel> {
        clickPrimaryButton()
      }

      // SigningWithHardware
      awaitScreenWithBodyModelMock<NfcSessionUIStateMachineProps<Psbt>>(
        id = nfcSessionUIStateMachineId
      ) {
        onSuccess(appAndHwSignedPsbt)
      }

      // FinalizingAndBroadcastingTransaction
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }
      transactionsService.broadcastedPsbts.test {
        awaitItem().shouldContainExactly(appAndHwSignedPsbt)
      }

      // ReceivedBdkError
      awaitScreenWithBody<FormBodyModel> {
        expectGenericErrorMessage()
        clickPrimaryButton()
      }
      onExitCalls.awaitItem()
    }

    transactionPriorityPreference.preference.shouldBeNull()
  }

  test("[app & server] successful signing syncs, broadcasts, calls onTransferInitiated") {
    val preferenceToSet = FASTEST
    spendingWallet.createSignedPsbtResult = Ok(appSignedPsbt)

    transactionPriorityPreference.preference.shouldBeNull()
    mobilePayService.mobilePayData.value = MobilePayEnabledDataMock
    mobilePayService.status = DailySpendingLimitStatus.MobilePayAvailable

    stateMachine.test(
      props.copy(
        selectedPriority = preferenceToSet
      )
    ) {
      // CreatingAppSignedPsbt
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      mobilePayService.getDailySpendingLimitStatusCalls.awaitItem().shouldBe(props.sendAmount)

      // ViewingTransferConfirmation
      awaitScreenWithBody<FormBodyModel> {
        clickPrimaryButton()
      }

      // SigningWithServer
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      transactionsService.broadcastedPsbts.test {
        awaitItem().shouldContainExactly(mobilePayService.signPsbtCalls.awaitItem())
      }
    }

    transactionPriorityPreference.preference.shouldBe(preferenceToSet)
    onTransferInitiatedCalls.awaitItem()
  }

  test("[app & server] failure to sign with app key presents error") {
    val preferenceToSet = FASTEST
    spendingWallet.createSignedPsbtResult = Err(BdkError.Generic(Exception(""), null))
    mobilePayService.status = DailySpendingLimitStatus.MobilePayAvailable

    stateMachine.test(
      props.copy(
        selectedPriority = preferenceToSet
      )
    ) {
      // CreatingAppSignedPsbt
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      // ReceivedBdkError
      awaitScreenWithBody<FormBodyModel> {
        expectGenericErrorMessage()
        clickPrimaryButton()
      }

      onExitCalls.awaitItem()
    }

    transactionPriorityPreference.preference.shouldBeNull()
  }

  test("[app & server] successfully signing, but failing to broadcast succeeds") {
    val preferenceToSet = FASTEST
    spendingWallet.createSignedPsbtResult = Ok(appSignedPsbt)
    transactionsService.broadcastError = BdkError.Generic(Exception(""), null)
    mobilePayService.keysetId = FullAccountMock.keybox.activeSpendingKeyset.f8eSpendingKeyset.keysetId
    mobilePayService.mobilePayData.value = MobilePayEnabledDataMock
    mobilePayService.status = DailySpendingLimitStatus.MobilePayAvailable

    stateMachine.test(
      props.copy(
        selectedPriority = preferenceToSet
      )
    ) {
      // CreatingAppSignedPsbt
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      mobilePayService.getDailySpendingLimitStatusCalls.awaitItem().shouldBe(props.sendAmount)

      // ViewingTransferConfirmation
      awaitScreenWithBody<FormBodyModel> {
        clickPrimaryButton()
      }

      // SigningWithServer
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }
      transactionsService.broadcastedPsbts.test {
        awaitItem().shouldContainExactly(mobilePayService.signPsbtCalls.awaitItem())
      }
    }

    spendingWallet.syncCalls.awaitItem()
    transactionPriorityPreference.preference.shouldBe(preferenceToSet)
    onTransferInitiatedCalls.awaitItem()
  }

  test("[app & server] failure to sign with server key presents error") {
    val preferenceToSet = FASTEST
    spendingWallet.createSignedPsbtResult = Ok(appSignedPsbt)
    mobilePayService.signPsbtWithMobilePayResult = Err(NetworkError(Error("oops")))
    mobilePayService.mobilePayData.value = MobilePayEnabledDataMock
    mobilePayService.status = DailySpendingLimitStatus.MobilePayAvailable

    stateMachine.test(
      props.copy(
        selectedPriority = preferenceToSet
      )
    ) {
      // CreatingAppSignedPsbt
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      mobilePayService.getDailySpendingLimitStatusCalls.awaitItem().shouldBe(props.sendAmount)

      // ViewingTransferConfirmation
      awaitScreenWithBody<FormBodyModel> {
        clickPrimaryButton()
      }

      // SigningWithServer
      awaitScreenWithBody<LoadingSuccessBodyModel> {
        state.shouldBe(LoadingSuccessBodyModel.State.Loading)
      }

      // ReceivedServerSigningError
      awaitScreenWithBody<FormBodyModel> {
        with(header.shouldNotBeNull()) {
          headline.shouldBe("We couldn’t send this as a mobile-only transaction")
          sublineModel.shouldNotBeNull().string.shouldBe(
            "Please use your hardware device to confirm this transaction."
          )
        }
        with(primaryButton.shouldNotBeNull()) {
          text.shouldBe("Continue")
          onClick()
        }
      }

      // SigningWithHardware
      awaitScreenWithBodyModelMock<NfcSessionUIStateMachineProps<Psbt>>(
        id = nfcSessionUIStateMachineId
      )
    }

    mobilePayService.signPsbtCalls.awaitItem()

    transactionPriorityPreference.preference.shouldBeNull()
  }
}

fun FormBodyModel.expectGenericErrorMessage() {
  with(header.shouldNotBeNull()) {
    headline.shouldBe("We couldn’t send this transaction")
    sublineModel.shouldNotBeNull().string.shouldBe(
      "We are looking into this. Please try again later."
    )
  }
  with(primaryButton.shouldNotBeNull()) {
    text.shouldBe("Done")
  }
}
