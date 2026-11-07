package com.signal.app

import com.signal.nas.NasRecording

/** What the Recordings page says about a recording (dvr.md). */
object RecordingText {
    /** Shown when the TV is paired but neither of the companion's addresses answers. */
    const val OUT_OF_REACH = "Can't reach the companion. Recordings need this TV on your home network or Tailscale."

    /** Set, but it won't all fit: said straight away, since the user can still change it. */
    const val CONFLICT = "Set, but no source is free for all of it, so some of it may be missed."
    const val MAY_MISS = "No free source for all of it"
    const val SETTING = "Setting the recording…"
    const val WORKING = "One moment…"
    const val NO_ANSWER = "The companion didn't answer in time. Check Recordings before trying again."
    const val KEEP_LONGER = "Keep recording 30 more minutes"
    const val KEPT_LONGER = "It'll keep recording 30 minutes longer."
    const val KEEP_LONGER_MINUTES = 30

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
    fun status(r: NasRecording, now: Long, conflict: Boolean = false): String {
        val parts = buildList {
            when (r.state) {
                "SCHEDULED" -> add(if (conflict) MAY_MISS else "Scheduled")
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
