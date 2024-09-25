@file:Suppress("TooManyFunctions")

package build.wallet.ui.app.moneyhome.card

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import build.wallet.Progress
import build.wallet.bitkey.relationships.*
import build.wallet.compose.collections.buildImmutableList
import build.wallet.compose.collections.immutableListOf
import build.wallet.home.GettingStartedTask
import build.wallet.home.GettingStartedTask.TaskId.AddAdditionalFingerprint
import build.wallet.home.GettingStartedTask.TaskId.AddBitcoin
import build.wallet.home.GettingStartedTask.TaskId.EnableSpendingLimit
import build.wallet.home.GettingStartedTask.TaskId.InviteTrustedContact
import build.wallet.home.GettingStartedTask.TaskState.Complete
import build.wallet.home.GettingStartedTask.TaskState.Incomplete
import build.wallet.pricechart.DataPoint
import build.wallet.pricechart.PriceDirection
import build.wallet.statemachine.moneyhome.card.CardModel
import build.wallet.statemachine.moneyhome.card.CardModel.AnimationSet.Animation.Height
import build.wallet.statemachine.moneyhome.card.CardModel.AnimationSet.Animation.Scale
import build.wallet.statemachine.moneyhome.card.CardModel.CardContent.BitcoinPrice
import build.wallet.statemachine.moneyhome.card.CardModel.CardContent.DrillList
import build.wallet.statemachine.moneyhome.card.CardModel.CardStyle.Gradient
import build.wallet.statemachine.moneyhome.card.CardModel.CardStyle.Outline
import build.wallet.statemachine.moneyhome.card.backup.CloudBackupHealthCardModel
import build.wallet.statemachine.moneyhome.card.fwup.DeviceUpdateCardModel
import build.wallet.statemachine.moneyhome.card.gettingstarted.GettingStartedCardModel
import build.wallet.statemachine.moneyhome.card.gettingstarted.GettingStartedTaskRowModel
import build.wallet.statemachine.moneyhome.lite.card.BuyOwnBitkeyMoneyHomeCardModel
import build.wallet.statemachine.moneyhome.lite.card.WalletsProtectingMoneyHomeCardModel
import build.wallet.statemachine.recovery.hardware.HardwareRecoveryCardModel
import build.wallet.statemachine.recovery.socrec.RecoveryContactCardModel
import build.wallet.ui.components.card.Card
import build.wallet.ui.components.card.CardContent
import build.wallet.ui.components.card.GradientCard
import build.wallet.ui.theme.WalletTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant.Companion.DISTANT_FUTURE
import kotlinx.datetime.Instant.Companion.DISTANT_PAST
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.DurationUnit.SECONDS

@Composable
fun MoneyHomeCard(
  modifier: Modifier = Modifier,
  model: CardModel,
) {
  // The real height of the card is intrinsic but we have to use an actual value here in
  // order for the animation to work
  val height = remember { Animatable(model.estimatedHeight()) }
  val scale = remember { Animatable(1f) }

  LaunchedEffect(model.animation) {
    model.animation?.forEach { animationSet ->
      // Give each animation set a scope so that they happen one after the other and
      // use `launch` within an animation set to ensure they happen at the same time
      coroutineScope {
        animationSet.animations.forEach {
          val animationSpec: TweenSpec<Float> =
            tween(
              durationMillis =
                Duration.convert(
                  animationSet.durationInSeconds,
                  SECONDS,
                  MILLISECONDS
                ).toInt()
            )
          when (val animation = it) {
            is Height ->
              launch {
                height.animateTo(animation.value, animationSpec)
              }
            is Scale ->
              launch {
                scale.animateTo(animation.value, animationSpec)
              }
            else -> Unit
          }
        }
      }
    }
  }

  // Only add the height modifier if we are in the middle of an animation
  var cardModifier =
    modifier.clickable(
      interactionSource = MutableInteractionSource(),
      indication = null,
      enabled = model.onClick != null,
      onClick = {
        model.onClick?.invoke()
      }
    )
  if (model.animation != null) {
    cardModifier =
      cardModifier
        .height(height.value.dp)
  }

  when (val style = model.style) {
    Outline ->
      Card(
        modifier = cardModifier.scale(scale.value),
        paddingValues = PaddingValues(0.dp)
      ) {
        CardContent(
          model,
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(
                top = 20.dp,
                start = 20.dp,
                end = 20.dp,
                bottom = if (model.content == null) 20.dp else 0.dp
              )
        )
      }

    is Gradient ->
      GradientCard(
        modifier = cardModifier.scale(scale.value),
        backgroundColor = when (style.backgroundColor) {
          Gradient.BackgroundColor.Warning -> WalletTheme.colors.warning
          else -> WalletTheme.colors.containerBackgroundHighlight
        }
      ) {
        CardContent(
          model,
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(
                top = 0.dp
              )
        )
      }
  }
}

@Preview
@Composable
internal fun PreviewMoneyHomePriceCard(isLoading: Boolean = false) {
  MoneyHomeCard(
    model =
      CardModel(
        title = null,
        content = BitcoinPrice(
          isLoading = isLoading,
          priceChange = "10.00% today",
          priceDirection = PriceDirection.UP,
          lastUpdated = "Updated 12:00am",
          price = "$90,000.00",
          data = generateChartData(150)
            .takeUnless { isLoading }
            ?: immutableListOf()
        ),
        style = Outline
      )
  )
}

