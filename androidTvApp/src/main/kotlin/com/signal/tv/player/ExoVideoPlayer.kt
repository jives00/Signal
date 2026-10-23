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
import androidx.compose.runtime.mutableStateOf
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
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.PlayerView
import com.signal.player.AspectMode
import com.signal.player.FrameRateMeter
import com.signal.player.PlayRequest
import com.signal.player.PlaybackError
import com.signal.player.DecoderMode
import com.signal.player.PlaybackState
import com.signal.player.PlayerSettings
import com.signal.player.SubtitleBackground
import com.signal.player.SubtitleMode
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
 *
 * Settings → Playback (PLAN §4.3.15 → 5): languages, subtitles and tunneling go to the track selector
 * at once. The decoder, passthrough and buffer sizes are fixed when an ExoPlayer is built, so a play
 * that needs different ones (another setting, or live after on-demand) builds a new one first.
 */
@OptIn(UnstableApi::class)
class ExoVideoPlayer(private val context: Context) : VideoPlayer {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private var request: PlayRequest? = null
    /** Automatic retries left for the current request (Settings → Playback → Stream retries). */
    private var retriesLeft = 0
    @Volatile private var settings = PlayerSettings()
    /** Where the player's lines go: the app log once [SignalApplication] wires it, so Send logs carries them. */
    var log: (String) -> Unit = { android.util.Log.i("Signal", it) }
    /** Subtitle style for the views: state, so a change restyles the one on screen. */
    private var subtitleStyle by mutableStateOf(Style(settings))

    /** What an ExoPlayer instance is built with. */
    private data class Engine(val decoder: DecoderMode, val passthrough: Boolean, val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val targetBytes: Int)

    private data class Style(val size: Float, val color: Int, val background: SubtitleBackground) {
        constructor(s: PlayerSettings) : this(s.subtitleSize.scale, s.subtitleColor.argb.toInt(), s.subtitleBackground)
    }

    private fun engineFor(live: Boolean): Engine = settings.let { s ->
        if (live) s.liveBuffer.let { Engine(s.decoder, s.passthrough, it.minMs, it.maxMs, it.startMs, it.rebufferMs, C.LENGTH_UNSET) }
        // The Shield's heap is 192 MB: a large on-demand buffer is capped in bytes as well as time.
        else s.vodBuffer.let { Engine(s.decoder, s.passthrough, it.minMs, it.maxMs, it.startMs, it.rebufferMs, if (it == com.signal.player.VodBuffer.LARGE) LARGE_VOD_BYTES else C.LENGTH_UNSET) }
    }

    /** Measures the frame rate when the stream doesn't declare one; null once measured, or while nothing plays. */
    @Volatile private var fpsMeter: FrameRateMeter? = null
    private var measuredFps: Float? = null

    private var engine: Engine = engineFor(live = true)
    private lateinit var trackSelector: DefaultTrackSelector
    private var exo: ExoPlayer = build(engine)

    private fun build(e: Engine): ExoPlayer {
        val selector = DefaultTrackSelector(context)
        trackSelector = selector
        val renderers = object : DefaultRenderersFactory(context) {
            // Passthrough off: the sink is told the HDMI chain takes plain PCM only, so AC3, E-AC3 and DTS
            // are decoded here (by FFmpeg when the platform can't) instead of sent to the receiver.
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                if (e.passthrough) super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                else DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
            // FFmpeg (the LGPL build, PLAN §5.3) only after the platform: AC3/E-AC3/DTS pass through to a
            // receiver when the HDMI chain supports it, and FFmpeg decodes them only when it doesn't (PLAN §5.6).
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(e.decoder == DecoderMode.HARDWARE_FALLBACK)
            .setMediaCodecSelector(codecSelector(e.decoder))
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setTrackSelector(selector)
            // Live starts quickly for fast zapping (PLAN §5.6 → Zapping fast); the sizes are Settings → Playback.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(e.minMs, e.maxMs, e.startMs, e.rebufferMs)
                    .setTargetBufferBytes(e.targetBytes)
                    .build(),
            )
            .build()
            .apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            // No pause on "audio becoming noisy": a TV has no headphones to pull, and the Shield sends it
            // when auto frame rate switches the display mode (HDMI audio replugs), which froze playback.
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
    }

    /**
     * Settings → Playback → Video decoder. Only video decoders are filtered: audio always has the
     * platform's and FFmpeg's. When the choice leaves none for a format, all are kept, so it still plays.
     */
    private fun codecSelector(mode: DecoderMode) = MediaCodecSelector { mime, secure, tunneling ->
        val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        if (!MimeTypes.isVideo(mime)) return@MediaCodecSelector all
        val chosen = when (mode) {
            DecoderMode.HARDWARE -> all.filter { it.hardwareAccelerated }
            DecoderMode.HARDWARE_FALLBACK -> all
            DecoderMode.SOFTWARE -> all.filter { it.softwareOnly }
        }
        chosen.ifEmpty { all }
    }

    override fun configure(settings: PlayerSettings) {
        val changed = settings != this.settings
        this.settings = settings
        subtitleStyle = Style(settings)
        if (changed) applyTrackPreferences(request)
    }

