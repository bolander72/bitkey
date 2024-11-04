package build.wallet.ui.app.recovery

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import build.wallet.Progress
import build.wallet.statemachine.recovery.inprogress.waiting.AppDelayNotifyInProgressBodyModel
import build.wallet.ui.app.core.form.FormScreen
import build.wallet.ui.components.header.Header
import build.wallet.ui.components.timer.Timer
import build.wallet.ui.components.toolbar.Toolbar
import build.wallet.ui.tokens.LabelType
import build.wallet.ui.tooling.PreviewWalletTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.time.Duration.Companion.seconds

@Composable
fun AppDelayNotifyInProgressScreen(
  modifier: Modifier = Modifier,
  model: AppDelayNotifyInProgressBodyModel,
) {
  FormScreen(
    modifier = modifier,
    onBack = model.onExit,
    toolbarContent = { Toolbar(model.toolbar) },
    headerContent = {
      Header(
        headline = model.header.headline,
        subline =
          model.header.sublineModel?.let { subline ->
            buildAnnotatedString {
              append(subline.string)
            }
          },
        headlineLabelType = LabelType.Title1
      )
    },
    mainContent = {
      Spacer(modifier = Modifier.height(24.dp))
      Timer(model = model.timerModel)
    }
  )
}

@Preview
@Composable
fun AppDelayNotifyInProgressPreview() {
  PreviewWalletTheme {
    AppDelayNotifyInProgressScreen(
      model =
        AppDelayNotifyInProgressBodyModel(
          onStopRecovery = { },
          durationTitle = "18 hours",
          progress = Progress.Half,
          remainingDelayPeriod = 120.seconds,
          onExit = null
        )
    )
  }
}
