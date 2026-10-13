package com.signal.parse

import kotlin.test.Test
import kotlin.test.assertEquals

/** The streamed list reader (PLAN §6): objects out of a top-level array, whatever the chunk sizes. */
class JsonArrayObjectsTest {
    private fun split(text: String, chunk: Int): List<String> {
        val out = ArrayList<String>()
        val splitter = JsonArrayObjects { out += it }
        text.encodeToByteArray().toList().chunked(chunk).forEach { splitter.feed(it.toByteArray()) }
        return out
    }

    @Test fun objectsComeOutWholeAtAnyChunkSize() {
        // A quote, brackets and an escaped backslash inside a string don't end anything.
        val text = """ [ {"a":1,"b":{"c":[1,2,{"d":"}"}]}}, 7, "x", [1,{"e":2}], {"name":"Amélie \"{[\\ ]} x"} ] """
        val expected = listOf("""{"a":1,"b":{"c":[1,2,{"d":"}"}]}}""", """{"name":"Amélie \"{[\\ ]} x"}""")
        for (chunk in listOf(1, 2, 3, 7, 64, 4096)) assertEquals(expected, split(text, chunk), "chunk $chunk")
    }

    @Test fun aByteOrderMarkIsSkippedAndAnErrorObjectGivesNothing() {
        assertEquals(listOf("""{"a":1}"""), split("﻿[{\"a\":1}]", 1))
        assertEquals(emptyList(), split("""{"user_info":{"auth":0}}""", 5))
        assertEquals(emptyList(), split("[]", 1))
    }
}
