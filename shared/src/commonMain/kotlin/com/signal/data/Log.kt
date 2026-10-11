package com.signal.data

import kotlin.time.Clock

/**
 * App log: written to the platform console (logcat or stdout) and kept in a ring buffer that
 * "Send logs" uploads in milestone 3 (PLAN §5.10). Callers must never pass credentials or full
 * stream URLs; use CredentialMasking.forLog (PLAN §1).
 */
class Log(private val sink: (level: String, message: String) -> Unit) {
    private val buffer = ArrayDeque<String>()

    fun info(message: String) = write("I", message)
    fun warn(message: String) = write("W", message)
    fun error(message: String, error: Throwable? = null) =
        write("E", if (error == null) message else "$message: ${error::class.simpleName}: ${error.message}")

    fun recent(): List<String> = synchronizedCopy()

    private fun write(level: String, message: String) {
        sink(level, message)
        val line = "${Clock.System.now()} $level $message"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
    }

    private fun synchronizedCopy(): List<String> = synchronized(buffer) { buffer.toList() }

    companion object {
        const val CAPACITY = 2000
    }
}

internal expect inline fun <T> synchronized(lock: Any, block: () -> T): T
