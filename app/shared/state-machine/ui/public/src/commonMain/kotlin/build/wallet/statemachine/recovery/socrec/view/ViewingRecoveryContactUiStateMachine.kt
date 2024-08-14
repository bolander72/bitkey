package build.wallet.statemachine.recovery.socrec.view

import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.relationships.TrustedContact
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.statemachine.core.BodyModel
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.StateMachine
import com.github.michaelbull.result.Result

/**
 * State machine for viewing the details and actions on an established Trusted Contact.
 */
interface ViewingRecoveryContactUiStateMachine : StateMachine<ViewingRecoveryContactProps, ScreenModel>

data class ViewingRecoveryContactProps(
  val screenBody: BodyModel,
  val recoveryContact: TrustedContact,
  val account: FullAccount,
  val onRemoveContact: suspend (
    TrustedContact,
    HwFactorProofOfPossession?,
  ) -> Result<Unit, Error>,
  val afterContactRemoved: (TrustedContact) -> Unit,
  val onExit: () -> Unit,
)
