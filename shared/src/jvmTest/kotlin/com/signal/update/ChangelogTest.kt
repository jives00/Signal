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

    @Test
    fun `GitHub's generated footer and headings are dropped`() {
        val generated = "## What's Changed\n\n\n**Full Changelog**: https://github.com/jives00/Signal/compare/apk-v0.2.8...apk-v0.2.9"
        assertEquals("", GitHubReleases.changelog(generated))
    }
}
