package build.wallet.ui.components.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.SheetModel
import build.wallet.statemachine.core.SystemUIModel
import build.wallet.ui.components.alertdialog.AlertDialog
import build.wallet.ui.components.sheet.Sheet
import build.wallet.ui.components.status.backgroundColor
import build.wallet.ui.components.system.SystemUI
import build.wallet.ui.components.tabbar.TabBarContainer
import build.wallet.ui.components.toast.Toast
import build.wallet.ui.compose.gestures.onTwoFingerDoubleTap
import build.wallet.ui.compose.gestures.onTwoFingerTripleTap
import build.wallet.ui.compose.thenIf
import build.wallet.ui.model.UiModelContent
import build.wallet.ui.model.alert.AlertModel
import build.wallet.ui.model.toast.ToastModel
import build.wallet.ui.theme.WalletTheme

/**
 * Defines UI scaffold layout for any screen in the app. Data and style rendered on the screen is
 * defined by its [model].
 */
@Composable
fun Screen(
  modifier: Modifier = Modifier,
  model: ScreenModel,
) {
  ScreenTheme(
    model.body,
    model.colorMode,
    model.presentationStyle,
    model.statusBannerModel?.backgroundColor
  ) { style ->
    Screen(
      modifier = modifier,
      addSystemBarsPadding = style.addSystemBarsPadding,
      bodyContent = {
        UiModelContent(model = model.body)
      },
      tabBarContent = {
        model.tabBar?.let {
          UiModelContent(model = it)
        }
      },
      statusBannerContent = {
        var statusBannerModel by remember {
          mutableStateOf(model.statusBannerModel)
        }

        if (model.statusBannerModel != null) {
          statusBannerModel = model.statusBannerModel
        }

        AnimatedVisibility(
          visible = model.statusBannerModel != null,
          enter = slideInVertically(
            initialOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(durationMillis = 300)
          ),
          exit = slideOutVertically(
            targetOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(durationMillis = 300)
          )
        ) {
          statusBannerModel?.let {
            UiModelContent(model = it)
          }

          LaunchedEffect("reset-banner-model") {
            if (model.statusBannerModel == null) {
              statusBannerModel = null
            }
          }
        }
      },
      alertModel = model.alertModel,
      toastModel = model.toastModel,
      bottomSheetModel = model.bottomSheetModel,
      onTwoFingerDoubleTap = model.onTwoFingerDoubleTap,
      systemUiModel = model.systemUIModel
    )
  }
}

/**
 * Defines UI scaffold layout for any screen in the app. Data and style rendered on the screen is
 * defined by the content used to build a screen.
 */
@Composable
internal fun Screen(
  modifier: Modifier = Modifier,
  addSystemBarsPadding: Boolean = false,
  bodyContent: @Composable () -> Unit,
  tabBarContent: @Composable (() -> Unit)? = null,
  statusBannerContent: @Composable () -> Unit = {},
  toastModel: ToastModel? = null,
  alertModel: AlertModel? = null,
  bottomSheetModel: SheetModel? = null,
  systemUiModel: SystemUIModel? = null,
  onTwoFingerDoubleTap: (() -> Unit)? = null,
  onTwoFingerTripleTap: (() -> Unit)? = null,
) {
  @Composable
  fun ScreenContents() =
    ScreenContents(
      modifier = modifier,
      addSystemBarsPadding = addSystemBarsPadding,
      toastModel = toastModel,
      statusBannerContent = statusBannerContent,
      bodyContent = bodyContent,
      tabBarContent = tabBarContent,
      onTwoFingerDoubleTap = onTwoFingerDoubleTap,
      onTwoFingerTripleTap = onTwoFingerTripleTap
    )

  // Wrap ScreenContents in Surface with AlertDialog if
  // alertModel is nonnull
  if (alertModel != null) {
    Surface(
      modifier =
        Modifier
          .fillMaxSize()
          .alpha(0.5f),
      color = Color.Black
    ) {
      AlertDialog(model = alertModel)
      ScreenContents()
    }
  } else {
    ScreenContents()
  }

  // Add bottom sheet, system UI, toast if any
  bottomSheetModel?.let {
    Sheet(model = it)
  }
  systemUiModel?.let {
    SystemUI(model = it)
  }

  toastModel?.let {
    Toast(model = it)
  }
}

@Composable
private fun ScreenContents(
  modifier: Modifier = Modifier,
  addSystemBarsPadding: Boolean,
  toastModel: ToastModel?,
  statusBannerContent: @Composable () -> Unit,
  bodyContent: @Composable () -> Unit,
  tabBarContent: @Composable (() -> Unit)? = null,
  onTwoFingerDoubleTap: (() -> Unit)? = null,
  onTwoFingerTripleTap: (() -> Unit)? = null,
) {
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(color = WalletTheme.colors.background)
        .thenIf(addSystemBarsPadding) {
          if (toastModel != null) {
            // If we're showing a toast, we need to explicitly ignore the bottom bar safe area
            // so that the toast can slide in from the bottom unobstructed
            Modifier.statusBarsPadding()
          } else {
            Modifier.systemBarsPadding()
          }
        }
        .onTwoFingerDoubleTap {
          onTwoFingerDoubleTap?.invoke()
        }
        .onTwoFingerTripleTap {
          onTwoFingerTripleTap?.invoke()
        }
  ) {
    TabBarContainer(
      statusBannerContent = statusBannerContent,
      tabBarContent = tabBarContent,
      bodyContent = bodyContent
    )
  }
}
