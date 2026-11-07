package com.signal.app

import com.signal.nas.NowPlaying
import com.signal.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Tells the companion which source and login this device plays from (source-activity.md), for the
 * web admin's dashboard. Sent once a play settles, so zapping through channels sends only where it
 * stops, then every [heartbeatMs] while it plays. Stopping sends an empty [NowPlaying]; the companion
 * also forgets a device that goes quiet, which covers a crash or a lost network.
 */
class NowPlayingReporter(
    state: StateFlow<PlaybackState>,
    scope: CoroutineScope,
    /** Best effort: a failure is never retried, the next heartbeat carries on. */
    private val send: suspend (NowPlaying) -> Unit,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    settleMs: Long = SETTLE_MS,
    private val heartbeatMs: Long = HEARTBEAT_MS,
) {
    private var sent = false
    /** The play [since] belongs to, without the fields that change while it plays. */
    private var playKey: NowPlaying? = null
    private var since = 0L

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            state.map { s -> s.request?.usage?.takeIf { s.status == PlaybackState.Status.BUFFERING || s.status == PlaybackState.Status.PLAYING }?.copy(paused = s.paused) }
                .distinctUntilChanged()
                .debounce(settleMs)
                .collectLatest { usage -> report(usage) }
        }
    }

    private suspend fun report(usage: NowPlaying?) {
        if (usage == null) {
            playKey = null
            if (sent) {
                sent = false
                runCatching { send(NowPlaying()) }
            }
            return
        }
        val key = usage.copy(paused = false, since = 0)
        if (key != playKey) {
            playKey = key
            since = now()
        }
        while (true) {
            runCatching { send(usage.copy(since = since)) }
            sent = true
            delay(heartbeatMs)
        }
    }

    companion object {
        const val SETTLE_MS = 2_000L
        const val HEARTBEAT_MS = 60_000L
    }
}
