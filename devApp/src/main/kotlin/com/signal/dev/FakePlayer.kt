package com.signal.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.signal.player.AspectMode
import com.signal.player.PlayRequest
import com.signal.player.PlaybackState
import com.signal.player.Track
import com.signal.player.VideoInfo
import com.signal.player.VideoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shows a colored frame with the channel label instead of video (PLAN §7.1), for working offline
 * or without using up a provider connection. Run with `-Psignal.player=fake`.
 */
class FakePlayer : VideoPlayer {
    private companion object { const val FAKE_LENGTH_MS = 10 * 60_000L }

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun play(request: PlayRequest) {
        startedAt = clockMs(); offsetMs = request.startPositionMs; pausedAt = null
        _state.value = PlaybackState(
            status = PlaybackState.Status.PLAYING,
            request = request,
            video = VideoInfo(1920, 1080, 59.94f, "Fake", "Fake", 2),
            audioTracks = listOf(Track("en", "English · Fake", "en", "AAC"), Track("es", "Spanish · Fake", "es", "AAC")),
            subtitleTracks = listOf(Track("en", "English", "en", null)),
            selectedAudioTrackId = "en",
        )
    }

    // On-demand: a pretend clock, so resume and autoplay can be tried without video.
    private var startedAt = 0L
    private var offsetMs = 0L
    private var pausedAt: Long? = null
    private fun clockMs() = System.currentTimeMillis()

    override fun position(): com.signal.player.PlaybackPosition {
        val req = _state.value.request ?: return com.signal.player.PlaybackPosition(0, 0)
        if (req.isLive) return com.signal.player.PlaybackPosition(0, 0)
        val pos = offsetMs + ((pausedAt ?: clockMs()) - startedAt)
        return com.signal.player.PlaybackPosition(pos.coerceIn(0, FAKE_LENGTH_MS), FAKE_LENGTH_MS)
    }

    override fun seekTo(positionMs: Long) {
        offsetMs = positionMs
        startedAt = pausedAt ?: clockMs()
    }

    override fun setPaused(paused: Boolean) {
        if (paused && pausedAt == null) pausedAt = clockMs()
        if (!paused) pausedAt?.let { startedAt += clockMs() - it; pausedAt = null }
        _state.value = _state.value.copy(paused = paused)
    }

    override fun stop() { _state.value = PlaybackState() }
    override fun retry() { _state.value.request?.let(::play) }
    override fun selectAudioTrack(trackId: String) { _state.value = _state.value.copy(selectedAudioTrackId = trackId) }
    override fun selectSubtitleTrack(trackId: String?) { _state.value = _state.value.copy(selectedSubtitleTrackId = trackId) }
    override fun setVolume(volume: Float) = Unit
    override fun release() = stop()

    @Composable
    override fun Surface(modifier: Modifier, aspect: AspectMode) {
        val s by state.collectAsState()
        val label = s.request?.label ?: return
        // A stable color per channel, so zapping visibly changes the picture.
        val hue = (label.hashCode() and 0xffffff) % 360
        Box(modifier.background(Color.hsv(hue.toFloat(), 0.45f, 0.45f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("FAKE PLAYER", style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 24.sp))
                BasicText(label, style = TextStyle(color = Color.White, fontSize = 40.sp))
            }
        }
    }
}