    /**
     * Languages and subtitles (PLAN §4.3.15 → 5) for [r]: the channel's remembered choices first, then
     * the settings. Also clears a track picked by hand on the previous stream.
     */
    private fun applyTrackPreferences(r: PlayRequest?) {
        val s = settings
        val audio = (listOfNotNull(r?.audioLanguage) + s.audioLanguages).distinct()
        val remembered = r?.subtitle
        val p = trackSelector.buildUponParameters()
            .clearOverrides()
            .setPreferredAudioLanguages(*audio.toTypedArray())
            .setTunnelingEnabled(s.tunneling)
        when {
            remembered == "off" || (remembered == null && s.subtitles == SubtitleMode.OFF) ->
                p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            remembered != null ->
                p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(remembered)
                    .setSelectUndeterminedTextLanguage(true).setIgnoredTextSelectionFlags(0)
            // Always: the subtitle language, else captions (which carry no language), else any.
            s.subtitles == SubtitleMode.ALWAYS ->
                p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(s.subtitleLanguage ?: java.util.Locale.getDefault().language)
                    .setSelectUndeterminedTextLanguage(true).setIgnoredTextSelectionFlags(0)
            // Forced only: tracks flagged forced, never ones merely flagged default.
            else ->
                p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(s.subtitleLanguage)
                    .setSelectUndeterminedTextLanguage(false).setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        }
        trackSelector.setParameters(p)
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
            log("Zap timing: ${stages.toString().trimEnd(',', ' ')}")
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
            if (isNetwork) stalls.bytesIn.addAndGet(bytesTransferred.toLong())
            if (!bytesSeen) { bytesSeen = true; mark("first bytes") }
        }
        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
    }

    private val stalls = StallLog()

    /**
     * Stalls for Send logs: buffering after playback started, how long it lasted, what the stream got
     * from the network before and during it, and the Wi-Fi signal. That tells a weak connection (the
     * download falls short of the stream's rate) from a provider that stops sending (nothing at all, or
     * the stream ends). Added for a Chromecast that buffered live TV on a home's Wi-Fi (2026-10-17).
     */
    private inner class StallLog {
        /** Network bytes read by the player, counted on the loader thread. */
        val bytesIn = java.util.concurrent.atomic.AtomicLong()
        /** When the viewer last seeked: buffering just after isn't a stall. */
        var seekedAt = 0L
        private var label: String? = null
        private var live = true
        private var playingSince = 0L
        private var playedMs = 0L
        private var bytesAtStart = 0L
        private var stallStartedAt = 0L
        private var bytesAtStall = 0L
        private var count = 0
        private var stalledMs = 0L
        /** (time, bytes) every [SAMPLE_MS], for the download rate before a stall. */
        private val samples = ArrayDeque<Pair<Long, Long>>()
        private val sampler = object : Runnable {
            override fun run() {
                samples.addLast(now() to bytesIn.get())
                while (samples.size > SAMPLES) samples.removeFirst()
                main.postDelayed(this, SAMPLE_MS)
            }
        }

        private fun now() = android.os.SystemClock.elapsedRealtime()

        fun begin(request: PlayRequest) {
            end()
            label = request.label
            live = request.isLive
            bytesAtStart = bytesIn.get()
            sampler.run()
        }

        /** The summary of the play that's ending, if it played at all. */
        fun end() {
            main.removeCallbacks(sampler)
            samples.clear()
            val t = now()
            if (stallStartedAt != 0L) stalledMs += t - stallStartedAt
            if (playingSince != 0L) playedMs += t - playingSince
            // Not for channels zapped through: a minute's play, or a stall.
            if (label != null && (playedMs >= 60_000 || count > 0)) {
                log("Playback: ${label} played ${seconds(playedMs)}, $count stall${if (count == 1) "" else "s"}" +
                    "${if (count > 0) " (${seconds(stalledMs)} in all)" else ""}, average download ${mbps(bytesIn.get() - bytesAtStart, playedMs + stalledMs)} (${network()})")
            }
            label = null
            playingSince = 0L; playedMs = 0L; stallStartedAt = 0L; count = 0; stalledMs = 0L
        }

        fun onState(state: Int) {
            val t = now()
            when (state) {
                Player.STATE_READY -> {
                    if (stallStartedAt != 0L) {
                        val ms = t - stallStartedAt
                        stalledMs += ms
                        log("Stall: resumed after ${seconds(ms)}, download during it ${mbps(bytesIn.get() - bytesAtStall, ms)}")
                        stallStartedAt = 0L
                    }
                    if (playingSince == 0L) playingSince = t
                }
                Player.STATE_BUFFERING -> {
                    if (playingSince == 0L || stallStartedAt != 0L) return
                    playedMs += t - playingSince
                    playingSince = 0L
                    if (t - seekedAt < SEEK_GRACE_MS) return
                    count++
                    stallStartedAt = t
                    bytesAtStall = bytesIn.get()
                    log("Stall: buffering ${seconds(playedMs)} into ${label}, download ${rate(10_000)} over 10 s, ${rate(60_000)} over 60 s (${network()})")
                }
                Player.STATE_ENDED -> if (live) {
                    if (playingSince != 0L) { playedMs += t - playingSince; playingSince = 0L }
                    log("Stall: the provider ended the stream after ${seconds(playedMs)}, reconnecting (${network()})")
                }
            }
        }

        /** The download rate over about the last [windowMs]. */
        private fun rate(windowMs: Long): String {
            val t = now()
            val from = samples.firstOrNull { t - it.first <= windowMs } ?: return "unknown"
            return mbps(bytesIn.get() - from.second, t - from.first)
        }

        private fun mbps(bytes: Long, ms: Long) = if (ms <= 0) "unknown" else "%.1f Mbps".format(bytes * 8.0 / ms / 1000)
        private fun seconds(ms: Long) = if (ms < 60_000) "%.1f s".format(ms / 1000.0) else "${ms / 60_000} min ${ms % 60_000 / 1000} s"

        /** The connection, and on Wi-Fi its band, signal and link speed. */
        fun network(): String = runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "no network"
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> {
                    @Suppress("DEPRECATION")
                    val info = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo
                    val band = when (info.frequency) { in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> "${info.frequency} MHz" }
                    val signal = when { info.rssi >= -60 -> "strong"; info.rssi >= -70 -> "fair"; else -> "weak" }
                    "Wi-Fi $band, ${info.rssi} dBm ($signal), link ${info.linkSpeed} Mbps"
                }
                else -> "other network"
            }
        }.getOrElse { "network unknown" }
    }

    override fun play(request: PlayRequest) {
        retriesLeft = settings.retries
        start(request)
    }

    private fun start(request: PlayRequest) {
        stalls.begin(request)
        this.request = request
        // Another decoder, passthrough or buffer than this ExoPlayer has: build a new one (PLAN §4.3.15 → 5).
        val wanted = engineFor(request.isLive)
        if (wanted != engine) {
            log("Player: rebuilding for ${if (request.isLive) "live" else "on-demand"} (${wanted.decoder}, passthrough ${wanted.passthrough}, buffer ${wanted.minMs / 1000}-${wanted.maxMs / 1000} s)")
            exo.release()
            engine = wanted
            exo = build(wanted)
            viewGeneration++
        }
        applyTrackPreferences(request)
        playStartedAt = android.os.SystemClock.elapsedRealtime()
        stages.clear()
        fpsMeter = FrameRateMeter()
        measuredFps = null
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(settings.timeoutSeconds * 1000)
            .setReadTimeoutMs(settings.timeoutSeconds * 1000)
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
        stalls.end()
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
        stalls.seekedAt = android.os.SystemClock.elapsedRealtime()
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
                    applyStyle(view, subtitleStyle)
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

    /** Settings → Playback → Subtitle style: the size, color and background, over the stream's own styling. */
    private fun applyStyle(view: PlayerView, style: Style) {
        val sub = view.subtitleView ?: return
        sub.setApplyEmbeddedStyles(false)
        sub.setApplyEmbeddedFontSizes(false)
        sub.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.size)
        val (background, edge) = when (style.background) {
            SubtitleBackground.OUTLINE -> android.graphics.Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleBackground.SHADED -> 0xAA000000.toInt() to CaptionStyleCompat.EDGE_TYPE_NONE
            SubtitleBackground.BLACK -> android.graphics.Color.BLACK to CaptionStyleCompat.EDGE_TYPE_NONE
        }
        sub.setStyle(CaptionStyleCompat(style.color, background, android.graphics.Color.TRANSPARENT, edge, android.graphics.Color.BLACK, null))
    }

    private inner class Listener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val status = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.Status.BUFFERING
                Player.STATE_READY -> PlaybackState.Status.PLAYING
                Player.STATE_ENDED -> PlaybackState.Status.BUFFERING // Live streams "end" when the provider drops them.
                else -> return
            }
            stalls.onState(playbackState)
            if (playbackState == Player.STATE_ENDED) {
                // On-demand ends for real (autoplay, PLAN §4.3.9); live streams "end" when the provider drops them.
                if (request?.isLive == false) _state.update { it.copy(status = PlaybackState.Status.PLAYING, ended = true) } else retry()
                return
            }
            _state.update { it.copy(status = status, error = null) }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Retries (one by default, Settings → Playback), then the message (PLAN §4.3.3 → Stream failure).
            log("Player error: ${error.errorCodeName}${if (retriesLeft > 0) ", retrying" else ""} (${stalls.network()})")
            if (retriesLeft > 0) {
                retriesLeft--
                val failed = request
                main.postDelayed({ if (request === failed) failed?.let(::start) }, 1000)
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
            log("Tracks: " + tracks.groups.joinToString("; ") { group ->
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

        /** The Large on-demand buffer's cap in bytes: about two minutes of 1080p within the Shield's 192 MB heap. */
        private const val LARGE_VOD_BYTES = 64 * 1024 * 1024

        /** [StallLog]'s download samples: every 5 s, the last minute's worth. */
        private const val SAMPLE_MS = 5_000L
        private const val SAMPLES = 13
        private const val SEEK_GRACE_MS = 2_000L

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
