package com.signal.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.signal.player.AspectMode
import com.signal.player.FrameRateMeter
import com.signal.player.PlayRequest
import com.signal.player.PlaybackError
import com.signal.player.PlaybackState
import com.signal.player.Track
import com.signal.player.VideoInfo
import com.signal.player.VideoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.SocketTimeoutException

/**
 * The Shield's player (PLAN §5.6): one Media3 ExoPlayer for the whole app. The Home hero, the Live
 * TV preview and full screen each attach their own view to it, so moving between them never
 * re-buffers. Audio focus and "becoming noisy" are handled by Media3 (PLAN §4.2).
 */
@OptIn(UnstableApi::class)
class ExoVideoPlayer(context: Context) : VideoPlayer {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private var request: PlayRequest? = null
    private var autoRetried = false

    /** Measures the frame rate when the stream doesn't declare one; null once measured, or while nothing plays. */
    @Volatile private var fpsMeter: FrameRateMeter? = null
    private var measuredFps: Float? = null

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        // FFmpeg (the LGPL build, PLAN §5.3) only after the platform: AC3/E-AC3/DTS pass through to a
        // receiver when the HDMI chain supports it, and FFmpeg decodes them only when it doesn't (PLAN §5.6).
        .setRenderersFactory(DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
        // "Balanced" live buffer: a short start for fast zapping (PLAN §5.6 → Zapping fast).
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 1_000, 2_000)
                .build(),
        )
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(Listener())
            addAnalyticsListener(ZapTiming())
            // Many TS streams don't declare a frame rate, so measure it from the frames (on the
            // playback thread) for the info bar.
            setVideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
                val meter = fpsMeter
                if (format.frameRate > 0 || meter == null) return@setVideoFrameMetadataListener
                meter.add(presentationTimeUs)?.let { fps ->
                    main.post {
                        if (fpsMeter !== meter) return@post
                        fpsMeter = null
                        measuredFps = fps
                        _state.update { it.copy(video = videoInfo()) }
                    }
                }
            }
        }

    // When play() was called, and the stages since: the time to first frame for PLAN §10 (zap under 1 s).
    private var playStartedAt = 0L
    private val stages = StringBuilder()

    /** Logs where a tune's time goes: data in, the video format known, the decoder ready, the first frame. */
    private inner class ZapTiming : androidx.media3.exoplayer.analytics.AnalyticsListener {
        private fun mark(stage: String) {
            if (playStartedAt == 0L) return
            stages.append(stage).append(" +").append(android.os.SystemClock.elapsedRealtime() - playStartedAt).append("ms, ")
        }
        override fun onLoadStarted(time: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, load: androidx.media3.exoplayer.source.LoadEventInfo, media: androidx.media3.exoplayer.source.MediaLoadData) {
            if (stages.isEmpty()) mark("request")
        }
        override fun onDownstreamFormatChanged(time: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, media: androidx.media3.exoplayer.source.MediaLoadData) {
            if (media.trackType == C.TRACK_TYPE_VIDEO && "format" !in stages) mark("format")
        }
        override fun onVideoDecoderInitialized(time: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            mark("decoder")
        }
        override fun onRenderedFirstFrame(time: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (playStartedAt == 0L) return
            mark("first frame")
            android.util.Log.i("Signal", "Zap timing: ${stages.toString().trimEnd(',', ' ')}")
            playStartedAt = 0L
        }
    }

    /** The HTTP side of the timing: the response (after any redirects), then the first bytes. Called on the loader thread. */
    private val transferTiming = object : androidx.media3.datasource.TransferListener {
        @Volatile private var bytesSeen = true
        private fun mark(stage: String) {
            val started = playStartedAt
            if (started == 0L) return
            val ms = android.os.SystemClock.elapsedRealtime() - started
            main.post { if (playStartedAt == started) stages.append(stage).append(" +").append(ms).append("ms, ") }
        }
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {
            bytesSeen = false
            mark("response")
        }
        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (!bytesSeen) { bytesSeen = true; mark("first bytes") }
        }
        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
    }

    override fun play(request: PlayRequest) {
        this.request = request
        autoRetried = false
        playStartedAt = android.os.SystemClock.elapsedRealtime()
        stages.clear()
        fpsMeter = FrameRateMeter()
        measuredFps = null
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent(request.headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(request.headers - "User-Agent")
            .setTransferListener(transferTiming)
        val source = mediaSource(http, liveItem(request))
        // On-demand resumes where it left off (PLAN §4.3.8); live always starts at the live edge.
        if (!request.isLive && request.startPositionMs > 0) exo.setMediaSource(source, request.startPositionMs) else exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        _state.value = PlaybackState(status = PlaybackState.Status.BUFFERING, request = request)
    }

    /**
     * Closed captions embedded in the video (CEA-608/708, carried in H.264/HEVC SEI) are only exposed
     * by Media3 when the stream declares them, in the TS program table or the HLS playlist, and most
     * IPTV streams don't: HBO Drama showed no subtitles in Signal but did in another app (milestone 6).
     * So both always look for CC1 (608) and service 1 (708); a stream without captions just has none.
     */
    private fun mediaSource(http: DefaultHttpDataSource.Factory, item: MediaItem): androidx.media3.exoplayer.source.MediaSource {
        if (item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) {
            return androidx.media3.exoplayer.hls.HlsMediaSource.Factory(http)
                .setExtractorFactory(androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(0, /* exposeCea608WhenMissingDeclarations= */ true))
                .createMediaSource(item)
        }
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory().setTsSubtitleFormats(EMBEDDED_CAPTIONS)
        return DefaultMediaSourceFactory(http, extractors).createMediaSource(item)
    }

    private fun liveItem(request: PlayRequest): MediaItem {
        val builder = MediaItem.Builder().setUri(request.url)
        // Xtream TS has no extension hint on some providers; HLS is detected from ".m3u8".
        if (request.url.contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    override fun stop() {
        exo.stop()
        exo.clearMediaItems()
        fpsMeter = null
        measuredFps = null
        _state.value = PlaybackState()
    }

    override fun retry() {
        request?.let(::play)
    }

    override fun selectAudioTrack(trackId: String) = select(C.TRACK_TYPE_AUDIO, trackId)

    override fun selectSubtitleTrack(trackId: String?) {
        if (trackId == null) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else {
            select(C.TRACK_TYPE_TEXT, trackId)
        }
    }

    private fun select(type: Int, trackId: String) {
        val (groupIndex, trackIndex) = trackId.split(':').map(String::toInt)
        val group = exo.currentTracks.groups.getOrNull(groupIndex) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    override fun setVolume(volume: Float) {
        exo.volume = volume
    }

    override fun position(): com.signal.player.PlaybackPosition =
        com.signal.player.PlaybackPosition(exo.currentPosition.coerceAtLeast(0), exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0)

    override fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs.coerceAtLeast(0))
        _state.update { it.copy(ended = false) }
    }

    override fun setPaused(paused: Boolean) {
        exo.playWhenReady = !paused
        _state.update { it.copy(paused = paused) }
    }

    /** Bumped by [rebuildView]; the video view is keyed on it. */
    private var viewGeneration by mutableIntStateOf(0)

    /**
     * Replaces the video view with a new one. The screensaver stopped the activity while the view sat
     * collapsed on a page without video, and on the Shield its surface never showed again: frames
     * decoded and audio played, but SurfaceFlinger dropped every buffer ("No local sync point found")
     * until the app was reopened (user, 2026-10-15). A fresh view gets a fresh surface.
     */
    fun rebuildView() {
        viewGeneration++
    }

    /** Stop frees the decoders and the provider connection; the instance stays usable (PLAN §4.2). */
    override fun release() = stop()

    fun destroy() = exo.release()

    @Composable
    override fun Surface(modifier: Modifier, aspect: AspectMode) {
        Box(modifier, contentAlignment = Alignment.Center) {
            val ratio = aspect.forcedRatio
            key(viewGeneration) { AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setKeepContentOnPlayerReset(true)
                        isFocusable = false
                        player = exo
                        // The SurfaceView cuts its hole in the window when it draws. Inside Compose
                        // nothing redraws it once its surface exists, so a new full-screen view
                        // showed the background over the video (audio, grey picture) until an
                        // overlay happened to trigger a redraw. Redraw as soon as the surface is ready.
                        (videoSurfaceView as? SurfaceView)?.holder?.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                post { invalidate(); (parent as? View)?.invalidate() }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                post { invalidate() }
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                        })
                    }
                },
                update = { view ->
                    view.player = exo
                    view.resizeMode = when (aspect) {
                        AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                },
                onRelease = { view -> view.player = null },
                modifier = if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
            ) }
        }
    }

    private inner class Listener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val status = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.Status.BUFFERING
                Player.STATE_READY -> PlaybackState.Status.PLAYING
                Player.STATE_ENDED -> PlaybackState.Status.BUFFERING // Live streams "end" when the provider drops them.
                else -> return
            }
            if (playbackState == Player.STATE_ENDED) {
                // On-demand ends for real (autoplay, PLAN §4.3.9); live streams "end" when the provider drops them.
                if (request?.isLive == false) _state.update { it.copy(status = PlaybackState.Status.PLAYING, ended = true) } else retry()
                return
            }
            _state.update { it.copy(status = status, error = null) }
        }

        override fun onPlayerError(error: PlaybackException) {
            // One automatic retry, then the message (PLAN §4.3.3 → Stream failure).
            if (!autoRetried) {
                autoRetried = true
                main.postDelayed({ request?.let { r -> val keep = autoRetried; play(r); autoRetried = keep } }, 1000)
                return
            }
            _state.update { it.copy(status = PlaybackState.Status.ERROR, error = map(error)) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val audio = ArrayList<Track>()
            val text = ArrayList<Track>()
            var selectedAudio: String? = null
            var selectedText: String? = null
            tracks.groups.forEachIndexed { g, group ->
                for (t in 0 until group.length) {
                    val f = group.getTrackFormat(t)
                    val id = "$g:$t"
                    val track = Track(id, label(f), f.language, codecName(f.sampleMimeType))
                    when (group.type) {
                        C.TRACK_TYPE_AUDIO -> { audio += track; if (group.isTrackSelected(t)) selectedAudio = id }
                        C.TRACK_TYPE_TEXT -> { text += track; if (group.isTrackSelected(t)) selectedText = id }
                    }
                }
            }
            _state.update { it.copy(audioTracks = audio, subtitleTracks = text, selectedAudioTrackId = selectedAudio, selectedSubtitleTrackId = selectedText, video = videoInfo()) }
            // What the stream carries, for "no subtitles on this channel" reports (milestone 6).
            android.util.Log.i("Signal", "Tracks: " + tracks.groups.joinToString("; ") { group ->
                (0 until group.length).joinToString(", ") { t ->
                    val f = group.getTrackFormat(t)
                    "${f.sampleMimeType ?: f.containerMimeType}${f.language?.let { " $it" } ?: ""}${if (group.isTrackSupported(t)) "" else " (unsupported)"}"
                }
            })
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(video = videoInfo()) }
        }
    }

    private fun videoInfo(): VideoInfo? {
        val v = exo.videoFormat ?: return null
        val a = exo.audioFormat
        return VideoInfo(
            width = v.width,
            height = v.height,
            frameRate = v.frameRate.takeIf { it > 0 } ?: measuredFps,
            videoCodec = codecName(v.sampleMimeType),
            audioCodec = a?.let { codecName(it.sampleMimeType) },
            audioChannels = a?.channelCount?.takeIf { it > 0 },
        )
    }

    private fun label(f: Format): String = listOfNotNull(
        f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } },
        codecName(f.sampleMimeType),
        f.channelCount.takeIf { it > 0 }?.let { if (it == 6) "5.1" else if (it == 8) "7.1" else "${it}ch" },
    ).joinToString(" · ").ifEmpty { "Track" }

    private fun map(error: PlaybackException): PlaybackError {
        val http = generateSequence(error.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return when {
            http != null && http.responseCode in setOf(401, 403, 429, 458, 509) -> PlaybackError(PlaybackError.Kind.CONNECTION_LIMIT, "HTTP ${http.responseCode}")
            http != null -> PlaybackError(PlaybackError.Kind.OFFLINE, "HTTP ${http.responseCode}")
            generateSequence(error.cause) { it.cause }.any { it is SocketTimeoutException } ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackError(PlaybackError.Kind.TIMEOUT)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlaybackError(PlaybackError.Kind.OFFLINE)
            error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> PlaybackError(PlaybackError.Kind.UNSUPPORTED_FORMAT)
            else -> PlaybackError(PlaybackError.Kind.UNKNOWN, error.errorCodeName)
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "Signal/1.0 (Android TV)"

        /** The captions [mediaSource] looks for in every live and on-demand stream. */
        val EMBEDDED_CAPTIONS: List<Format> = listOf(
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).build(),
            Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).setAccessibilityChannel(1).build(),
        )

        fun codecName(mime: String?): String? = when (mime) {
            null -> null
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "AC3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2/MP3"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.APPLICATION_CEA608 -> "CC"
            MimeTypes.APPLICATION_CEA708 -> "CC (708)"
            else -> mime.substringAfter('/').uppercase()
        }
    }
}