@Preview
@Composable
internal fun PreviewMoneyHomeGettingStarted() {
  MoneyHomeCard(
    model =
      GettingStartedCardModel(
        animations = null,
        taskModels =
          immutableListOf(
            GettingStartedTaskRowModel(
              task = GettingStartedTask(AddBitcoin, Incomplete),
              isEnabled = true,
              onClick = {},
              isRevampEnabled = false
            ),
            GettingStartedTaskRowModel(
              task = GettingStartedTask(EnableSpendingLimit, Incomplete),
              isEnabled = false,
              onClick = {},
              isRevampEnabled = false
            ),
            GettingStartedTaskRowModel(
              task = GettingStartedTask(InviteTrustedContact, Complete),
              isEnabled = true,
              onClick = {},
              isRevampEnabled = false
            ),
            GettingStartedTaskRowModel(
              task = GettingStartedTask(AddAdditionalFingerprint, Incomplete),
              isEnabled = true,
              onClick = {},
              isRevampEnabled = false
            )
          )
      )
  )
}

@Preview
@Composable
internal fun PreviewMoneyHomeCardDeviceUpdate() {
  MoneyHomeCard(
    model =
      DeviceUpdateCardModel(onUpdateDevice = {})
  )
}

@Preview
@Composable
internal fun PreviewMoneyHomeCardInvitationPending() {
  MoneyHomeCard(
    model =
      RecoveryContactCardModel(
        contact =
          Invitation(
            relationshipId = "foo",
            trustedContactAlias = TrustedContactAlias("Bela"),
            code = "token",
            codeBitLength = 20,
            expiresAt = DISTANT_FUTURE,
            roles = setOf(TrustedContactRole.SocialRecoveryContact)
          ),
        buttonText = "Pending",
        onClick = {}
      )
  )
}

@Preview
@Composable
internal fun PreviewMoneyHomeCardInvitationExpired() {
  MoneyHomeCard(
    model =
      RecoveryContactCardModel(
        contact =
          Invitation(
            relationshipId = "foo",
            trustedContactAlias = TrustedContactAlias("Bela"),
            code = "token",
            codeBitLength = 20,
            expiresAt = DISTANT_PAST,
            roles = setOf(TrustedContactRole.SocialRecoveryContact)
          ),
        buttonText = "Expired",
        onClick = {}
      )
  )
}

@Preview
@Composable
internal fun PreviewMoneyHomeCardReplacementPending() {
  MoneyHomeCard(
    model =
      HardwareRecoveryCardModel(
        title = "Replacement pending...",
        subtitle = "2 days remaining",
        delayPeriodProgress = Progress.Half,
        delayPeriodRemainingSeconds = 0,
        onClick = {}
      )
  )
}

@Preview
@Composable
internal fun PreviewMoneyHomeCardReplacementReady() {
  MoneyHomeCard(
    model =
      HardwareRecoveryCardModel(
        title = "Replacement Ready",
        delayPeriodProgress = Progress.Full,
        delayPeriodRemainingSeconds = 0,
        onClick = {}
      )
  )
}

@Preview
@Composable
internal fun PreviewMoneyHomeCardWalletsProtecting() {
  MoneyHomeCard(
    model =
      WalletsProtectingMoneyHomeCardModel(
        protectedCustomers =
          immutableListOf(
            ProtectedCustomer(
              relationshipId = "",
              alias = ProtectedCustomerAlias("Alice"),
              roles = setOf(TrustedContactRole.SocialRecoveryContact)
            ),
            ProtectedCustomer(
              relationshipId = "",
              alias = ProtectedCustomerAlias("Bob"),
              roles = setOf(TrustedContactRole.SocialRecoveryContact)
            )
          ),
        onProtectedCustomerClick = {},
        onAcceptInviteClick = {}
      )
  )
}

@Preview
@Composable
internal fun PreviewMoneyHomeCardBuyOwnBitkey() {
  MoneyHomeCard(
    model = BuyOwnBitkeyMoneyHomeCardModel(onClick = {})
  )
}

@Preview
@Composable
internal fun PreviewCloudBackupHealthCard() {
  MoneyHomeCard(
    model = CloudBackupHealthCardModel(
      title = "Problem with Google\naccount access",
      onActionClick = {}
    )
  )
}

private fun CardModel.estimatedHeight() =
  listOfNotNull(
    20f, // top padding
    17f, // title height
    subtitle?.let { 15f }, // subtitle height
    content?.let {
      when (it) {
        is DrillList ->
          // each row height + spacing in between rows
          (it.items.count() * 56f) + ((it.items.count() - 1) * 12f)
        is BitcoinPrice -> 100f
      }
    },
    if (content == null) {
      20f
    } else {
      null
    } // bottom padding if no content
  ).sum()

private fun generateChartData(pointCount: Int): ImmutableList<DataPoint> {
  return buildImmutableList {
    for (i in 0 until pointCount) {
      val y = abs(sin(i * PI / 30) * 20 + cos(i * PI / 15) * 10)
      add(DataPoint(i.toLong(), y))
    }
  }
}
