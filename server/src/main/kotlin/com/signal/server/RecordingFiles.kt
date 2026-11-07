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
 */
class RecordingFiles(private val dir: File, private val onSegment: (durationMs: Long, bytes: Long) -> Unit = { _, _ -> }) : TsSegmenter.Sink {
    private val playlist = File(dir, PLAYLIST)
    private var number: Int
    private var gap: Boolean
    private var out: BufferedOutputStream? = null
    private var part: File? = null
    private var written = 0L

    init {
        dir.mkdirs()
        val resuming = playlist.exists()
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        number = (dir.list().orEmpty().mapNotNull { SEGMENT.matchEntire(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0) + 1
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
        if (!part!!.renameTo(File(dir, name))) throw IOException("Couldn't finish a segment")
        val entry = buildString {
            if (gap) append("#EXT-X-DISCONTINUITY\n")
            append(String.format(Locale.ROOT, "#EXTINF:%.3f,\n", durationMs / 1000.0))
            append(name).append('\n')
        }
        playlist.appendText(entry)
        gap = false
        number++
        onSegment(durationMs, written)
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
