package com.signal.server

import com.signal.sync.SettingsFile
import com.signal.sync.SyncItem
import com.signal.sync.fields
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The contract between the Kotlin types and the web admin's Zod mirror (PLAN §5.2 → Types). This
 * writes one fixture per type, serialized by the real classes, into web/src/contract/. The web's
 * schema.test.ts parses each with its Zod schema, so a renamed or retyped field fails one side.
 *
 * A fixture that differs from what's committed fails here too: rerun with SIGNAL_WRITE_CONTRACT=1,
 * update schema.ts to match, and commit both.
 */
class ContractTest {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val dir = File("../web/src/contract")

    private fun <T> check(name: String, serializer: KSerializer<T>, value: T) {
        val text = json.encodeToString(serializer, value) + "\n"
        val file = File(dir, "$name.json")
        if (System.getenv("SIGNAL_WRITE_CONTRACT") == "1" || !file.exists()) {
            dir.mkdirs()
            file.writeText(text)
        }
        assertEquals(file.readText().replace("\r\n", "\n"), text, "web/src/contract/$name.json is out of date: rerun with SIGNAL_WRITE_CONTRACT=1 and update web/src/schema.ts")
    }

    private val guide = GuideStatusView("epg:src", "Provider guide", "fs1.us", "Fox Sports 1", how = "auto", override = false, fallback = false)
    private val row = ChannelRow(
        streamId = "src:x:42", sourceId = "src", sourceName = "Provider", group = "US Sports", rawName = "US: FOX Sports 1 ᴴᴰ", name = "FOX Sports 1",
        number = 42, logo = "http://logo.example/fs1.png", tvgId = "FS1.us", userChannelId = "ch:src:x:42", favorite = true, playlists = listOf("Sports"),
        hidden = false, groupHidden = false, customName = null, customLogo = null, customNumber = null, guide = guide,
        now = ProgramBrief("Live football", 1_791_828_000_000, 1_791_835_200_000), next = null,
    )
    private val source = SourceView("src", "Provider", "XTREAM", "http://p.example", "me", true, null, null, true, "TS", 2, 1_791_828_000_000, null, 57_000, false)
    private val job = JobRun(1, "guide_build", 1_791_828_000_000, 1_791_828_160_000, "ok", "nightly: 283302 programs")

