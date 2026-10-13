package com.signal.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * The one player interface (PLAN §5.6). It plays a URL with headers and knows nothing about
 * channels, sources or IPTV (PLAN §11.2), so the future unified app can lift it unchanged.
 * ExoPlayer implements it on the Shield; vlcj and a fake player implement it in the dev harness.
 */
interface VideoPlayer {
    val state: StateFlow<PlaybackState>

    fun play(request: PlayRequest)
    fun stop()
    fun retry()
    fun selectAudioTrack(trackId: String)
    /** Null turns subtitles off. */
    fun selectSubtitleTrack(trackId: String?)
    fun setVolume(volume: Float)

    /**
     * Where the video draws. The same player instance moves between surfaces (the Home hero, the
     * Live TV preview, full screen), so changing surface never re-buffers (PLAN §5.6).
     */
    @Composable
    fun Surface(modifier: Modifier, aspect: AspectMode)

    /** On-demand (PLAN §4.3.10): where playback is and how long the video is. Polled, since it changes every frame; zero for live. */
    fun position(): PlaybackPosition = PlaybackPosition(0, 0)

    fun seekTo(positionMs: Long) {}

    fun setPaused(paused: Boolean) {}

    /** Frees the decoder and the stream connection: the app is going to the background (PLAN §4.2). */
    fun release()
}

data class PlayRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val isLive: Boolean = true,
    /** On-demand: where to start, for Resume (PLAN §4.3.8). */
    val startPositionMs: Long = 0,
    /** For logs and diagnostics only; never the URL, which may embed credentials (PLAN §1). */
    val label: String,
)

data class PlaybackState(
    val status: Status = Status.IDLE,
    val request: PlayRequest? = null,
    val error: PlaybackError? = null,
    val video: VideoInfo? = null,
    val audioTracks: List<Track> = emptyList(),
    val subtitleTracks: List<Track> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val selectedSubtitleTrackId: String? = null,
    val paused: Boolean = false,
    /** On-demand only: the video played to its end (live streams retry instead). */
    val ended: Boolean = false,
) {
    enum class Status { IDLE, BUFFERING, PLAYING, ERROR }
}

/** The stream as decoded, not as labeled by the provider (PLAN §4.3.3 → Info bar). */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val videoCodec: String?,
    val audioCodec: String?,
    val audioChannels: Int?,
)

data class Track(val id: String, val label: String, val language: String?, val codec: String?)

/** Errors mapped to the messages in PLAN §4.3.3 → Errors and buffering. */
data class PlaybackError(val kind: Kind, val detail: String? = null) {
    enum class Kind { OFFLINE, TIMEOUT, UNSUPPORTED_FORMAT, CONNECTION_LIMIT, UNKNOWN }

    val message: String
        get() = when (kind) {
            Kind.OFFLINE -> "The channel is offline"
            Kind.TIMEOUT -> "The stream timed out"
            Kind.UNSUPPORTED_FORMAT -> "This format isn't supported"
            Kind.CONNECTION_LIMIT -> "The provider's connection limit is reached. Stop playback on another device and try again."
            Kind.UNKNOWN -> detail ?: "Something went wrong"
        }
}

/** PLAN §4.3.3 → Aspect ratio. */
enum class AspectMode(val label: String) {
    FIT("Fit"), FILL("Fill"), STRETCH("Stretch"), RATIO_16_9("16:9"), RATIO_4_3("4:3");

    val forcedRatio: Float?
        get() = when (this) {
            RATIO_16_9 -> 16f / 9f
            RATIO_4_3 -> 4f / 3f
            else -> null
        }
}

/** Human-readable stream info for the info bar, e.g. "1920×1080 · 59.94 fps · H.264 · E-AC3 5.1". */
fun VideoInfo.summary(): List<String> = buildList {
    add("$width×$height" + (frameRate?.let { " · ${formatFps(it)} fps" } ?: ""))
    videoCodec?.let(::add)
    if (audioCodec != null) add(audioCodec + (audioChannels?.let { " " + channelLayout(it) } ?: ""))
}

private fun formatFps(fps: Float): String {
    val hundredths = kotlin.math.round(fps * 100).toInt()
    return if (hundredths % 100 == 0) (hundredths / 100).toString()
    else "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0').trimEnd('0')}"
}

private fun channelLayout(channels: Int): String = when (channels) {
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${channels}ch"
}

data class PlaybackPosition(val positionMs: Long, val durationMs: Long)
