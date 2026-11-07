package com.signal.server

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * One recording's folder: numbered segments and an EVENT playlist that grows as they finish, so it
 * can be watched while it records (dvr.md → Capture). The playlist is only ever appended to. A folder
 * that already has one carries on after it with a gap, which is how a restart resumes.
 *
 * A finished segment waits off the playlist until the segmenter [confirm]s it's the channel, or
 * [discard]s it when the provider sent a placeholder instead (TsSegmenter → NotLive).
 */
class RecordingFiles(private val dir: File, private val onSegment: (durationMs: Long, bytes: Long) -> Unit = { _, _ -> }) : TsSegmenter.Sink {
    private class Held(val file: File, val entry: String, val durationMs: Long, val bytes: Long)

    private val playlist = File(dir, PLAYLIST)
    private var number: Int
    private var gap: Boolean
    private var out: BufferedOutputStream? = null
    private var part: File? = null
    private var written = 0L
    private val held = mutableListOf<Held>()

    init {
        dir.mkdirs()
        val resuming = playlist.exists()
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        // Segments a crash left off the playlist are overwritten as numbering carries on from the playlist.
        number = (playlist.takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { SEGMENT.matchEntire(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0) + 1
        gap = resuming
        if (!resuming) {
            playlist.writeText(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:${TsSegmenter.PLAYLIST_TARGET_SECONDS}\n" +
                    "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n",
            )
        }
    }

    /** The next segment follows a stretch that wasn't recorded. */
    override fun markGap() {
        gap = true
    }

    override fun begin() {
        val file = File(dir, "${name(number)}.part")
        part = file
        written = 0
        out = BufferedOutputStream(FileOutputStream(file), 256 * 1024)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        out!!.write(data, offset, length)
        written += length
    }

    override fun finish(durationMs: Long) {
        out!!.close()
        out = null
        val name = name(number)
        val file = File(dir, name)
        if (!part!!.renameTo(file)) throw IOException("Couldn't finish a segment")
        val entry = buildString {
            if (gap) append("#EXT-X-DISCONTINUITY\n")
            append(String.format(Locale.ROOT, "#EXTINF:%.3f,\n", durationMs / 1000.0))
            append(name).append('\n')
        }
        held += Held(file, entry, durationMs, written)
        gap = false
        number++
    }

    override fun confirm() {
        if (held.isEmpty()) return
        val batch = held.toList()
        held.clear()
        playlist.appendText(batch.joinToString("") { it.entry })
        batch.forEach { onSegment(it.durationMs, it.bytes) }
    }

    override fun discard() {
        if (held.isEmpty()) return
        held.forEach { it.file.delete() }
        number -= held.size
        held.clear()
        gap = true
    }

    /** No more segments: players treat the playlist as complete. */
    fun end() {
        if (!playlist.readText().contains(ENDLIST)) playlist.appendText("$ENDLIST\n")
    }

    companion object {
        const val PLAYLIST = "index.m3u8"
        private const val ENDLIST = "#EXT-X-ENDLIST"
        private val SEGMENT = Regex("""seg-(\d+)\.ts""")
        private fun name(number: Int) = String.format(Locale.ROOT, "seg-%06d.ts", number)

        /** A file a recording's folder may serve; everything else is refused. */
        fun servable(name: String) = name == PLAYLIST || SEGMENT.matches(name)
    }
}
