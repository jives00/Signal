package com.signal.app

import com.signal.nas.NasRecording

/** What the Recordings page says about a recording (dvr.md). */
object RecordingText {
    /** Shown when the TV is paired but neither of the companion's addresses answers. */
    const val OUT_OF_REACH = "Can't reach the companion. Recordings need this TV on your home network or Tailscale."

    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    fun length(ms: Long): String {
        val minutes = ms / MINUTE
        return when {
            ms < MINUTE -> "${ms / 1000} s"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} hr"
            else -> "${minutes / 60} hr ${minutes % 60} min"
        }
    }

    /** "Expires in 3 days". Rounded up, so it never says 0 while it's still there. */
    fun expiry(expiresAt: Long, now: Long): String {
        val left = expiresAt - now
        if (left <= 0) return "Expires soon"
        val days = (left + DAY - 1) / DAY
        return if (days <= 1) "Expires today" else "Expires in $days days"
    }

    /** What's going on with it, in a few words. */
    fun status(r: NasRecording, now: Long): String {
        val parts = buildList {
            when (r.state) {
                "SCHEDULED" -> add("Scheduled")
                "WAITING" -> add("Waiting for a free source")
                "RECORDING" -> add("Recording now")
                "FAILED" -> add(r.reason ?: "Failed")
                "PARTIAL" -> add("Missing ${length(r.gapMs)}")
            }
            if (r.playable) add(length(r.durationMs))
            if (r.watchedAt != null) add("Watched")
            r.expiresAt?.let { add(expiry(it, now)) }
        }
        return parts.joinToString(" · ")
    }
}
