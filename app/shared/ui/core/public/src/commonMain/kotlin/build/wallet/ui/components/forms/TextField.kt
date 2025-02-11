package build.wallet.ui.components.forms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization.Companion.Characters
import androidx.compose.ui.text.input.KeyboardCapitalization.Companion.None
import androidx.compose.ui.text.input.KeyboardCapitalization.Companion.Sentences
import androidx.compose.ui.text.input.KeyboardCapitalization.Companion.Words
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import build.wallet.ui.components.button.Button
import build.wallet.ui.components.forms.TextFieldOverflowCharacteristic.*
import build.wallet.ui.components.label.Label
import build.wallet.ui.components.label.LabelTreatment
import build.wallet.ui.components.label.labelStyle
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.button.ButtonModel.Treatment.Secondary
import build.wallet.ui.model.input.TextFieldModel
import build.wallet.ui.model.input.TextFieldModel.Capitalization
import build.wallet.ui.model.input.TextFieldModel.KeyboardType.*
import build.wallet.ui.model.input.TextFieldModel.KeyboardType.Number
import build.wallet.ui.theme.WalletTheme
import build.wallet.ui.tokens.LabelType
import androidx.compose.material3.TextField as MaterialTextField

@Composable
fun TextField(
  modifier: Modifier = Modifier,
  model: TextFieldModel,
  labelType: LabelType = LabelType.Body2Regular,
  textFieldOverflowCharacteristic: TextFieldOverflowCharacteristic = Truncate,
  trailingButtonModel: ButtonModel? = null,
) {
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect("request-default-focus") {
    if (model.focusByDefault && focusRequester.captureFocus()) {
      focusRequester.requestFocus()
    }
  }

  // State for managing TextField's text value and cursor position
  var textState by remember(model.value, model.selectionOverride) {
    mutableStateOf(
      TextFieldValue(
        text = model.value,
        selection =
          model.selectionOverride
            // Apply the overridden selection from the model if present
            ?.let { TextRange(it.first, it.last) }
            // Otherwise, use value's length as initial.
            ?: TextRange(model.value.length)
      )
    )
  }

  TextField(
    modifier = modifier,
    placeholderText = model.placeholderText,
    value = textState,
    labelType = labelType,
    onValueChange = { newTextFieldValue ->
      model.maxLength?.let { maxLength ->
        if (newTextFieldValue.text.length <= maxLength) {
          textState = newTextFieldValue
          model.onValueChange(
            textState.text,
            textState.selection.start..textState.selection.end
          )
        }
      } ?: run {
        textState = newTextFieldValue
        model.onValueChange(
          textState.text,
          textState.selection.start..textState.selection.end
        )
      }
    },
    focusRequester = focusRequester,
    textFieldOverflowCharacteristic = textFieldOverflowCharacteristic,
    trailingButtonModel = trailingButtonModel,
    keyboardOptions =
      KeyboardOptions(
        keyboardType =
          when (model.keyboardType) {
            Default -> KeyboardType.Text
            Email -> KeyboardType.Email
            Decimal -> KeyboardType.Decimal
            Number -> KeyboardType.Number
            Phone -> KeyboardType.Phone
            Uri -> KeyboardType.Uri
          },
        autoCorrect = model.enableAutoCorrect,
        capitalization =
          when (model.capitalization) {
            Capitalization.None -> None
            Capitalization.Characters -> Characters
            Capitalization.Words -> Words
            Capitalization.Sentences -> Sentences
          }
      ),
    keyboardActions =
      KeyboardActions(
        onDone = model.onDone?.let { { it.invoke() } }
      ),
    visualTransformation =
      when (model.masksText) {
        true -> PasswordVisualTransformation()
        false -> VisualTransformation.None
      }
  )
}