    @Test fun fixturesMatchTheKotlinTypes() {
        check(
            "settings-file", SettingsFile.serializer(),
            SettingsFile(exportedAt = 1_791_828_000_000, items = listOf(SyncItem("favorite", "ch:src:x:42", "profile", "main", fields("sort_key" to "a0"), rev = 7, updatedAt = 1))),
        )
        check("edit-result", EditResult.serializer(), EditResult(8, 1, listOf(AdminOp("favorite", "ch:src:x:42", "profile", "main", deleted = true))))
        check("channel-page", ChannelPage.serializer(), ChannelPage(1, 0, listOf(row)))
        check(
            "releases", ReleasesView.serializer(),
            ReleasesView(
                listOf(
                    ReleaseView("apk-v0.2.30", "Signal 0.2.30", 1_791_828_000_000, stable = false, notes = "- Release channels"),
                    ReleaseView("apk-v0.2.29", "Signal 0.2.29", 1_791_741_600_000, stable = true, notes = "- Banner is back"),
                ),
                tokenSet = true,
            ),
        )
        check(
            "voice-help", com.signal.voice.VoiceHelp.serializer(),
            com.signal.voice.VoiceHelp(
                "Press the mic button and say a channel.",
                listOf(
                    com.signal.voice.VoiceHelp.Group("Live TV", "The keyword is “Watch”.", listOf("“ESPN” switches to that channel.")),
                    com.signal.voice.VoiceHelp.Group("Other", items = listOf("“Captions on” turns on captions.")),
                ),
            ),
        )
        check(
            "sources", SourcesView.serializer(),
            SourcesView(listOf(source), listOf(EpgSourceView("epg:src", "Provider guide", "XTREAM", "", "src", true, 0, true, 8343, 118_000, null, 1_760_000_000_000)), guideBuilding = false),
        )
        check("groups", GroupsView.serializer(), GroupsView("PROVIDER", "PROVIDER", listOf(GroupView("src", "Provider", "US Sports", "Sports", 120, false, null, "Sports"))))
        check("lists", ListsView.serializer(), ListsView(listOf(row), listOf(PlaylistView("pl-1", "Sports", listOf(row)))))
        check(
            "queue", QueueView.serializer(),
            QueueView(12, row.copy(guide = null), listOf(GuideChannelInfo("epg:src", "Provider guide", "fs1.us", "Fox Sports 1", 0.95, null, null))),
        )
        check("settings", SettingsView.serializer(), SettingsView(mapOf("default_epg_source" to "epg:src"), mapOf("clock_24h" to "true"), CompanionSettingsView("03:00", "America/Chicago", 30, 90, "main", true, null, 20)))
        check(
            "cleanup-preview", CleanupPreview.serializer(),
            CleanupPreview(listOf(CleanupPresetView("COUNTRY", "Country codes at the start", "US: ESPN → ESPN")), 1000, 400, 50, 0, listOf(CleanupSample("US: ESPN FHD", "ESPN")), emptyList(), listOf("(unclosed")),
        )
        check(
            "overview", Overview.serializer(),
            Overview(listOf(source), Coverage(900, 1000), 2, 0, listOf(job), listOf(Device("d1", "Living room", "SHIELD", "0.3.0", 1, 2, "main", 3)), "0.3.0", 4),
        )
        check(
            "switch", SwitchView.serializer(),
            SwitchView(
                "new", "New provider", listOf(SourceRef("src", "Provider", true)),
                listOf(ProposalView("ch:src:x:42", "FOX Sports 1", true, "US: FOX Sports 1", "Provider", listOf(MatchView("new:x:9", "FS1 HD", "Sports", 0.6, "CONFIDENT", listOf("same tvg-id"))))),
                emptyList(), listOf(UnmatchedView("ch:src:x:7", "Local 7", false, "Provider")), 12,
                listOf(GroupProposalView("src", "Provider", "US Sports", "Sports")),
            ),
        )
        check("vod-status", VodStatus.serializer(), VodStatus(true, 1200, 1000, 3, 150, true, "Matching 12 of 47", job.copy(job = "vod_enrich"), 1_500_000_000))
        check(
            "vod-rows", VodRows.serializer(),
            VodRows(listOf(VodRow("src:v:603", "src", "MOVIE", "The Matrix", "EN - The Matrix (1999)", 1999, "Action", "EN - ACTION", "matched", 603, "The Matrix", 1999), VodRow("src:s:9", "src", "SERIES", "Home Video", "Home Video", null, "", "", "pending", null, null, null)), 2),
        )
        check(
            "vod-categories", VodCategoriesView.serializer(),
            VodCategoriesView("MANUAL", listOf(VodCategoryView("src", "Provider", "MOVIE", "3", "EN - ACTION", "Action", 120, false, "Action"), VodCategoryView("src", "Provider", "SERIES", "9", "ES - SERIES", "ES - SERIES", 0, true, null))),
        )
        check("tmdb-candidates", kotlinx.serialization.builtins.ListSerializer(TmdbCandidate.serializer()), listOf(TmdbCandidate(603, "The Matrix", null, 1999, "/matrix.jpg", "A hacker learns the truth.")))
        check("links", kotlinx.serialization.builtins.ListSerializer(LinkView.serializer()), listOf(LinkView("src:x:42", "FOX Sports 1", "US Sports", "Provider", true, true)))
        check(
            "library", kotlinx.serialization.builtins.ListSerializer(LibraryRow.serializer()),
            listOf(LibraryRow("tmdb:movie:603", "MOVIE", "The Matrix", 1999, 1_760_000_000_000, true), LibraryRow("src:s:9", "SERIES", "Home Video", null, 1_760_000_000_000, false)),
        )
    }
}
