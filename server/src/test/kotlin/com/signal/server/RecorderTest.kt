package com.signal.server

import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.parse.XtreamParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A little TS: PAT, PMT on 0x1000 (H.264 on 0x100, AC3 on 0x101) and video packets with a PCR and a keyframe flag. */
internal object Ts {
    const val VIDEO = 0x100
    const val PMT = 0x1000

    private fun table(pid: Int, vararg body: Int): ByteArray {
        val p = ByteArray(188) { 0xff.toByte() }
        p[0] = 0x47
        p[1] = (0x40 or (pid shr 8)).toByte()
        p[2] = pid.toByte()
        p[3] = 0x10
        body.forEachIndexed { i, v -> p[4 + i] = v.toByte() }
        return p
    }

    val pat = table(0, 0, 0x00, 0xb0, 13, 0, 1, 0xc1, 0, 0, 0, 1, 0xf0, 0x00, 0, 0, 0, 0)
    val pmt = table(PMT, 0, 0x02, 0xb0, 23, 0, 1, 0xc1, 0, 0, 0xe1, 0x00, 0xf0, 0, 0x1b, 0xe1, 0x00, 0xf0, 0, 0x81, 0xe1, 0x01, 0xf0, 0, 0, 0, 0, 0)

    /** A picture's first packet at [ms], flagged as a keyframe when [keyframe]. */
    fun video(ms: Long, keyframe: Boolean = false): ByteArray {
        val p = ByteArray(188) { 0xff.toByte() }
        val base = (ms * 90) and ((1L shl 33) - 1)
        p[0] = 0x47
        p[1] = (0x40 or (VIDEO shr 8)).toByte()
        p[2] = VIDEO.toByte()
        p[3] = 0x30
        p[4] = 7
        p[5] = ((if (keyframe) 0x40 else 0) or 0x10).toByte()
        p[6] = (base shr 25).toByte()
        p[7] = (base shr 17).toByte()
        p[8] = (base shr 9).toByte()
        p[9] = (base shr 1).toByte()
        p[10] = ((base shl 7) and 0x80).toByte()
        return p
    }

    /** [seconds] of stream: a picture every 500 ms, a keyframe every 2 s. */
    fun stream(seconds: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pat)
        out.write(pmt)
        for (i in 0 until seconds * 2) out.write(video(i * 500L, keyframe = i % 4 == 0))
        return out.toByteArray()
    }
}

class TsSegmenterTest {
    private class Sink : TsSegmenter.Sink {
        val segments = mutableListOf<Pair<ByteArray, Long>>()
        private var current = ByteArrayOutputStream()
        override fun begin() { current = ByteArrayOutputStream() }
        override fun write(data: ByteArray, offset: Int, length: Int) { current.write(data, offset, length) }
        override fun finish(durationMs: Long) { segments += current.toByteArray() to durationMs }
    }

    private fun Sink.pids(segment: Int): List<Int> {
        val b = segments[segment].first
        return (0 until b.size / 188).map { ((b[it * 188 + 1].toInt() and 0x1f) shl 8) or (b[it * 188 + 2].toInt() and 0xff) }
    }

    @Test fun cutsAtTheFirstKeyframePastTheTargetWithTheTablesInFront() {
        val sink = Sink()
        TsSegmenter(sink).apply { feed(Ts.stream(31)); close() }
        assertEquals(listOf(10_000L, 10_000L, 10_000L, 500L), sink.segments.map { it.second })
        sink.segments.indices.forEach { assertEquals(listOf(0, Ts.PMT), sink.pids(it).take(2), "segment $it") }
        assertEquals(62, sink.segments.indices.sumOf { i -> sink.pids(i).count { it == Ts.VIDEO } }, "every picture, once")
    }

    @Test fun aChannelThatNeverFlagsAKeyframeCutsAtTheMaximum() {
        var clock = 0L
        val sink = Sink()
        val s = TsSegmenter(sink, clock = { clock })
        // A packet is only read once the next arrives to confirm it, so the first picture holds the PMT back.
        s.feed(Ts.pat + Ts.pmt + Ts.video(0))
        clock = 4_000
        for (i in 1 until 80) s.feed(Ts.video(i * 500L))
        s.close()
        assertEquals(15_000L, sink.segments.first().second)
        assertEquals(3, sink.segments.size)
    }

    @Test fun waitsForAKeyframeToStartAndDropsWhatComesBefore() {
        val sink = Sink()
        val s = TsSegmenter(sink, clock = { 0 })
        s.feed(Ts.video(0))
        s.feed(Ts.pat + Ts.pmt)
        s.feed(Ts.video(500))
        s.feed(Ts.video(1_000, keyframe = true))
        s.feed(Ts.video(1_500))
        s.close()
        assertEquals(listOf(0, Ts.PMT, Ts.VIDEO, Ts.VIDEO), sink.pids(0))
        assertEquals(500L, sink.segments.single().second)
    }

