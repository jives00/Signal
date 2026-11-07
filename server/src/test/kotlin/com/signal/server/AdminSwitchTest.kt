package com.signal.server

import com.signal.model.Source
import com.signal.model.SourceKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminSwitchTest {
    private fun xtream(id: String, url: String) = Source(id, id, SourceKind.XTREAM, url, username = id)

    @Test fun twoLoginsToOneServerAreOneProvider() {
        assertTrue(AdminSwitch.sameProvider(xtream("a", "http://cf.example.vip"), xtream("b", "HTTP://cf.example.vip/")))
        assertFalse(AdminSwitch.sameProvider(xtream("a", "http://cf.example.vip"), xtream("b", "http://other.example.vip")))
        assertFalse(AdminSwitch.sameProvider(xtream("a", "http://cf.example.vip:8080"), xtream("b", "http://cf.example.vip")))
    }

    @Test fun demoAndM3uSourcesAreNeverTheSameProvider() {
        // Demo sources stand in for different providers in the switch tests, and share "demo:".
        assertFalse(AdminSwitch.sameProvider(Source("a", "a", SourceKind.DEMO, "demo:"), Source("b", "b", SourceKind.DEMO, "demo:")))
        assertFalse(AdminSwitch.sameProvider(Source("a", "a", SourceKind.M3U, "http://x/list.m3u"), Source("b", "b", SourceKind.M3U, "http://x/list.m3u")))
    }
}
