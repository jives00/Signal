package com.signal.parse

/**
 * Splits a top-level JSON array into its objects as the bytes arrive, so a provider's 75 MB movie
 * list is never held whole (PLAN §6: lists are streamed, as the M3U parser does line by line). Each
 * object is handed over as its own small JSON text. Anything that isn't an object in the array, or a
 * body that isn't an array at all (an error object), gives nothing.
 *
 * It works on bytes: JSON's structure is ASCII, and UTF-8 never uses ASCII bytes inside a multi-byte
 * character, so splitting there never cuts a character in half.
 */
class JsonArrayObjects(private val onObject: (String) -> Unit) {
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var started = false
    private var rejected = false
    private var buf = ByteArray(4096)
    private var len = 0

    fun feed(bytes: ByteArray, count: Int = bytes.size) {
        for (i in 0 until count) {
            if (rejected) return
            val b = bytes[i]
            val ch = b.toInt().toChar()
            if (!started) {
                val u = b.toInt() and 0xFF
                // Whitespace and a UTF-8 byte order mark may come first; anything else isn't an array.
                if (ch == '[') { started = true; depth = 1 } else if (!ch.isWhitespace() && u != 0xEF && u != 0xBB && u != 0xBF) rejected = true
                continue
            }
            // Inside an object of the array: keep its bytes.
            if (depth >= 2) append(b)
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> {
                    if (depth == 1) {
                        len = 0
                        if (ch == '{') append(b)
                    }
                    depth++
                }
                '}', ']' -> {
                    depth--
                    if (depth == 1 && ch == '}' && len > 0) {
                        onObject(buf.decodeToString(0, len))
                        len = 0
                    } else if (depth == 1) {
                        len = 0 // a nested array in the top array: not an object, skipped
                    } else if (depth == 0) {
                        rejected = true // the array ended
                    }
                }
            }
        }
    }

    private fun append(b: Byte) {
        if (len == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[len++] = b
    }
}
