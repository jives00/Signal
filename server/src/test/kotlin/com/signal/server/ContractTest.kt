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
            "sources", SourcesView.serializer(),
            SourcesView(listOf(source), listOf(EpgSourceView("epg:src", "Provider guide", "XTREAM", "", "src", true, 0, true, 8343, 118_000, null))),
        )
        check("groups", GroupsView.serializer(), GroupsView("PROVIDER", "PROVIDER", listOf(GroupView("src", "Provider", "US Sports", "Sports", 120, false, null, "Sports"))))
        check("lists", ListsView.serializer(), ListsView(listOf(row), listOf(PlaylistView("pl-1", "Sports", listOf(row)))))
        check(
            "queue", QueueView.serializer(),
            QueueView(12, row.copy(guide = null), listOf(GuideChannelInfo("epg:src", "Provider guide", "fs1.us", "Fox Sports 1", 0.95, null, null))),
        )
        check("settings", SettingsView.serializer(), SettingsView(mapOf("default_epg_source" to "epg:src"), mapOf("clock_24h" to "true"), CompanionSettingsView("03:00", "America/Chicago", 30, 90, "main")))
        check(
            "overview", Overview.serializer(),
            Overview(listOf(source), Coverage(900, 1000), 2, 0, listOf(job), listOf(Device("d1", "Living room", "SHIELD", "0.3.0", 1, 2, "main", 3)), "0.3.0"),
        )
    }
}
