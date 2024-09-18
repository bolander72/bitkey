@file:OptIn(ExperimentalCoroutinesApi::class)

package build.wallet.recovery.socrec

import app.cash.turbine.test
import build.wallet.account.AccountServiceFake
import build.wallet.analytics.events.AppSessionManagerFake
import build.wallet.bitcoin.AppPrivateKeyDaoFake
import build.wallet.bitkey.keybox.FullAccountMock
import build.wallet.bitkey.relationships.EndorsedTrustedContact
import build.wallet.bitkey.relationships.TrustedContactAlias
import build.wallet.bitkey.relationships.TrustedContactAuthenticationState.*
import build.wallet.bitkey.relationships.TrustedContactRole
import build.wallet.bitkey.socrec.TrustedContactKeyCertificateFake
import build.wallet.bitkey.socrec.TrustedContactKeyCertificateFake2
import build.wallet.compose.collections.immutableListOf
import build.wallet.database.BitkeyDatabaseProviderImpl
import build.wallet.f8e.socrec.*
import build.wallet.sqldelight.InMemorySqlDriverFactory
import io.kotest.core.coroutines.backgroundScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestScope
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SocRecServiceImplTests : FunSpec({

  coroutineTestScope = true

  val databaseProvider = BitkeyDatabaseProviderImpl(
    InMemorySqlDriverFactory()
  )
  val dao = SocRecRelationshipsDaoImpl(databaseProvider)
  val appKeyDao = AppPrivateKeyDaoFake()
  val authDao = SocRecEnrollmentAuthenticationDaoImpl(appKeyDao, databaseProvider)

  lateinit var socRecFake: SocRecF8eClientFake

  fun TestScope.socRecFake() =
    SocRecF8eClientFake(
      uuidGenerator = { "fake-uuid" },
      backgroundScope = backgroundScope
    )

  val socRecCrypto = SocRecCryptoFake()

  val tcAliceUnverified = EndorsedTrustedContact(
    relationshipId = "rel-123",
    trustedContactAlias = TrustedContactAlias("alice"),
    authenticationState = AWAITING_VERIFY,
    keyCertificate = TrustedContactKeyCertificateFake,
    roles = setOf(TrustedContactRole.SocialRecoveryContact)
  )
  val tcAliceVerified = tcAliceUnverified.copy(authenticationState = VERIFIED)
  val tcAliceTampered = tcAliceUnverified.copy(authenticationState = TAMPERED)

  val tcBobUnverified = EndorsedTrustedContact(
    relationshipId = "rel-456",
    trustedContactAlias = TrustedContactAlias("bob"),
    authenticationState = AWAITING_VERIFY,
    keyCertificate = TrustedContactKeyCertificateFake2,
    roles = setOf(TrustedContactRole.SocialRecoveryContact)
  )
  val tcBobVerified = tcBobUnverified.copy(authenticationState = VERIFIED)

  val appSessionManager = AppSessionManagerFake()
  val postSocRecTaskRepository = PostSocRecTaskRepositoryMock()
  val accountService = AccountServiceFake()

  fun TestScope.socRecService(): SocRecServiceImpl {
    socRecFake = socRecFake()
    return SocRecServiceImpl(
      socRecF8eClientProvider = suspend { socRecFake },
      socRecRelationshipsDao = dao,
      socRecEnrollmentAuthenticationDao = authDao,
      socRecCrypto = socRecCrypto,
      socialRecoveryCodeBuilder = SocialRecoveryCodeBuilderFake(),
      appSessionManager = appSessionManager,
      postSocRecTaskRepository = postSocRecTaskRepository,
      accountService = accountService
    )
  }

  afterTest {
    accountService.reset()
    accountService.setActiveAccount(FullAccountMock)
    appKeyDao.reset()
    appSessionManager.reset()
    dao.clear()
    postSocRecTaskRepository.reset()
    socRecCrypto.reset()
    socRecCrypto.reset()
    socRecFake.reset()
  }

  // TODO(W-6203): this test is racy because syncLoop overwrites the dao with f8e data in the loop.
  xtest("sync relationships when db is changed") {
    /**
     * TODO: Can't use kotest's test scope due to a bug in kotest
     * https://github.com/kotest/kotest/pull/3717#issuecomment-1858174448
     *
     * This should be fixed in 5.9.0
     */
    val service = socRecService()

    backgroundScope.launch {
      service.executeWork()
    }

    service.relationships.filterNotNull().first().shouldBeEmpty()

    socRecCrypto.validCertificates += tcAliceUnverified.keyCertificate
    dao.setSocRecRelationships(
      SocRecRelationships.EMPTY.copy(
        endorsedTrustedContacts = immutableListOf(tcAliceVerified)
      )
    )

    service.relationships
      .filterNotNull()
      .first { !it.isEmpty() }
      .shouldOnlyHaveEndorsed(tcAliceVerified)
  }

  test("on demand sync and verify relationships") {
    val service = socRecService()

    backgroundScope.launch {
      service.executeWork()
    }

    service.relationships.test {
      awaitItem().shouldBeNull() // initial loading

      // Mark tcAlice's cert as valid
      socRecCrypto.validCertificates += tcAliceUnverified.keyCertificate
      // Add tcAlice to f8e
      socRecFake.endorsedTrustedContacts.add(tcAliceUnverified)

      // Sync and verify
      service.syncAndVerifyRelationships(FullAccountMock)

      awaitItem()
        .shouldNotBeNull()
        .shouldOnlyHaveEndorsed(tcAliceVerified)
    }
  }

  test("sync and verify relationships from service with prefetch") {
    val service = socRecService()

    backgroundScope.launch {
      service.executeWork()
    }

    service.relationships.test {
      awaitItem().shouldBeNull() // initial loading

      // Mark tcAlice's cert as valid
      socRecCrypto.validCertificates += tcAliceUnverified.keyCertificate
      // Add tcAlice to f8e
      socRecFake.endorsedTrustedContacts.add(tcAliceUnverified)

      // Sync and verify
      service.syncAndVerifyRelationships(FullAccountMock)

      awaitItem()
        .shouldNotBeNull()
        .shouldOnlyHaveEndorsed(tcAliceVerified)
    }
  }

  test("invalid trusted contacts are marked as tampered") {
    val service = socRecService()

    backgroundScope.launch {
      service.executeWork()
    }

    service.relationships.test {
      awaitItem().shouldBeNull() // initial loading

      // Mark tcAlice's cert as invalid
      socRecCrypto.invalidCertificates += tcAliceUnverified.keyCertificate
      // Mark tcBob's cert as valid
      socRecCrypto.validCertificates += tcBobUnverified.keyCertificate

      // Add both to f8e
      socRecFake.endorsedTrustedContacts += tcAliceUnverified
      socRecFake.endorsedTrustedContacts += tcBobUnverified

      service.syncAndVerifyRelationships(FullAccountMock)

      awaitItem()
        .shouldNotBeNull()
        .shouldOnlyHaveEndorsed(tcAliceTampered, tcBobVerified)
    }
  }

  test("syncing does not occur while app is in the background") {
    val service = socRecService()

    appSessionManager.appDidEnterBackground()
    backgroundScope.launch {
      service.executeWork()
    }

    service.relationships.test {
      awaitItem().shouldBeNull() // initial loading

      // Mark tcAlice's cert as invalid
      socRecCrypto.invalidCertificates += tcAliceUnverified.keyCertificate
      // Mark tcBob's cert as valid
      socRecCrypto.validCertificates += tcBobUnverified.keyCertificate

      // Add both to f8e
      socRecFake.endorsedTrustedContacts += tcAliceUnverified
      socRecFake.endorsedTrustedContacts += tcBobUnverified

      appSessionManager.appDidEnterForeground()
      awaitItem()
        .shouldNotBeNull()
        .shouldOnlyHaveEndorsed(tcAliceTampered, tcBobVerified)
    }
  }

  test("justCompletedRecovery emits false by default") {
    val service = socRecService()
    service.justCompletedRecovery().test {
      awaitItem().shouldBeFalse()
    }
  }

  test("justCompletedRecovery emits true when post soc rec task state is HardwareReplacementScreens") {
    val service = socRecService()
    service.justCompletedRecovery().test {
      awaitItem().shouldBeFalse()

      postSocRecTaskRepository.mutableState.value =
        PostSocialRecoveryTaskState.HardwareReplacementScreens

      awaitItem().shouldBeTrue()
    }
  }

  test("justCompletedRecovery emits false when post soc rec task state is not HardwareReplacementScreens") {
    val service = socRecService()
    postSocRecTaskRepository.mutableState.value =
      PostSocialRecoveryTaskState.HardwareReplacementNotification

    service.justCompletedRecovery().test {
      awaitItem().shouldBeFalse()
    }
  }
})
