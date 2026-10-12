package com.signal.dev

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.signal.player.AspectMode
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
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer

/**
 * The dev harness's real player (PLAN §7.1): VLC through vlcj, drawing decoded frames straight
 * into Compose. Needs VLC installed. vlcj is GPLv3, which is fine here because the harness is never
 * distributed; it must never be used by androidTvApp or server (PLAN §1).
 */
class VlcjPlayer : VideoPlayer {
    private val factory = MediaPlayerFactory("--no-video-title-show", "--quiet")
    private val mp = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var frame by mutableStateOf<ImageBitmap?>(null)
    private var bitmap: Bitmap? = null
    private var pixels = ByteArray(0)
    private var width = 0
    private var height = 0

    init {
        val formatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                width = sourceWidth
                height = sourceHeight
                pixels = ByteArray(sourceWidth * sourceHeight * 4)
                bitmap = Bitmap().apply { allocPixels(ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
                return RV32BufferFormat(sourceWidth, sourceHeight)
            }

            override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
        }
        val renderCallback = object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
                val bmp = bitmap ?: return
                nativeBuffers[0].rewind()
                nativeBuffers[0].get(pixels, 0, minOf(pixels.size, nativeBuffers[0].remaining()))
                bmp.installPixels(pixels)
                frame = bmp.asComposeImageBitmap()
            }
            override fun unlock(mediaPlayer: MediaPlayer) = Unit
        }
        mp.videoSurface().set(factory.videoSurfaces().newVideoSurface(formatCallback, renderCallback, true))
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                _state.update { it.copy(status = PlaybackState.Status.PLAYING, error = null) }
                refreshTracks()
            }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                if (newCache < 100f) _state.update { it.copy(status = PlaybackState.Status.BUFFERING) }
                else _state.update { it.copy(status = PlaybackState.Status.PLAYING) }
            }
            override fun error(mediaPlayer: MediaPlayer) {
                _state.update { it.copy(status = PlaybackState.Status.ERROR, error = PlaybackError(PlaybackError.Kind.OFFLINE)) }
            }
            override fun finished(mediaPlayer: MediaPlayer) {
                // Live streams "finish" when the provider drops them; restart like the Shield does.
                retry()
            }
            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) = refreshTracks()
        })
    }

    override fun play(request: PlayRequest) {
        val options = buildList {
            add(":network-caching=1500")
            request.headers["User-Agent"]?.let { add(":http-user-agent=$it") }
            request.headers["Referer"]?.let { add(":http-referrer=$it") }
        }
        _state.value = PlaybackState(status = PlaybackState.Status.BUFFERING, request = request)
        mp.submit { mp.media().play(request.url, *options.toTypedArray()) }
    }

    override fun stop() {
        mp.submit { mp.controls().stop() }
        frame = null
        _state.value = PlaybackState()
    }

    override fun retry() {
        _state.value.request?.let(::play)
    }

    override fun selectAudioTrack(trackId: String) {
        mp.submit { mp.audio().setTrack(trackId.toInt()) }
        _state.update { it.copy(selectedAudioTrackId = trackId) }
    }

    override fun selectSubtitleTrack(trackId: String?) {
        mp.submit { mp.subpictures().setTrack(trackId?.toInt() ?: -1) }
        _state.update { it.copy(selectedSubtitleTrackId = trackId) }
    }

    override fun setVolume(volume: Float) {
        mp.submit { mp.audio().setVolume((volume * 100).toInt()) }
    }

    override fun release() = stop()

    private fun refreshTracks() {
        val audio = mp.audio().trackDescriptions().filter { it.id() >= 0 }.map { Track(it.id().toString(), it.description(), null, null) }
        val subs = mp.subpictures().trackDescriptions().filter { it.id() >= 0 }.map { Track(it.id().toString(), it.description(), null, null) }
        val videoTrack = mp.media().info()?.videoTracks()?.firstOrNull()
        val audioTrack = mp.media().info()?.audioTracks()?.firstOrNull()
        _state.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = subs,
                selectedAudioTrackId = mp.audio().track().takeIf { t -> t >= 0 }?.toString(),
                selectedSubtitleTrackId = mp.subpictures().track().takeIf { t -> t >= 0 }?.toString(),
                video = videoTrack?.let { v ->
                    VideoInfo(
                        width = v.width(), height = v.height(),
                        frameRate = if (v.frameRateBase() > 0) v.frameRate().toFloat() / v.frameRateBase() else null,
                        videoCodec = v.codecName(), audioCodec = audioTrack?.codecName(), audioChannels = audioTrack?.channels(),
                    )
                } ?: it.video,
            )
        }
    }

    @Composable
    override fun Surface(modifier: Modifier, aspect: AspectMode) {
        val image = frame ?: return
        val ratio = aspect.forcedRatio
        Box(modifier, contentAlignment = Alignment.Center) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = when (aspect) {
                    AspectMode.FIT -> ContentScale.Fit
                    AspectMode.FILL -> ContentScale.Crop
                    else -> ContentScale.FillBounds
                },
                modifier = if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
            )
        }
    }
}