    @Test fun findsTheStreamAfterJoiningMidPacketAndAfterLosingSync() {
        val sink = Sink()
        val stream = Ts.stream(4)
        val bad = ByteArray(77) { 0x47 } + stream.copyOfRange(0, 188 * 4) + ByteArray(50) { 1 } + stream.copyOfRange(188 * 4, stream.size)
        TsSegmenter(sink).apply { feed(bad); close() }
        // The tables, then 7 of the 8 pictures: the one before the junk can't be confirmed, so it's dropped.
        assertEquals(listOf(0, Ts.PMT) + List(7) { Ts.VIDEO }, sink.pids(0))
    }

    @Test fun comesOutTheSameHoweverTheNetworkChopsIt() {
        val whole = Sink()
        val stream = Ts.stream(25)
        TsSegmenter(whole).apply { feed(stream); close() }
        val chopped = Sink()
        val s = TsSegmenter(chopped)
        var at = 0
        var size = 1
        while (at < stream.size) {
            val n = minOf(size, stream.size - at)
            s.feed(stream, at, n)
            at += n
            size = size % 977 + 13
        }
        s.close()
        assertEquals(whole.segments.size, chopped.segments.size)
        whole.segments.zip(chopped.segments).forEach { (a, b) ->
            assertTrue(a.first.contentEquals(b.first))
            assertEquals(a.second, b.second)
        }
    }

    @Test fun durationsSurviveThePcrWrappingAndFallBackWhenItJumps() {
        val wrap = (1L shl 33) / 90
        val sink = Sink()
        val s = TsSegmenter(sink)
        s.feed(Ts.pat + Ts.pmt)
        s.feed(Ts.video(wrap - 6_000, keyframe = true))
        s.feed(Ts.video(wrap + 5_000, keyframe = true))
        s.close()
        assertEquals(11_000L, sink.segments.first().second)

        var clock = 0L
        val jumped = Sink()
        val t = TsSegmenter(jumped, clock = { clock })
        t.feed(Ts.pat + Ts.pmt)
        t.feed(Ts.video(5_000_000, keyframe = true) + Ts.video(5_000_500))
        clock = 12_000
        t.feed(Ts.video(1_000, keyframe = true))
        t.close()
        assertEquals(12_000L, jumped.segments.first().second)
    }
}

class RecorderTest {
    private val root = Files.createTempDirectory("signal-recordings").toFile()
    private val store = MemoryRecordingStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timing = Recorder.Timing(retryMs = 20, reconnectMs = 20, recheckMs = 1, minFreeBytes = 1, keepMs = 1_000)
    private fun info(active: Int?) = XtreamParser.AccountInfo(true, "Active", 1, active, null)
    private fun login(name: String) = RecordingLogin(name, name, "http://p/$name", emptyMap(), Source(name, name, SourceKind.XTREAM, "http://p", name, "secret-$name"))

    private val asked = CopyOnWriteArrayList<String>()
    private val connected = CopyOnWriteArrayList<String>()
    @Volatile private var answers: (String) -> XtreamParser.AccountInfo? = { info(0) }
    @Volatile private var serve: suspend (String, (ByteArray, Int) -> Unit) -> Unit = { _, send -> feed(send); awaitCancellation() }
    private var logins = listOf(login("own"), login("other"))

    /** 3.5 seconds of stream, by the PCR. */
    private fun feed(send: (ByteArray, Int) -> Unit) = Ts.stream(4).let { send(it, it.size) }

    private fun recorder(dir: File? = root, scope: CoroutineScope = this.scope) = Recorder(
        dir, { store }, { logins },
        { s -> asked += s.name; answers(s.name) },
        { url, _, send -> val name = url.substringAfterLast('/'); connected += name; serve(name, send) },
        scope, timing,
    )

    private fun start(r: Recorder, ms: Long = 500) = r.start("main", "s:x:1", "Show", "Channel", System.currentTimeMillis() + ms)

    private fun finished(id: String): Recording = runBlocking {
        withTimeout(8_000) {
            var r = store.get(id)!!
            while (r.endedAt == null) {
                delay(5)
                r = store.get(id)!!
            }
            r
        }
    }

    private fun Recorder.settle() = runBlocking { withTimeout(2_000) { while (busy()) delay(5) } }

    private fun playlist(r: Recording) = File(root, "${r.profileId}/${r.id}/index.m3u8").readText()

