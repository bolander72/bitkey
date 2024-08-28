package build.wallet.recovery.socrec

import build.wallet.account.AccountRepositoryFake
import build.wallet.account.AccountStatus.ActiveAccount
import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.hardware.AppGlobalAuthKeyHwSignature
import build.wallet.bitkey.keybox.FullAccountMock
import build.wallet.bitkey.relationships.*
import build.wallet.bitkey.socrec.TrustedContactKeyCertificateFake2
import build.wallet.crypto.PublicKey
import build.wallet.encrypt.signResult
import build.wallet.f8e.socrec.SocRecF8eClientFake
import build.wallet.testing.AppTester
import build.wallet.testing.AppTester.Companion.launchNewApp
import build.wallet.testing.ext.getHardwareFactorProofOfPossession
import build.wallet.testing.ext.onboardFullAccountWithFakeHardware
import build.wallet.testing.shouldBeOk
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration

class EndorseTrustedContactsServiceImplFunctionalTests : FunSpec({

  coroutineTestScope = true

  lateinit var appTester: AppTester
  lateinit var socRecService: SocRecServiceImpl
  lateinit var endorseTrustedContactsService: EndorseTrustedContactsServiceImpl
  lateinit var socialRecoveryF8eClient: SocRecF8eClientFake
  lateinit var socRecCrypto: SocRecCryptoFake
  lateinit var socRecRelationshipsDao: SocRecRelationshipsDao
  lateinit var socRecEnrollmentAuthenticationDao: SocRecEnrollmentAuthenticationDao
  val accountRepository = AccountRepositoryFake().apply {
    accountState.value = Ok(ActiveAccount(FullAccountMock))
  }

  val alias = TrustedContactAlias("trustedContactId")

  val postSocRecTaskRepository = PostSocRecTaskRepositoryMock()

  beforeTest {
    appTester = launchNewApp(isUsingSocRecFakes = true)

    socialRecoveryF8eClient =
      (appTester.app.appComponent.socRecF8eClientProvider.get() as SocRecF8eClientFake)
    socialRecoveryF8eClient.acceptInvitationDelay = Duration.ZERO
    socRecRelationshipsDao = appTester.app.appComponent.socRecRelationshipsDao
    socRecEnrollmentAuthenticationDao = appTester.app.appComponent.socRecEnrollmentAuthenticationDao
    socRecCrypto = appTester.app.socRecCryptoFake
    postSocRecTaskRepository.reset()
    accountRepository.reset()
    accountRepository.setActiveAccount(FullAccountMock)

    socRecService = SocRecServiceImpl(
      socRecF8eClientProvider = { socialRecoveryF8eClient },
      socRecRelationshipsDao = socRecRelationshipsDao,
      socRecEnrollmentAuthenticationDao = socRecEnrollmentAuthenticationDao,
      socRecCrypto = socRecCrypto,
      socialRecoveryCodeBuilder = appTester.app.appComponent.socialRecoveryCodeBuilder,
      appSessionManager = appTester.app.appComponent.appSessionManager,
      postSocRecTaskRepository = postSocRecTaskRepository,
      accountRepository = accountRepository
    )

    endorseTrustedContactsService = EndorseTrustedContactsServiceImpl(
      socRecService = socRecService,
      socRecRelationshipsDao = socRecRelationshipsDao,
      socRecEnrollmentAuthenticationDao = socRecEnrollmentAuthenticationDao,
      socRecCrypto = socRecCrypto,
      endorseTrustedContactsF8eClientProvider = suspend { socialRecoveryF8eClient },
      accountRepository = accountRepository
    )
  }

  suspend fun simulateAcceptedInvite(
    account: FullAccount,
    overrideConfirmation: String? = null,
    overridePakeCode: String? = null,
  ): Pair<UnendorsedTrustedContact, PublicKey<DelegatedDecryptionKey>> {
    val invite = socRecService
      .createInvitation(
        account = account,
        trustedContactAlias = alias,
        hardwareProofOfPossession = appTester.getHardwareFactorProofOfPossession(account.keybox)
      )
      .getOrThrow()
    // Delete the invitation since we'll be adding it back as an unendorsed trusted contact.
    socialRecoveryF8eClient.deleteInvitation(invite.invitation.relationshipId)

    // Get the PAKE code and enrollment public key that should be shared with the TC
    val pakeData = socRecEnrollmentAuthenticationDao
      .getByRelationshipId(invite.invitation.relationshipId)
      .getOrThrow()
      .shouldNotBeNull()
    val delegatedDecryptionKey = socRecCrypto.generateDelegatedDecryptionKey().getOrThrow()

    // Simulate the TC accepting the invitation and sending their identity key
    val pakeCode = if (overridePakeCode != null) {
      PakeCode(overridePakeCode.toByteArray().toByteString())
    } else {
      pakeData.pakeCode
    }
    val tcResponse = socRecCrypto
      .encryptDelegatedDecryptionKey(
        password = pakeCode,
        protectedCustomerEnrollmentPakeKey = pakeData.protectedCustomerEnrollmentPakeKey.publicKey,
        delegatedDecryptionKey = delegatedDecryptionKey.publicKey
      )
      .getOrThrow()
    val unendorsedTc = UnendorsedTrustedContact(
      relationshipId = invite.invitation.relationshipId,
      trustedContactAlias = alias,
      sealedDelegatedDecryptionKey = tcResponse.sealedDelegatedDecryptionKey,
      enrollmentPakeKey = tcResponse.trustedContactEnrollmentPakeKey,
      enrollmentKeyConfirmation = overrideConfirmation?.encodeUtf8() ?: tcResponse.keyConfirmation,
      authenticationState = TrustedContactAuthenticationState.UNAUTHENTICATED,
      roles = setOf(TrustedContactRole.SocialRecoveryContact)
    )

    // Update unendorsed TC
    socialRecoveryF8eClient.unendorsedTrustedContacts
      .removeAll { it.relationshipId == unendorsedTc.relationshipId }
    socialRecoveryF8eClient.unendorsedTrustedContacts.add(unendorsedTc)

    socRecService.syncAndVerifyRelationships(account).getOrThrow()
    return Pair(unendorsedTc, delegatedDecryptionKey.publicKey)
  }

  test("happy path") {
    // Onboard new account
    val account = appTester.onboardFullAccountWithFakeHardware()

    // Creat TC invite
    val (_, tcIdentityKey) = simulateAcceptedInvite(account)

    // PC to authenticate and verify unendorsed TCs
    endorseTrustedContactsService.authenticateAndEndorse(
      socialRecoveryF8eClient.unendorsedTrustedContacts,
      account
    )

    // Verify the key certificate
    val keyCertificate = socialRecoveryF8eClient.keyCertificates.single()
    socRecCrypto.verifyKeyCertificate(account, keyCertificate)
      .shouldBeOk()
      // Verify the TC's identity key
      .shouldBe(tcIdentityKey)

    // Fetch relationships
    val relationships = socRecRelationshipsDao.socRecRelationships().first().getOrThrow()

    // TC should be completely endorsed
    relationships
      .endorsedTrustedContacts
      .single()
      .run {
        trustedContactAlias.shouldBe(alias)
        authenticationState.shouldBe(TrustedContactAuthenticationState.VERIFIED)
      }

    relationships.unendorsedTrustedContacts.shouldBeEmpty()
    relationships.invitations.shouldBeEmpty()
    relationships.protectedCustomers.shouldBeEmpty()
  }

  test("Authenticate/regenerate/endorse - Empty") {
    // Onboard new account
    val account = appTester.onboardFullAccountWithFakeHardware()

    // Generate new Certs
    val newAppKey = socRecCrypto.generateAppAuthKeypair()
    val newHwKey = appTester.app.appComponent.secp256k1KeyGenerator.generateKeypair()
    val hwSignature = appTester.app.appComponent.messageSigner.signResult(
      newAppKey.publicKey.value.encodeUtf8(),
      newHwKey.privateKey
    ).getOrThrow()

    // Verify test setup
    socialRecoveryF8eClient.endorsedTrustedContacts.shouldBeEmpty()

    val result = endorseTrustedContactsService.authenticateRegenerateAndEndorse(
      accountId = account.accountId,
      f8eEnvironment = account.config.f8eEnvironment,
      contacts = socialRecoveryF8eClient.endorsedTrustedContacts,
      oldAppGlobalAuthKey = account.keybox.activeAppKeyBundle.authKey,
      oldHwAuthKey = account.keybox.activeHwKeyBundle.authKey,
      newAppGlobalAuthKey = newAppKey.publicKey,
      newAppGlobalAuthKeyHwSignature = AppGlobalAuthKeyHwSignature(hwSignature)
    )

    result.shouldBeOk()
  }

  test("Authenticate/regenerate/endorse - Success") {
    // Onboard new account
    val account = appTester.onboardFullAccountWithFakeHardware()

    // Create TC invite
    simulateAcceptedInvite(account)

    // Endorse
    endorseTrustedContactsService.authenticateAndEndorse(
      socialRecoveryF8eClient.unendorsedTrustedContacts,
      account
    )

    // Generate new Certs
    val newAppKey = socRecCrypto.generateAppAuthKeypair()
    val newHwKey = appTester.app.appComponent.secp256k1KeyGenerator.generateKeypair()
    val hwSignature = appTester.app.appComponent.messageSigner.signResult(
      newAppKey.publicKey.value.encodeUtf8(),
      newHwKey.privateKey
    ).getOrThrow()

    // Verify test setup
    socialRecoveryF8eClient.endorsedTrustedContacts.shouldNotBeEmpty()

    val result = endorseTrustedContactsService.authenticateRegenerateAndEndorse(
      accountId = account.accountId,
      f8eEnvironment = account.config.f8eEnvironment,
      contacts = socialRecoveryF8eClient.endorsedTrustedContacts,
      oldAppGlobalAuthKey = account.keybox.activeAppKeyBundle.authKey,
      oldHwAuthKey = account.keybox.activeHwKeyBundle.authKey,
      newAppGlobalAuthKey = newAppKey.publicKey,
      newAppGlobalAuthKeyHwSignature = AppGlobalAuthKeyHwSignature(hwSignature)
    )

    result.shouldBeOk()
  }

  test("Authenticate/regenerate/endorse - Tamper") {
    // Onboard new account
    val account = appTester.onboardFullAccountWithFakeHardware()

    // Create TC invite
    simulateAcceptedInvite(account)

    // Endorse
    endorseTrustedContactsService.authenticateAndEndorse(
      socialRecoveryF8eClient.unendorsedTrustedContacts,
      account
    )

    // Generate New Certs
    val newAppKey = socRecCrypto.generateAppAuthKeypair()
    val newHwKey = appTester.app.appComponent.secp256k1KeyGenerator.generateKeypair()
    val hwSignature = appTester.app.appComponent.messageSigner.signResult(
      newAppKey.publicKey.value.encodeUtf8(),
      newHwKey.privateKey
    ).getOrThrow()

    // Verify test setup
    socialRecoveryF8eClient.endorsedTrustedContacts.shouldNotBeEmpty()

    val result = endorseTrustedContactsService.authenticateRegenerateAndEndorse(
      accountId = account.accountId,
      f8eEnvironment = account.config.f8eEnvironment,
      contacts = listOf(
        socialRecoveryF8eClient.endorsedTrustedContacts.single().copy(
          keyCertificate = TrustedContactKeyCertificateFake2
        )
      ),
      oldAppGlobalAuthKey = account.keybox.activeAppKeyBundle.authKey,
      oldHwAuthKey = account.keybox.activeHwKeyBundle.authKey,
      newAppGlobalAuthKey = newAppKey.publicKey,
      newAppGlobalAuthKeyHwSignature = AppGlobalAuthKeyHwSignature(hwSignature)
    )
    val relationships = socRecRelationshipsDao.socRecRelationships().first().getOrThrow()

    relationships.endorsedTrustedContacts.single().authenticationState.shouldBe(
      TrustedContactAuthenticationState.TAMPERED
    )
    result.shouldBeOk()
  }

  test("missing pake data") {
    // Onboard new account
    val account = appTester.onboardFullAccountWithFakeHardware()

    // Creat TC invite
    simulateAcceptedInvite(account)

    // Clear the PAKE data
    socRecEnrollmentAuthenticationDao.clear().getOrThrow()

    // Attempt to authenticate and verify unendorsed TCs
    endorseTrustedContactsService
      .authenticateAndEndorse(socialRecoveryF8eClient.unendorsedTrustedContacts, account)

    // Fetch relationships
    val relationships = socRecRelationshipsDao.socRecRelationships().first().getOrThrow()

    relationships.endorsedTrustedContacts.shouldBeEmpty()

    // Verify that the unendorsed TC is in a failed state
    relationships
      .unendorsedTrustedContacts
      .single()
      .authenticationState
      .shouldBe(TrustedContactAuthenticationState.PAKE_DATA_UNAVAILABLE)
  }

  test("authentication failed due to invalid key confirmation") {
    val account = appTester.onboardFullAccountWithFakeHardware()

    simulateAcceptedInvite(account, overrideConfirmation = "badConfirmation")

    endorseTrustedContactsService.authenticateAndEndorse(
      socialRecoveryF8eClient.unendorsedTrustedContacts,
      account
    )

    socRecRelationshipsDao.socRecRelationships().first().getOrThrow()
      .unendorsedTrustedContacts
      .single()
      .authenticationState
      .shouldBe(TrustedContactAuthenticationState.FAILED)
  }

  test("authentication failed due to wrong pake password") {
    val account = appTester.onboardFullAccountWithFakeHardware()

    simulateAcceptedInvite(account, overridePakeCode = "F00DBAD")

    endorseTrustedContactsService.authenticateAndEndorse(
      socialRecoveryF8eClient.unendorsedTrustedContacts,
      account
    )

    socRecRelationshipsDao.socRecRelationships().first().getOrThrow()
      .unendorsedTrustedContacts
      .single()
      .authenticationState
      .shouldBe(TrustedContactAuthenticationState.FAILED)
  }

  test("one bad contact does not block a good contact") {
    // Onboard new account
    val account = appTester.onboardFullAccountWithFakeHardware()
    val (tcBad, _) = simulateAcceptedInvite(account, overrideConfirmation = "badConfirmation")
    val (tcGood, tcGoodIdentityKey) = simulateAcceptedInvite(account)

    // PC to authenticate and verify unendorsed TCs
    endorseTrustedContactsService
      .authenticateAndEndorse(socialRecoveryF8eClient.unendorsedTrustedContacts, account)

    // Fetch relationships
    val relationships = socRecRelationshipsDao.socRecRelationships().first().getOrThrow()

    // Verify that the unendorsed TC is in a failed state
    relationships
      .unendorsedTrustedContacts
      .single()
      .run {
        relationshipId.shouldBe(tcBad.relationshipId)
        authenticationState.shouldBe(TrustedContactAuthenticationState.FAILED)
      }

    // Verify that the unendorsed TC is in the endorsed state
    relationships
      .endorsedTrustedContacts
      .single()
      .run {
        identityKey.shouldBe(tcGoodIdentityKey)
        trustedContactAlias.shouldBe(tcGood.trustedContactAlias)
        authenticationState.shouldBe(TrustedContactAuthenticationState.VERIFIED)
      }

    // Verify the key certificate
    socialRecoveryF8eClient.keyCertificates
      .single()
      .run {
        delegatedDecryptionKey.shouldBe(tcGoodIdentityKey)

        socRecCrypto.verifyKeyCertificate(keyCertificate = this, account = account)
      }
  }
})
