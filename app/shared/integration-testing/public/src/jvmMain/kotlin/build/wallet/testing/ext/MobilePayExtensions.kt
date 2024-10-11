package build.wallet.testing.ext

import app.cash.turbine.test
import build.wallet.auth.AuthTokenScope
import build.wallet.coroutines.turbine.awaitUntil
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.limit.MobilePayData
import build.wallet.limit.SpendingLimit
import build.wallet.money.FiatMoney
import build.wallet.nfc.platform.signAccessToken
import build.wallet.testing.AppTester
import build.wallet.testing.fakeTransact
import com.github.michaelbull.result.getOrThrow
import com.github.michaelbull.result.toErrorIfNull
import kotlinx.datetime.TimeZone

/**
 * Sets up mobile pay limits.
 */
suspend fun AppTester.setupMobilePay(limit: FiatMoney): SpendingLimit {
  return app.run {
    val account = getActiveFullAccount()
    val accessToken =
      appComponent.authTokensRepository
        .getAuthTokens(account.accountId, AuthTokenScope.Global)
        .toErrorIfNull { IllegalStateException("Auth tokens missing.") }
        .getOrThrow()
        .accessToken
    val signResponse =
      nfcTransactor.fakeTransact { session, command ->
        command.signAccessToken(session, accessToken)
      }.getOrThrow()
    val spendingLimit = SpendingLimit(true, limit, TimeZone.UTC)
    appComponent.mobilePayService.setLimit(
      account = account,
      spendingLimit = spendingLimit,
      hwFactorProofOfPossession = HwFactorProofOfPossession(signResponse)
    ).getOrThrow()

    appComponent.mobilePayService.mobilePayData.test {
      awaitUntil {
        it is MobilePayData.MobilePayEnabledData &&
          it.activeSpendingLimit.amount == limit
      }
    }

    spendingLimit
  }
}