    @Test fun recordsFromItsOwnLoginAndWritesAPlayablePlaylist() {
        val r = finished(start(recorder()).id)
        assertEquals(RecordingState.DONE, r.state)
        assertEquals("own", r.loginName)
        assertEquals(listOf("own"), connected)
        assertTrue(r.bytes > 0)
        assertEquals(3_500, r.durationMs)
        val text = playlist(r)
        assertTrue(text.startsWith("#EXTM3U"))
        assertTrue(text.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        assertTrue(text.contains("#EXTINF:3.500,\nseg-000001.ts"), text)
        assertTrue(text.trim().endsWith("#EXT-X-ENDLIST"))
        assertNotNull(r.expiresAt)
    }

    @Test fun takesAnotherFreeLoginWhenItsOwnIsInUse() {
        answers = { name -> info(if (name == "own") 1 else 0) }
        val r = finished(start(recorder()).id)
        assertEquals("other", r.loginName)
        assertEquals(listOf("other"), connected)
    }

    @Test fun neverConnectsToABusyLogin() {
        answers = { info(1) }
        val r = finished(start(recorder()).id)
        assertEquals(RecordingState.FAILED, r.state)
        assertTrue(connected.isEmpty())
        assertEquals("No login was free.", r.reason)
    }

    @Test fun anUnclearAnswerIsAskedAgainThenCountsAsBusy() {
        answers = { name -> if (name == "own") null else info(0) }
        assertEquals("other", finished(start(recorder()).id).loginName)
        assertEquals(2, asked.count { it == "own" })
        answers = { null }
        connected.clear()
        assertEquals(RecordingState.FAILED, finished(start(recorder()).id).state)
        assertTrue(connected.isEmpty())
    }

    @Test fun aMissingCountIsUnclearToo() {
        answers = { info(null) }
        assertEquals(RecordingState.FAILED, finished(start(recorder()).id).state)
        assertTrue(connected.isEmpty())
    }

    @Test fun twoRecordingsNeverShareALoginEvenBeforeTheProviderCountsIt() {
        val rec = recorder()
        val a = start(rec)
        val b = start(rec)
        assertEquals(setOf("own", "other"), setOf(finished(a.id).loginName, finished(b.id).loginName))
    }

    @Test fun whenATvTakesTheLoginItWaitsAndNeverReconnectsOverIt() {
        answers = { name -> info(if (name == "other" || connected.isNotEmpty()) 1 else 0) }
        serve = { _, send -> feed(send); throw IOException("the provider closed it") }
        val r = finished(start(recorder(), 500).id)
        assertEquals(listOf("own"), connected)
        assertEquals(RecordingState.PARTIAL, r.state)
        assertEquals(1, r.gaps)
        assertTrue(r.gapMs > 0)
    }

    @Test fun whenTheLoginComesFreeItPicksUpWithAGapInThePlaylist() {
        var calls = 0
        serve = { _, send -> feed(send); if (++calls == 1) throw IOException("dropped") else awaitCancellation() }
        val r = finished(start(recorder(), 500).id)
        assertEquals(RecordingState.PARTIAL, r.state)
        assertEquals(listOf("own", "own"), connected)
        assertEquals(1, r.gaps)
        assertTrue(playlist(r).contains("#EXT-X-DISCONTINUITY\n#EXTINF:3.500,\nseg-000002.ts"), playlist(r))
    }

    @Test fun aStreamThatJustEndsCountsAsADrop() {
        var calls = 0
        serve = { _, send -> feed(send); if (++calls > 1) awaitCancellation() }
        assertEquals(RecordingState.PARTIAL, finished(start(recorder(), 500).id).state)
    }

    @Test fun failuresGiveAReasonWithNoUrlOrPassword() {
        serve = { _, _ -> throw StreamRefused(403) }
        val refused = finished(start(recorder()).id)
        assertEquals(RecordingState.FAILED, refused.state)
        assertEquals("The provider refused the stream (403).", refused.reason)
        serve = { name, _ -> throw IOException("Connection to http://p/live/$name/secret-$name/1.ts failed") }
        assertEquals("The stream dropped.", finished(start(recorder()).id).reason)
    }

    @Test fun stopEndsItEarlyAndKeepsWhatItHas() {
        val rec = recorder()
        val got = CompletableDeferred<Unit>()
        serve = { _, send -> feed(send); got.complete(Unit); awaitCancellation() }
        val started = start(rec, 60_000)
        runBlocking { withTimeout(5_000) { got.await() } }
        assertTrue(rec.busy())
        assertTrue(rec.stop(started.id))
        val r = finished(started.id)
        assertEquals(RecordingState.DONE, r.state)
        assertTrue(playlist(r).contains("#EXT-X-ENDLIST"))
        rec.settle()
        assertFalse(rec.stop(started.id))
    }

    @Test fun busyCoversEverythingUnfinishedAndClearsWhenDone() {
        val rec = recorder()
        assertFalse(rec.busy())
        val r = start(rec)
        assertTrue(rec.busy())
        finished(r.id)
        rec.settle()
    }

    @Test fun anUnexpectedFailureStillEndsItSoItCantHoldDeploysBack() {
        root.resolve("main").writeText("a file where the profile's folder should be")
        val rec = recorder()
        val r = finished(start(rec).id)
        assertEquals(RecordingState.FAILED, r.state)
        rec.settle()
        assertTrue(store.unfinished().isEmpty())
    }

    @Test fun aRowStuckPastItsEndDoesntCountAsBusy() {
        val at = System.currentTimeMillis()
        val stuck = Recording("old", "main", "s:x:1", "Show", "Channel", at - 3_600_000, at - 6 * 60_000, RecordingState.RECORDING, updatedAt = at)
        store.save(stuck)
        assertFalse(recorder().busy())
        store.save(stuck.copy(stopMs = at - 60_000))
        assertTrue(recorder().busy())
    }

    @Test fun aShutdownLeavesItUnfinishedAndARestartResumesWithAGap() {
        val rec = recorder(scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val got = CompletableDeferred<Unit>()
        serve = { _, send -> feed(send); got.complete(Unit); awaitCancellation() }
        val started = start(rec, 2_500)
        runBlocking { withTimeout(2_000) { got.await() } }
        rec.shutdown()
        val saved = store.get(started.id)!!
        assertEquals(RecordingState.RECORDING, saved.state)
        assertNull(saved.endedAt)
        val text = File(root, "main/${started.id}/index.m3u8").readText()
        assertTrue(text.contains("seg-000001.ts"))
        assertFalse(text.contains("#EXT-X-ENDLIST"))

        val again = recorder()
        assertTrue(again.busy(), "before recovery, the row alone holds a deploy back")
        again.recover()
        val r = finished(started.id)
        assertEquals(RecordingState.PARTIAL, r.state)
        assertEquals(1, r.gaps)
        assertEquals(2, connected.size)
        assertTrue(playlist(r).contains("#EXT-X-DISCONTINUITY\n#EXTINF:3.500,\nseg-000002.ts"), playlist(r))
    }

    @Test fun aRestartAfterTheEndClosesItAsItStands() {
        val at = System.currentTimeMillis()
        store.save(Recording("old", "main", "s:x:1", "Show", "Channel", at - 60_000, at - 1_000, RecordingState.RECORDING, updatedAt = at - 30_000, bytes = 100, durationMs = 30_000))
        val dir = File(root, "main/old")
        RecordingFiles(dir)
        recorder().recover()
        val r = finished("old")
        assertEquals(RecordingState.PARTIAL, r.state)
        assertTrue(r.gapMs >= 29_000, "${r.gapMs}")
        assertTrue(File(dir, "index.m3u8").readText().contains("#EXT-X-ENDLIST"))
        assertTrue(connected.isEmpty())
    }

    @Test fun refusesWithoutTheMountAndNeverCreatesIt() {
        val missing = File(root, "not-mounted")
        val rec = recorder(missing)
        assertEquals("The recordings folder isn't mounted.", rec.problem())
        assertFalse(runCatching { start(rec) }.isSuccess)
        assertFalse(missing.exists())
        assertEquals("Recording isn't set up: RECORDINGS_DIR isn't set.", recorder(null).problem())
    }

    @Test fun deleteRemovesTheFilesButNotWhileRecording() {
        val rec = recorder()
        val got = CompletableDeferred<Unit>()
        serve = { _, send -> feed(send); got.complete(Unit); awaitCancellation() }
        val started = start(rec, 60_000)
        runBlocking { withTimeout(5_000) { got.await() } }
        assertEquals(false, rec.delete(started.id))
        rec.stop(started.id)
        finished(started.id)
        rec.settle()
        assertEquals(true, rec.delete(started.id))
        assertNull(store.get(started.id))
        assertFalse(File(root, "main/${started.id}").exists())
        assertNull(rec.delete("nothing"))
    }

    @Test fun servesOnlyItsOwnFiles() {
        val rec = recorder()
        val r = finished(start(rec).id)
        assertNotNull(rec.file(r.id, "index.m3u8"))
        assertNotNull(rec.file(r.id, "seg-000001.ts"))
        assertNull(rec.file(r.id, "../other/index.m3u8"))
        assertNull(rec.file(r.id, "seg-000009.ts"))
        assertNull(rec.file("nothing", "index.m3u8"))
    }
}
