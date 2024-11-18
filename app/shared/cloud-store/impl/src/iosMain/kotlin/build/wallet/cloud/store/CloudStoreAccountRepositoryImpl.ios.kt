package build.wallet.cloud.store

import build.wallet.logging.logFailure
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

actual class CloudStoreAccountRepositoryImpl(
  private val iCloudAccountRepository: iCloudAccountRepository,
) : CloudStoreAccountRepository {
  actual override suspend fun currentAccount(
    cloudStoreServiceProvider: CloudStoreServiceProvider,
  ): Result<CloudStoreAccount?, CloudStoreAccountError> {
    return when (cloudStoreServiceProvider) {
      is iCloudDrive -> iCloudAccountRepository.currentAccount()
      else -> error("Cloud store service provider $cloudStoreServiceProvider is not supported")
    }.logFailure { "Error loading current cloud store account." }
  }

  actual override suspend fun clear(): Result<Unit, Throwable> = Ok(Unit)
}
