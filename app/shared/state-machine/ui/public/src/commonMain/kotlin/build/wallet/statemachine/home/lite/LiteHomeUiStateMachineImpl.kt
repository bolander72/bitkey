package build.wallet.statemachine.home.lite

import androidx.compose.runtime.*
import build.wallet.analytics.events.EventTracker
import build.wallet.analytics.v1.Action
import build.wallet.f8e.socrec.SocRecRelationships
import build.wallet.recovery.socrec.SocRecRelationshipsRepository
import build.wallet.router.Route
import build.wallet.router.Router
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.home.lite.HomeScreen.MoneyHome
import build.wallet.statemachine.home.lite.HomeScreen.Settings
import build.wallet.statemachine.home.lite.PresentedScreen.AddTrustedContact
import build.wallet.statemachine.moneyhome.lite.LiteMoneyHomeUiProps
import build.wallet.statemachine.moneyhome.lite.LiteMoneyHomeUiStateMachine
import build.wallet.statemachine.recovery.socrec.LiteTrustedContactManagementProps
import build.wallet.statemachine.recovery.socrec.LiteTrustedContactManagementProps.AcceptInvite
import build.wallet.statemachine.recovery.socrec.LiteTrustedContactManagementUiStateMachine
import build.wallet.statemachine.settings.lite.LiteSettingsHomeUiProps
import build.wallet.statemachine.settings.lite.LiteSettingsHomeUiStateMachine
import build.wallet.statemachine.status.HomeStatusBannerUiProps
import build.wallet.statemachine.status.HomeStatusBannerUiStateMachine
import kotlinx.coroutines.flow.filterNotNull

class LiteHomeUiStateMachineImpl(
  private val homeStatusBannerUiStateMachine: HomeStatusBannerUiStateMachine,
  private val liteMoneyHomeUiStateMachine: LiteMoneyHomeUiStateMachine,
  private val liteSettingsHomeUiStateMachine: LiteSettingsHomeUiStateMachine,
  private val liteTrustedContactManagementUiStateMachine:
    LiteTrustedContactManagementUiStateMachine,
  private val socRecRelationshipsRepository: SocRecRelationshipsRepository,
  private val eventTracker: EventTracker,
) : LiteHomeUiStateMachine {
  @Composable
  override fun model(props: LiteHomeUiProps): ScreenModel {
    var uiState: LiteHomeUiState by remember {
      mutableStateOf(LiteHomeUiState(rootScreen = MoneyHome, presentedScreen = null))
    }

    // Set up a launched effect to sync relationships for social recovery
    // (i.e. sync protected customers).
    val socRecRelationships = syncRelationships(props)
    val socRecLiteAccountActions =
      socRecRelationshipsRepository.toActions(
        props.accountData.account
      )

    LaunchedEffect("deep-link-routing") {
      Router.onRouteChange { route ->
        when (route) {
          is Route.TrustedContactInvite -> {
            eventTracker.track(Action.ACTION_APP_SOCREC_ENTERED_INVITE_VIA_DEEPLINK)
            uiState =
              uiState.copy(
                presentedScreen =
                  AddTrustedContact(
                    acceptInvite =
                      AcceptInvite(
                        inviteCode = route.inviteCode
                      )
                  )
              )
            return@onRouteChange true
          }
          else -> false
        }
      }
    }

    // Observe the global status banner model
    val homeStatusBannerModel =
      homeStatusBannerUiStateMachine.model(
        props =
          HomeStatusBannerUiProps(
            f8eEnvironment = props.accountData.account.config.f8eEnvironment,
            onBannerClick = null
          )
      )

    return when (val presentedScreen = uiState.presentedScreen) {
      null -> {
        when (uiState.rootScreen) {
          MoneyHome ->
            liteMoneyHomeUiStateMachine.model(
              props =
                LiteMoneyHomeUiProps(
                  accountData = props.accountData,
                  protectedCustomers = socRecRelationships.protectedCustomers,
                  homeStatusBannerModel = homeStatusBannerModel,
                  onRemoveRelationship = socRecLiteAccountActions::removeProtectedCustomer,
                  onSettings = { uiState = uiState.copy(rootScreen = Settings) },
                  onAcceptInvite = {
                    uiState =
                      uiState.copy(
                        presentedScreen =
                          AddTrustedContact(
                            acceptInvite = AcceptInvite(inviteCode = null)
                          )
                      )
                  }
                )
            )

          Settings ->
            liteSettingsHomeUiStateMachine.model(
              props = LiteSettingsHomeUiProps(
                accountData = props.accountData,
                protectedCustomers = socRecRelationships.protectedCustomers,
                homeStatusBannerModel = homeStatusBannerModel,
                socRecTrustedContactActions = socRecLiteAccountActions,
                onBack = { uiState = uiState.copy(rootScreen = MoneyHome) }
              )
            )
        }
      }

      is AddTrustedContact ->
        liteTrustedContactManagementUiStateMachine.model(
          props =
            LiteTrustedContactManagementProps(
              accountData = props.accountData,
              protectedCustomers = socRecRelationships.protectedCustomers,
              acceptInvite = presentedScreen.acceptInvite,
              actions = socRecLiteAccountActions,
              onExit = {
                uiState = uiState.copy(presentedScreen = null)
              }
            )
        )
    }
  }

  @Composable
  private fun syncRelationships(props: LiteHomeUiProps): SocRecRelationships {
    LaunchedEffect(props.accountData.account) {
      socRecRelationshipsRepository.syncLoop(scope = this, props.accountData.account)
    }
    return remember {
      socRecRelationshipsRepository.relationships
        .filterNotNull()
    }.collectAsState(SocRecRelationships.EMPTY).value
  }
}

private data class LiteHomeUiState(
  val rootScreen: HomeScreen,
  val presentedScreen: PresentedScreen?,
)

/**
 * Represents a specific "home" screen. These are special screens managed by this state
 * machine that share the ability to present certain content, [PresentedScreen]
 */
private enum class HomeScreen {
  /**
   * Indicates that money home is shown.
   */
  MoneyHome,

  /**
   * Indicates that settings are shown.
   */
  Settings,
}

/**
 * Represents a screen presented on top of either [HomeScreen]
 */
private sealed interface PresentedScreen {
  /** Indicates that the add trusted contact flow is currently presented */
  data class AddTrustedContact(
    val acceptInvite: AcceptInvite?,
  ) : PresentedScreen
}
