package com.signal.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Logs what the main thread was doing when it's blocked for more than [THRESHOLD_MS]: its stack once
 * the block passes the threshold, then how long it lasted. A tune from Live Sports sat 5 s before its
 * request went out (1.1.16, 2026-11-07), and logcat had rolled over before anyone could look; Send
 * logs carries these lines, so the next one names its own cause.
 */
class MainThreadWatchdog(private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastBeat = SystemClock.uptimeMillis()
    private val beat = Runnable { lastBeat = SystemClock.uptimeMillis() }

    fun start() {
        Thread({
            var reportedFor = -1L
            while (true) {
                main.post(beat)
                Thread.sleep(POLL_MS)
                val since = lastBeat
                val blocked = SystemClock.uptimeMillis() - since
                if (blocked >= THRESHOLD_MS && reportedFor != since) {
                    reportedFor = since
                    val stack = Looper.getMainLooper().thread.stackTrace.take(STACK_FRAMES).joinToString("\n  at ")
                    log("Main thread blocked ${blocked} ms, so far, at:\n  at $stack")
                    // Then how long it lasted, once it's free again.
                    val start = since
                    main.post { log("Main thread was blocked ${SystemClock.uptimeMillis() - start} ms") }
                }
            }
        }, "main-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private companion object {
        const val POLL_MS = 250L
        const val THRESHOLD_MS = 1_000L
        const val STACK_FRAMES = 25
    }
}
