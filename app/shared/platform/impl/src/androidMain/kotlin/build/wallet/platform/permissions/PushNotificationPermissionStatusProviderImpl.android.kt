package build.wallet.platform.permissions

import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import build.wallet.platform.PlatformContext
import build.wallet.platform.permissions.Permission.PushNotifications
import build.wallet.platform.permissions.PermissionStatus.Authorized
import build.wallet.platform.permissions.PermissionStatus.NotDetermined
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class PushNotificationPermissionStatusProviderImpl actual constructor(
  platformContext: PlatformContext,
) : PushNotificationPermissionStatusProvider {
  private val statusFlow =
    MutableStateFlow(
      value =
        when (val manifestPermission = PushNotifications.manifestPermission()) {
          null -> Authorized
          else ->
            when (
              ContextCompat.checkSelfPermission(
                platformContext.appContext,
                manifestPermission
              )
            ) {
              PERMISSION_GRANTED -> Authorized
              else -> NotDetermined
            }
        }
    )

  actual override fun pushNotificationStatus(): StateFlow<PermissionStatus> = statusFlow

  actual override fun updatePushNotificationStatus(status: PermissionStatus) {
    statusFlow.tryEmit(status)
  }
}
