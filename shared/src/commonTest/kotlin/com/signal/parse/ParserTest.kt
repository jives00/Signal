package com.signal.parse

import com.signal.model.CredentialMasking
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.model.StreamIds
import com.signal.model.StreamUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class M3uParserTest {
    private val source = Source(id = "s1", name = "A", kind = SourceKind.M3U, url = "http://x/list.m3u", username = "joe", password = "secret")

    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-id="fs1.us" tvg-name="FOX Sports 1" tvg-logo="http://logo/fs1.png" tvg-chno="101" group-title="US Sports",US: FOX Sports 1 HD
        http://host:8080/live/joe/secret/1001.ts
        #EXTINF:-1 tvg-id="" group-title="News",CNN, International
        #EXTVLCOPT:http-user-agent=VLC/3.0
        http://host:8080/live/joe/secret/1002.ts
        #EXTINF:-1,No Group Channel
        #EXTGRP:Misc
        http://other/stream.m3u8
    """.trimIndent()

    @Test
    fun parsesAttributesNamesAndGroups() {
        val streams = M3uParser.parse(source, sample)
        assertEquals(3, streams.size)
        with(streams[0]) {
            assertEquals("US: FOX Sports 1 HD", rawName)
            assertEquals("US Sports", groupName)
            assertEquals("fs1.us", tvgId)
            assertEquals(101, providerNumber)
            assertEquals("http://logo/fs1.png", logoUrl)
        }
        assertEquals("CNN, International", streams[1].rawName)
        assertEquals("VLC/3.0", streams[1].headers["User-Agent"])
        assertEquals("Misc", streams[2].groupName)
        assertEquals(listOf(0, 1, 2), streams.map { it.position })
    }

    @Test
    fun idsAreStableAcrossReparses() {
        assertEquals(M3uParser.parse(source, sample).map { it.id }, M3uParser.parse(source, sample).map { it.id })
    }

    @Test
    fun aPasswordChangeKeepsTheSameIds() {
        val changed = source.copy(password = "newpass")
        val before = M3uParser.parse(source, sample).map { it.id }
        val after = M3uParser.parse(changed, sample.replace("/joe/secret/", "/joe/newpass/")).map { it.id }
        assertEquals(before, after)
    }

    @Test
    fun exactDuplicatesGetDistinctStableIds() {
        val dup = "#EXTINF:-1,Same\nhttp://a/1.ts\n#EXTINF:-1,Same\nhttp://a/1.ts\n"
        val ids = M3uParser.parse(source, dup).map { it.id }
        assertEquals(2, ids.toSet().size)
        assertEquals(ids, M3uParser.parse(source, dup).map { it.id })
    }
}

class XtreamParserTest {
    private val source = Source(id = "x1", name = "X", kind = SourceKind.XTREAM, url = "http://host:8080/", username = "joe", password = "secret")

    @Test
    fun readsLooselyTypedStreams() {
        val cats = XtreamParser.categories("""[{"category_id":"5","category_name":"US Sports"},{"category_id":6,"category_name":"News"}]""")
        val streams = XtreamParser.liveStreams(
            source,
            """[{"num":"1","name":"FOX Sports 1","stream_id":1001,"stream_icon":"http://l/1.png","epg_channel_id":"fs1.us","category_id":"5"},
                {"num":2,"name":"CNN","stream_id":"1002","stream_icon":null,"epg_channel_id":null,"category_id":6},
                {"num":3,"name":"Orphan","stream_id":"1003","category_id":"99"}]""",
            cats,
        )
        assertEquals(3, streams.size)
        assertEquals("US Sports", streams[0].groupName)
        assertEquals(1, streams[0].providerNumber)
        assertEquals("News", streams[1].groupName)
        assertEquals(null, streams[1].logoUrl)
        assertEquals("Uncategorized", streams[2].groupName)
        assertEquals("http://host:8080/live/joe/secret/1001.ts", StreamUrls.live(source, streams[0]))
        assertEquals(null, streams[0].url, "Xtream URLs are never stored: they embed credentials")
    }

    @Test
    fun readsAccountInfo() {
        val info = XtreamParser.accountInfo("""{"user_info":{"auth":1,"status":"Active","max_connections":"2","active_cons":"0","exp_date":"1767225600"}}""")
        assertTrue(info.authenticated)
        assertEquals(2, info.maxConnections)
        assertEquals(1767225600L, info.expiresAt)
    }

    @Test
    fun badLoginIsNotAuthenticated() {
        assertFalse(XtreamParser.accountInfo("""{"user_info":{"auth":0}}""").authenticated)
    }
}

class CredentialMaskingTest {
    private val source = Source(id = "s", name = "S", kind = SourceKind.XTREAM, url = "http://h", username = "joe", password = "secret")

    @Test
    fun stripsEveryCredentialForm() {
        assertEquals("http://host/live/1.ts", CredentialMasking.stripCredentials("http://joe:secret@host/live/1.ts"))
        assertEquals("http://host/get.php?type=m3u", CredentialMasking.stripCredentials("http://host/get.php?username=joe&password=secret&type=m3u"))
        assertEquals("http://host/live/1001.ts", CredentialMasking.stripCredentials("http://host/live/joe/secret/1001.ts", source))
    }

    @Test
    fun logFormNeverContainsCredentials() {
        val logged = CredentialMasking.forLog("http://host:8080/live/joe/secret/1001.ts?token=abc", source)
        assertFalse("joe" in logged || "secret" in logged || "abc" in logged, logged)
    }

    @Test
    fun hashIsStableAndSpreads() {
        assertEquals(StreamIds.fnv1a64("abc"), StreamIds.fnv1a64("abc"))
        assertNotEquals(StreamIds.fnv1a64("abc"), StreamIds.fnv1a64("abd"))
        assertEquals("e71fa2190541574b", StreamIds.fnv1a64("abc"))
    }
}
