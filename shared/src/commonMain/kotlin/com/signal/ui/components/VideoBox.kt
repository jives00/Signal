package com.signal.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.player.AspectMode
import com.signal.player.PlaybackState
import com.signal.ui.LocalApp
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.delay

/**
 * The shared player's surface plus its states (PLAN §4.3.3 → Errors and buffering): a spinner
 * after about a second of stalled video, and the error with a retry hint in place of the video.
 */
@Composable
fun VideoBox(modifier: Modifier = Modifier, aspect: AspectMode = AspectMode.FIT, compact: Boolean = false, emptyText: String? = null) {
    val app = LocalApp.current
    val state by app.platform.player.state.collectAsState()
    val c = SignalTheme.colors
    Box(modifier.background(c.videoGround), contentAlignment = Alignment.Center) {
        if (state.status != PlaybackState.Status.IDLE) app.platform.player.Surface(Modifier.fillMaxSize(), aspect)
        when (state.status) {
            PlaybackState.Status.BUFFERING -> DelayedSpinner(if (compact) 48 else 72)
            PlaybackState.Status.ERROR -> Column(Modifier.background(c.videoGround).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SText("Can't play this channel", if (compact) 26 else 32, weight = FontWeight.ExtraBold)
                SText(state.error?.message ?: "", if (compact) 22 else 26, color = c.muted, maxLines = 3)
                SText(if (compact) "Press OK to retry" else "Press OK to retry · Up/Down to change channel", 22, Modifier.padding(top = 12.dp), color = c.muted)
            }
            PlaybackState.Status.IDLE -> emptyText?.let { SText(it, 24, color = c.muted) }
            PlaybackState.Status.PLAYING -> {}
        }
    }
}

/** Nothing for short stalls; a spinner after about 1 second (PLAN §4.3.3). */
@Composable
private fun DelayedSpinner(size: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1000)
        visible = true
    }
    if (!visible) return
    val accent = SignalTheme.colors.accent
    val angle by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart))
    Canvas(Modifier.size(size.dp)) {
        val stroke = this.size.width / 10f
        drawArc(accent, angle, 270f, useCenter = false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke))
    }
}
