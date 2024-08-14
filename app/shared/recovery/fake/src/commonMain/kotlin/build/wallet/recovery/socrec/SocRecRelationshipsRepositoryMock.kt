package build.wallet.recovery.socrec

import app.cash.turbine.Turbine
import build.wallet.auth.AuthTokenScope
import build.wallet.bitkey.account.Account
import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.app.AppGlobalAuthKey
import build.wallet.bitkey.f8e.AccountId
import build.wallet.bitkey.hardware.HwAuthPublicKey
import build.wallet.bitkey.relationships.DelegatedDecryptionKey
import build.wallet.bitkey.relationships.IncomingInvitation
import build.wallet.bitkey.relationships.OutgoingInvitation
import build.wallet.bitkey.relationships.ProtectedCustomer
import build.wallet.bitkey.relationships.ProtectedCustomerAlias
import build.wallet.bitkey.relationships.TrustedContactAlias
import build.wallet.bitkey.socrec.ProtectedCustomerFake
import build.wallet.crypto.PublicKey
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.f8e.socrec.SocRecRelationships
import build.wallet.f8e.socrec.SocRecRelationshipsFake
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

class SocRecRelationshipsRepositoryMock(
  turbine: (String) -> Turbine<Any>,
) : SocRecRelationshipsRepository {
  val launchSyncCalls = turbine("SocRecRelationshipsRepository launchSync calls")
  val syncCalls = turbine("SocRecRelationshipsRepository syncRelationships calls")

  override fun syncLoop(
    scope: CoroutineScope,
    account: Account,
  ) {
    launchSyncCalls.add(Unit)
  }

  private val defaultSyncAndVerifyRelationshipsResult: Result<SocRecRelationships, Error> = Ok(
    SocRecRelationshipsFake
  )
  var syncAndVerifyRelationshipsResult: Result<SocRecRelationships, Error> =
    defaultSyncAndVerifyRelationshipsResult

  override suspend fun syncAndVerifyRelationships(
    accountId: AccountId,
    f8eEnvironment: F8eEnvironment,
    appAuthKey: PublicKey<AppGlobalAuthKey>?,
    hwAuthPublicKey: HwAuthPublicKey?,
  ): Result<SocRecRelationships, Error> {
    syncCalls.add(Unit)

    return syncAndVerifyRelationshipsResult
  }

  var relationshipsFlow =
    MutableStateFlow(
      SocRecRelationshipsFake
    )

  override val relationships = relationshipsFlow

  override suspend fun getRelationshipsWithoutSyncing(
    accountId: AccountId,
    f8eEnvironment: F8eEnvironment,
  ): Result<SocRecRelationships, Error> {
    return Ok(relationshipsFlow.value)
  }

  val removeRelationshipWithoutSyncingCalls = turbine("removeRelationshipWithoutSyncing calls")

  override suspend fun removeRelationshipWithoutSyncing(
    accountId: AccountId,
    f8eEnvironment: F8eEnvironment,
    hardwareProofOfPossession: HwFactorProofOfPossession?,
    authTokenScope: AuthTokenScope,
    relationshipId: String,
  ): Result<Unit, Error> {
    removeRelationshipWithoutSyncingCalls.add(relationshipId)
    return Ok(Unit)
  }

  val removeRelationshipCalls = turbine("removeRelationship calls")

  override suspend fun removeRelationship(
    account: Account,
    hardwareProofOfPossession: HwFactorProofOfPossession?,
    authTokenScope: AuthTokenScope,
    relationshipId: String,
  ): Result<Unit, Error> {
    removeRelationshipCalls.add(relationshipId)
    return Ok(Unit)
  }

  val createInvitationCalls = turbine("createInvitation calls")

  override suspend fun createInvitation(
    account: FullAccount,
    trustedContactAlias: TrustedContactAlias,
    hardwareProofOfPossession: HwFactorProofOfPossession,
  ): Result<OutgoingInvitation, Error> {
    createInvitationCalls.add(Unit)
    return Ok(OutgoingInvitationFake)
  }

  override suspend fun refreshInvitation(
    account: FullAccount,
    relationshipId: String,
    hardwareProofOfPossession: HwFactorProofOfPossession,
  ): Result<OutgoingInvitation, Error> {
    return Ok(OutgoingInvitationFake)
  }

  private val defaultRetrieveInvitationResult:
    Result<IncomingInvitation, RetrieveInvitationCodeError> =
    Ok(IncomingInvitationFake)

  var retrieveInvitationResult = defaultRetrieveInvitationResult

  override suspend fun retrieveInvitation(
    account: Account,
    invitationCode: String,
  ): Result<IncomingInvitation, RetrieveInvitationCodeError> {
    return retrieveInvitationResult
  }

  private val defaultAcceptInvitationResult:
    Result<ProtectedCustomer, AcceptInvitationCodeError> =
    Ok(ProtectedCustomerFake)
  var acceptInvitationResult = defaultAcceptInvitationResult

  override suspend fun acceptInvitation(
    account: Account,
    invitation: IncomingInvitation,
    protectedCustomerAlias: ProtectedCustomerAlias,
    delegatedDecryptionKey: PublicKey<DelegatedDecryptionKey>,
    inviteCode: String,
  ): Result<ProtectedCustomer, AcceptInvitationCodeError> {
    return acceptInvitationResult
  }

  override suspend fun clear(): Result<Unit, Error> {
    acceptInvitationResult = defaultAcceptInvitationResult
    retrieveInvitationResult = defaultRetrieveInvitationResult
    syncAndVerifyRelationshipsResult = defaultSyncAndVerifyRelationshipsResult
    return Ok(Unit)
  }
}
