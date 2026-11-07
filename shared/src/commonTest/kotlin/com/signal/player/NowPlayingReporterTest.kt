package com.signal.player

import com.signal.app.NowPlayingReporter
import com.signal.nas.NowPlaying
import com.signal.nas.PlayKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingReporterTest {
    private fun play(stream: String, status: PlaybackState.Status = PlaybackState.Status.PLAYING, paused: Boolean = false) = PlaybackState(
        status, PlayRequest("http://p/$stream", label = stream, usage = NowPlaying("src", "main", PlayKind.LIVE, stream, "Channel $stream")), paused = paused,
    )

    @Test fun reportsWhereZappingStopsThenBeatsAndSaysWhenItStops() = runTest {
        val state = MutableStateFlow(PlaybackState())
        val sent = mutableListOf<NowPlaying>()
        var clock = 1_000L
        NowPlayingReporter(state, backgroundScope, send = { sent += it }, now = { clock })
        runCurrent()
        assertEquals(emptyList(), sent, "nothing to say before anything plays")

        // Zapping: only the channel it stays on is sent.
        for (s in listOf("1", "2", "3")) {
            state.value = play(s, PlaybackState.Status.BUFFERING)
            advanceTimeBy(500)
        }
        state.value = play("3")
        advanceTimeBy(NowPlayingReporter.SETTLE_MS + 1)
        assertEquals(listOf("3"), sent.map { it.streamId })
        assertEquals(1_000L, sent.single().since)

        // A heartbeat each minute, the start unchanged; pausing is the same play.
        clock = 90_000
        advanceTimeBy(NowPlayingReporter.HEARTBEAT_MS)
        state.value = play("3", paused = true)
        advanceTimeBy(NowPlayingReporter.SETTLE_MS + 1)
        assertEquals(listOf(false, false, true), sent.map { it.paused })
        assertEquals(setOf(1_000L), sent.map { it.since }.toSet())

        state.value = PlaybackState()
        advanceTimeBy(NowPlayingReporter.SETTLE_MS + 1)
        assertEquals(NowPlaying(), sent.last())
        val count = sent.size
        advanceTimeBy(5 * NowPlayingReporter.HEARTBEAT_MS)
        assertEquals(count, sent.size, "no heartbeats once stopped")
    }

    @Test fun aRecordingFromTheCompanionIsntReported() = runTest {
        val state = MutableStateFlow(PlaybackState())
        val sent = mutableListOf<NowPlaying>()
        NowPlayingReporter(state, backgroundScope, send = { sent += it })
        state.value = PlaybackState(PlaybackState.Status.PLAYING, PlayRequest("http://nas/rec.m3u8", isLive = false, label = "Futurama"))
        advanceTimeBy(NowPlayingReporter.SETTLE_MS + 1)
        state.value = PlaybackState()
        advanceTimeBy(NowPlayingReporter.SETTLE_MS + 1)
        assertEquals(emptyList(), sent)
    }
}
