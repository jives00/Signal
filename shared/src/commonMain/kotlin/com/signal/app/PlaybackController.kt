package com.signal.app

import com.signal.data.ChannelRepository
import com.signal.data.Log
import com.signal.data.Settings
import com.signal.data.SourceRepository
import com.signal.model.Channel
import com.signal.model.ChannelGroup
import com.signal.model.CredentialMasking
import com.signal.model.StreamUrls
import com.signal.player.AspectMode
import com.signal.player.PlayRequest
import com.signal.player.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What's playing, and the rules around it (PLAN §4.3.1, §4.3.3): one player instance shared by
 * the Home hero, the Live TV preview and full screen; the last played channel; the current group
 * that zapping works within; last-channel recall.
 */
class PlaybackController(
    private val player: VideoPlayer,
    private val channels: ChannelRepository,
    private val sources: SourceRepository,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val log: Log,
    /** User channels with more than one playable stream, for failover (AppController.linkedStreams). */
    private val linkedStreams: () -> Map<String, List<ChannelRepository.LinkedStream>> = { emptyMap() },
) {
    private val _current = MutableStateFlow<Channel?>(null)
    /** The channel loaded in the player, in the preview or full screen. */
    val current: StateFlow<Channel?> = _current.asStateFlow()

    private val _aspect = MutableStateFlow(AspectMode.FIT)
    val aspect: StateFlow<AspectMode> = _aspect.asStateFlow()

    /** The group full-screen playback zaps within (PLAN §2.3 "Current group"). */
    var currentGroup: ChannelGroup = ChannelGroup.Favorites
        private set

    /** For last-channel recall (Play/Pause in full screen). */
    private var previous: Channel? = null
    val previousChannel: Channel? get() = previous

    val playerState get() = player.state

    /** Streams tried since the user tuned the current channel: failover tries each linked one once. */
    private val tried = HashSet<String>()
    /** The URL [start] last sent to the player, to tell its errors from a stale request's. */
    private var startedUrl: String? = null
    /**
     * The [start] in flight: it reads the database before the player gets the request. A newer tune
     * replaces it, so a slow older one can't land last, and one already starting isn't started twice.
     */
    private var starting: Job? = null

    init {
        // Automatic failover (PLAN §4.5, milestone 5): when a stream fails for good (the player has
        // already retried it once), the channel moves to its next linked stream on an enabled source.
        // The preferred stream stays as it is, so the next tune starts there again. Only when every
        // link has failed does the error show.
        scope.launch {
            player.state.collect { s ->
                if (s.status != com.signal.player.PlaybackState.Status.ERROR || s.request?.isLive != true) return@collect
                val playing = _current.value ?: return@collect
                if (s.request.url != startedUrl) return@collect
                failover(playing)
            }
        }
        // Disabling or removing a source stops its channel wherever it's playing; otherwise a page
        // re-entering its preview would tune it again.
        scope.launch {
            sources.sources().collect { all ->
                val enabled = all.filter { it.enabled }.map { it.id }.toSet()
                if (previous?.stream?.sourceId !in enabled) previous = null
                val playing = _current.value ?: return@collect
                if (playing.stream.sourceId !in enabled) {
                    log.info("Source of ${playing.displayName} is off: stopping")
                    _current.value = null
                    player.stop()
                }
            }
        }
    }

    /** Tunes a channel in whichever surface is showing. Full screen also makes it the last played channel. */
    fun tune(channel: Channel, fullScreen: Boolean, group: ChannelGroup? = null) {
        if (group != null) currentGroup = group
        val was = _current.value
        if (was?.key != channel.key) {
            if (was != null) previous = was
            _current.value = channel
            tried.clear()
            launchStart(channel)
        } else if (player.state.value.status == com.signal.player.PlaybackState.Status.IDLE && starting?.isActive != true) {
            // The same channel, perhaps after a failover: back to the stream it was tuned with.
            _current.value = channel
            tried.clear()
            launchStart(channel)
        }
        if (fullScreen) scope.launch { rememberLastPlayed(channel) }
    }

    /** The Stream picker chose another stream for the playing channel: play it now. */
    fun switchStream(channel: Channel) {
        _current.value = channel
        tried.clear()
        launchStart(channel)
    }

    private fun launchStart(channel: Channel) {
        starting?.cancel()
        starting = scope.launch { start(channel) }
    }

    private suspend fun failover(playing: Channel) {
        val uc = playing.userChannelId ?: return
        tried += playing.stream.id
        val next = linkedStreams()[uc].orEmpty().firstOrNull { it.streamId !in tried } ?: return
        tried += next.streamId
        val alternative = channels.channelByStream(next.streamId) ?: return
        // A zap or a new tune while the lookup ran wins.
        if (_current.value?.key != playing.key) return
        log.info("Failover: ${playing.displayName} failed, trying ${next.sourceName} · ${next.name}")
        _current.value = alternative
        start(alternative)
    }

    private suspend fun start(channel: Channel) {
        val source = sources.byId(channel.stream.sourceId) ?: return
        val url = StreamUrls.live(source, channel.stream)
        _aspect.value = channels.remembered(channel)?.aspect?.let { runCatching { AspectMode.valueOf(it) }.getOrNull() } ?: AspectMode.FIT
        startedUrl = url
        log.info("Tune ${channel.displayName} (${CredentialMasking.forLog(url, source)})")
        player.play(PlayRequest(url = url, headers = StreamUrls.headers(source, channel.stream), isLive = true, label = channel.displayName))
    }

    /** Up/Down in full screen: the previous or next channel in the current group, wrapping (PLAN §4.3.3). */
    suspend fun zap(step: Int): Channel? {
        val list = channels.channelsNow(currentGroup)
        if (list.isEmpty()) return null
        val i = list.indexOfFirst { it.key == _current.value?.key || it.stream.id == _current.value?.stream?.id }
        val next = list[((if (i < 0) 0 else i + step) % list.size + list.size) % list.size]
        tune(next, fullScreen = true)
        return next
    }

    /** Last-channel recall: swaps to the previous channel; pressing again swaps back. */
    fun recall(): Channel? {
        val target = previous ?: return null
        tune(target, fullScreen = true)
        return target
    }

    fun retry() = player.retry()

    fun setAspect(channel: Channel, mode: AspectMode) {
        _aspect.value = mode
        scope.launch { channels.rememberAspect(channel, mode.name) }
    }

    fun selectAudio(channel: Channel, trackId: String) {
        player.selectAudioTrack(trackId)
        val lang = player.state.value.audioTracks.firstOrNull { it.id == trackId }?.language
        scope.launch { channels.rememberAudio(channel, lang) }
    }

    fun selectSubtitle(channel: Channel, trackId: String?) {
        player.selectSubtitleTrack(trackId)
        val lang = trackId?.let { id -> player.state.value.subtitleTracks.firstOrNull { it.id == id }?.language ?: id }
        scope.launch { channels.rememberSubtitle(channel, lang ?: "off") }
    }

    /** Leaving a page with a player stops it (PLAN §4.3.1 → "Leaving Home stops the preview"). */
    fun stop() {
        log.info("Playback stop")
        starting?.cancel()
        player.stop()
    }

    /** Sleep or Home button: frees the decoder and the provider connection (PLAN §4.2). */
    fun release() {
        log.info("Playback release (background)")
        player.release()
    }

    /** Re-entering a page with a preview after it was stopped: plays the same channel again. */
    fun resumeIfStopped() {
        if (player.state.value.status == com.signal.player.PlaybackState.Status.IDLE) resume()
    }

    /** Coming back: re-tunes the same channel (PLAN §4.2). */
    fun resume() {
        _current.value?.let { launchStart(it) }
    }

    /** "Last played" = last channel played full screen; it creates a user channel (PLAN §4.5). */
    private suspend fun rememberLastPlayed(channel: Channel) {
        val id = channels.ensureUserChannel(channel)
        settings.put(Settings.Key.LAST_PLAYED_CHANNEL, id)
    }

    /**
     * The channel the Home hero and the Live TV preview start on: the last played channel, else the
     * first favorite, else the first channel in the first visible group (PLAN §4.3.1 → Hero).
     */
    suspend fun startingChannel(): Channel? {
        settings.raw(Settings.Key.LAST_PLAYED_CHANNEL)?.let { key -> channels.channelByKey(key)?.let { return it } }
        channels.channelsNow(ChannelGroup.Favorites).firstOrNull()?.let { return it }
        val groups = channels.groupsNow()
        for (g in groups) {
            if (g.group is ChannelGroup.Provider) channels.channelsNow(g.group).firstOrNull()?.let { return it }
        }
        return null
    }
}
