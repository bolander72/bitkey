package build.wallet.recovery.sweep

import build.wallet.account.AccountService
import build.wallet.account.AccountStatus.ActiveAccount
import build.wallet.analytics.events.AppSessionManager
import build.wallet.analytics.events.AppSessionState
import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.keybox.Keybox
import build.wallet.feature.flags.PromptSweepFeatureFlag
import build.wallet.feature.isEnabled
import build.wallet.logging.logFailure
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class SweepServiceImpl(
  private val accountService: AccountService,
  private val appSessionManager: AppSessionManager,
  private val promptSweepFeatureFlag: PromptSweepFeatureFlag,
  private val sweepGenerator: SweepGenerator,
) : SweepService, SweepSyncWorker {
  /**
   * Caches the value of the sweep required flag. Synced by the [executeWork].
   */
  override val sweepRequired = MutableStateFlow(false)

  override suspend fun checkForSweeps() {
    if (promptSweepFeatureFlag.isEnabled()) {
      sweepRequired.value = isSweepRequired()
    } else {
      sweepRequired.value = false
    }
  }

  override suspend fun executeWork() {
    coroutineScope {
      launch {
        // Sync the prompt sweep required if:
        //  - the flag is on
        //  - the app is in the foreground
        //  - there is a sweep available
        promptSweepFeatureFlag.flagValue()
          .collectLatest { flag ->
            if (flag.isEnabled()) {
              appSessionManager.appSessionState
                .collectLatest { state ->
                  if (state == AppSessionState.FOREGROUND) {
                    while (isActive) {
                      sweepRequired.value = isSweepRequired()
                      delay(5.minutes)
                    }
                  }
                }
            }
          }
      }
    }
  }

  /**
   * Returns `true` if customer should perform a sweep transaction.
   */
  private suspend fun isSweepRequired(): Boolean {
    val accountStatus = accountService.accountStatus().first().get()
    val activeFullAccount = (accountStatus as? ActiveAccount)?.account as? FullAccount

    return if (activeFullAccount != null) {
      sweepGenerator.generateSweep(activeFullAccount.keybox)
        .map { it.isNotEmpty() }
        .logFailure { "Failure Generating Sweep used to check for funds on old wallets" }
        .get() ?: false
    } else {
      false
    }
  }

  override suspend fun prepareSweep(keybox: Keybox): Result<Sweep?, Error> =
    coroutineBinding {
      val sweepPsbts = sweepGenerator.generateSweep(keybox).bind()

      when {
        sweepPsbts.isEmpty() -> null
        else -> Sweep(unsignedPsbts = sweepPsbts.toSet())
      }
    }
}
