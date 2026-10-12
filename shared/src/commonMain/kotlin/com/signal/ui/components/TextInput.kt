package com.signal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signal.input.remoteItem
import com.signal.ui.theme.SignalTheme

/**
 * A labeled text field for the 10-foot UI. OK opens the Android TV on-screen keyboard, which also
 * offers voice input (PLAN §4.2); a physical keyboard types straight in, including in the harness.
 */
@Composable
fun SField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    /** Where Down goes from this field, when the nearest element below isn't the right one. */
    down: FocusRequester? = null,
) {
    val c = SignalTheme.colors
    var focused by remember { mutableStateOf(false) }
    val controller = LocalSoftwareKeyboardController.current
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SText(label, 22, Modifier.padding(bottom = 8.dp), weight = FontWeight.SemiBold, color = c.muted)
        Box(
            Modifier.fillMaxWidth().height(72.dp).background(if (focused) c.accent else c.surface).padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = if (focused) c.onAccent else c.text, fontSize = 26.sp, fontFamily = SignalTheme.font),
                cursorBrush = SolidColor(if (focused) c.onAccent else c.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                    .let { if (down != null) it.focusProperties { this.down = down } else it }
                    .onFocusChanged { focused = it.isFocused }
                    .remoteItem(onSelect = { controller?.show() }, isTextInput = true),
            )
        }
    }
}
