package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme
import com.signal.ui.theme.SignalType

/**
 * Root of the app on every platform. Milestone 0 placeholder: the nav bar's wordmark and a line
 * of text, enough to check the design-unit scaling on the Shield, the emulator and the harness.
 */
@Composable
fun SignalApp(versionLabel: String) {
    SignalTheme {
        val colors = SignalTheme.colors
        Column(Modifier.fillMaxSize().background(colors.bg)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(SignalSizes.NAV_BAR_HEIGHT.dp)
                    .padding(horizontal = SignalSizes.EDGE_MARGIN.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    buildAnnotatedString {
                        append("SIGNAL")
                        withStyle(SpanStyle(color = colors.accent)) { append(".") }
                    },
                    style = TextStyle(color = colors.text, fontSize = SignalType.WORDMARK.sp, fontWeight = FontWeight.ExtraBold),
                )
            }
            Box(Modifier.fillMaxWidth().height(SignalSizes.DIVIDER.dp).background(colors.divider))
            Column(Modifier.padding(SignalSizes.EDGE_MARGIN.dp)) {
                BasicText(
                    "Milestone 0",
                    style = TextStyle(color = colors.text, fontSize = SignalType.DISPLAY.sp, fontWeight = FontWeight.ExtraBold),
                )
                BasicText(
                    versionLabel,
                    style = TextStyle(color = colors.muted, fontSize = SignalType.BODY.sp),
                )
            }
        }
    }
}
