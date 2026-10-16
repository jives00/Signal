package com.signal.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ChangelogTest {
    @Test
    fun `tag notes become bullets`() {
        assertEquals(
            "• Fixed the guide jumping\n• Faster startup",
            GitHubReleases.changelog("- Fixed the guide jumping\r\n* Faster startup\n"),
        )
    }

    private fun release(tag: String, notes: String) = GitHubReleases.Release(tag, GitHubReleases.changelog(notes), "")

    // Every release between the installed version and the offered one, newest first (user, 2026-10-16).
    @Test
    fun `changes since the installed version`() {
        val releases = listOf(
            release("apk-v0.2.29", "Signal 0.2.29\n\n- Banner is back\n- Faster titles"),
            release("apk-v0.2.28", "Signal 0.2.28\n\n- No grey screen"),
            release("apk-v0.2.27", "Signal 0.2.27\n\n- Favorites only"),
            release("apk-v0.2.25", "Signal 0.2.25\n\n- Takes the mic back"),
        )
        assertEquals(
            "Version 0.2.29\n• Banner is back\n• Faster titles\n\nVersion 0.2.28\n• No grey screen",
            GitHubReleases.changesSince(releases, "apk-v0.2.27", "apk-v0.2.29"),
        )
        // A skipped version that was never released (0.2.26) simply isn't there; the range still holds.
        assertEquals(
            "Version 0.2.27\n• Favorites only",
            GitHubReleases.changesSince(releases, "apk-v0.2.25", "apk-v0.2.27"),
        )
    }

    @Test
    fun `nothing to show falls back to the newest notes`() {
        assertEquals(null, GitHubReleases.changesSince(emptyList(), "apk-v0.2.27", "apk-v0.2.29"))
        assertEquals(null, GitHubReleases.changesSince(listOf(release("apk-v0.2.29", "- x")), "dev", "apk-v0.2.29"))
    }

    @Test
    fun `versions order numerically`() {
        val releases = listOf(release("apk-v0.2.10", "- ten"), release("apk-v0.2.9", "- nine"))
        assertEquals("Version 0.2.10\n• ten\n\nVersion 0.2.9\n• nine", GitHubReleases.changesSince(releases, "apk-v0.2.8", "apk-v0.2.10"))
    }

    @Test
    fun `a releases list parses, drafts left out`() {
        val json = """[
            {"tag_name":"apk-v0.2.29","draft":false,"body":"- a","assets":[{"name":"signal-v0.2.29.apk","browser_download_url":"https://x/a.apk"}]},
            {"tag_name":"apk-v0.2.30","draft":true,"body":"- draft","assets":[]},
            {"tag_name":"apk-v0.2.28","draft":false,"body":"- b","assets":[]}
        ]"""
        assertEquals(listOf("apk-v0.2.29", "apk-v0.2.28"), GitHubReleases.parseReleases(json).map { it.tag })
        assertEquals("https://x/a.apk", GitHubReleases.parseReleases(json).first().apkUrl)
    }

    @Test
    fun `GitHub's generated footer and headings are dropped`() {
        val generated = "## What's Changed\n\n\n**Full Changelog**: https://github.com/jives00/Signal/compare/apk-v0.2.8...apk-v0.2.9"
        assertEquals("", GitHubReleases.changelog(generated))
    }
}
