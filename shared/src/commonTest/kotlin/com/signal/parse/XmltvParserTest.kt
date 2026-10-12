package com.signal.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmltvParserTest {
    private fun parse(xml: String, chunk: Int = 7): Pair<List<XmltvChannel>, List<XmltvProgram>> {
        val channels = ArrayList<XmltvChannel>()
        val programs = ArrayList<XmltvProgram>()
        var at = 0
        // Small chunks, so tokens split across reads are exercised.
        XmltvParser(channels::add, programs::add).parse { buf ->
            if (at >= xml.length) -1 else {
                val n = minOf(chunk, buf.size, xml.length - at)
                xml.toCharArray(buf, 0, at, at + n)
                at += n
                n
            }
        }
        return channels to programs
    }

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <!-- generated -->
        <tv generator-info-name="test">
          <channel id="fs1.us">
            <display-name lang="en">FOX Sports 1</display-name>
            <display-name>FS1</display-name>
            <icon src="http://logo/fs1.png" />
          </channel>
          <programme start="20261012180000 +0000" stop="20261012200000 +0000" channel="fs1.us">
            <title lang="en">College Football: Ohio State &amp; Michigan</title>
            <sub-title>Week 7</sub-title>
            <desc><![CDATA[Big <rivalry> game.]]></desc>
            <category>Sports</category>
            <category>Football</category>
            <episode-num system="xmltv_ns">2.6.</episode-num>
            <episode-num system="onscreen">S03E07</episode-num>
            <icon src="http://img/cfb.jpg"/>
            <rating system="VCHIP"><value>TV-PG</value></rating>
            <new />
            <live/>
            <credits><presenter>Gus Johnson</presenter></credits>
            <unknown><nested>ignored</nested></unknown>
          </programme>
          <programme start="20261012200000 -0400" stop="20261012210000 -0400" channel="fs1.us">
            <title>Post&#45;game &#x2014; Show</title>
          </programme>
          <programme start="bad" stop="20261012210000" channel="fs1.us"><title>Dropped</title></programme>
        </tv>
    """.trimIndent()

    @Test fun channelsAndPrograms() {
        val (channels, programs) = parse(sample)
        assertEquals(listOf(XmltvChannel("fs1.us", listOf("FOX Sports 1", "FS1"), "http://logo/fs1.png")), channels)
        assertEquals(2, programs.size)
        val p = programs[0]
        assertEquals("College Football: Ohio State & Michigan", p.title)
        assertEquals("Week 7", p.subTitle)
        assertEquals("Big <rivalry> game.", p.description)
        assertEquals(listOf("Sports", "Football"), p.categories)
        assertEquals(3, p.season)
        assertEquals(7, p.episode)
        assertEquals("S03E07", p.episodeLabel)
        assertEquals("http://img/cfb.jpg", p.icon)
        assertEquals("TV-PG", p.rating)
        assertTrue(p.isNew && p.isLive)
        assertEquals(listOf("Gus Johnson"), p.credits)
        assertEquals(XmltvTime.parse("20261012180000 +0000"), p.start)
        assertEquals(2 * 3600_000L, p.stop - p.start)
    }

    @Test fun entitiesInText() {
        assertEquals("Post-game — Show", parse(sample).second[1].title)
    }

    @Test fun parsesInOneChunkToo() {
        assertEquals(parse(sample, chunk = 7), parse(sample, chunk = 100_000))
    }

    @Test fun timesWithOffsetsAndWithout() {
        val utc = XmltvTime.parse("20261012180000 +0000")!!
        assertEquals(utc, XmltvTime.parse("20261012180000"))
        assertEquals(utc, XmltvTime.parse("20261012200000 +0200"))
        assertEquals(utc, XmltvTime.parse("20261012140000 -0400"))
        assertEquals(utc, XmltvTime.parse("202610121800 +0000"))
        // 2026-10-12T18:00:00Z
        assertEquals(1_791_828_000_000L, utc)
        assertNull(XmltvTime.parse("bad"))
    }
}