@Composable
fun TextField(
  modifier: Modifier = Modifier,
  focusRequester: FocusRequester = remember { FocusRequester() },
  placeholderText: String,
  value: TextFieldValue,
  labelType: LabelType = LabelType.Body2Regular,
  textFieldOverflowCharacteristic: TextFieldOverflowCharacteristic = Truncate,
  trailingButtonModel: ButtonModel? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  onValueChange: (TextFieldValue) -> Unit,
) {
  var textStyle =
    WalletTheme.labelStyle(
      type = labelType,
      treatment = LabelTreatment.Primary
    )

  when (textFieldOverflowCharacteristic) {
    // If TextFieldOverflowCharacteristic is Resize, we want to wrap our TextField in
    // BoxWithConstraints so we can use it to compute paragraph intrinsics to
    is Resize -> {
      BoxWithConstraints {
        val calculateParagraph = @Composable {
          Paragraph(
            paragraphIntrinsics =
              ParagraphIntrinsics(
                text = value.text,
                style = textStyle,
                spanStyles = emptyList(),
                placeholders = emptyList(),
                density = LocalDensity.current,
                fontFamilyResolver = LocalFontFamilyResolver.current
              ),
            constraints = Constraints(),
            maxLines = textFieldOverflowCharacteristic.maxLines,
            ellipsis = false
          )
        }

        var intrinsics = calculateParagraph()
        with(LocalDensity.current) {
          while (
            (intrinsics.width.toDp() > maxWidth || intrinsics.didExceedMaxLines) &&
            textStyle.fontSize >= textFieldOverflowCharacteristic.minFontSize
          ) {
            textStyle =
              textStyle.copy(
                fontSize = textStyle.fontSize * textFieldOverflowCharacteristic.scaleFactor
              )
            intrinsics = calculateParagraph()
          }
        }

        TextFieldWithCharacteristic(
          modifier = modifier.focusRequester(focusRequester),
          placeholderText = placeholderText,
          value = value,
          textStyle = textStyle,
          textFieldOverflowCharacteristic = textFieldOverflowCharacteristic,
          trailingButtonModel = trailingButtonModel,
          keyboardOptions = keyboardOptions,
          keyboardActions = keyboardActions,
          visualTransformation = visualTransformation,
          onValueChange = onValueChange
        )
      }
    }
    else -> {
      TextFieldWithCharacteristic(
        modifier = modifier.focusRequester(focusRequester),
        placeholderText = placeholderText,
        value = value,
        textStyle = textStyle,
        textFieldOverflowCharacteristic = textFieldOverflowCharacteristic,
        trailingButtonModel = trailingButtonModel,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        onValueChange = onValueChange
      )
    }
  }
}

@Composable
fun TextFieldWithCharacteristic(
  modifier: Modifier = Modifier,
  placeholderText: String,
  value: TextFieldValue,
  textStyle: TextStyle,
  textFieldOverflowCharacteristic: TextFieldOverflowCharacteristic,
  trailingButtonModel: ButtonModel?,
  keyboardOptions: KeyboardOptions,
  keyboardActions: KeyboardActions,
  visualTransformation: VisualTransformation,
  onValueChange: (TextFieldValue) -> Unit,
) {
  Row(
    modifier =
      modifier
        .clip(RoundedCornerShape(size = 32.dp))
        .background(color = WalletTheme.colors.foreground10),
    verticalAlignment = Alignment.CenterVertically
  ) {
    MaterialTextField(
      modifier = Modifier.weight(1F),
      value = value,
      onValueChange = onValueChange,
      textStyle = textStyle,
      placeholder = {
        Label(
          text = placeholderText,
          type = LabelType.Body2Regular,
          treatment = LabelTreatment.Secondary
        )
      },
      singleLine = textFieldOverflowCharacteristic !is Multiline,
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = WalletTheme.colors.foreground10,
          unfocusedContainerColor = WalletTheme.colors.foreground10,
          cursorColor = WalletTheme.colors.bitkeyPrimary,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent
        ),
      keyboardOptions = keyboardOptions,
      visualTransformation = visualTransformation,
      keyboardActions = keyboardActions
    )

    trailingButtonModel?.let {
      Button(
        modifier = Modifier.padding(end = 12.dp),
        model = trailingButtonModel
      )
    }
  }
}
