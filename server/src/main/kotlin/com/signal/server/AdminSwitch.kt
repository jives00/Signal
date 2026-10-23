package com.signal.server

import com.signal.match.ChannelMatcher
import com.signal.match.ProviderSwitch
import com.signal.sync.SyncScope
import com.signal.sync.SyncType
import com.signal.sync.fields
import com.signal.sync.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MatchView(
    val streamId: String,
    val name: String,
    val group: String,
    val score: Double,
    val confidence: String,
    val reasons: List<String>,
    val now: ProgramBrief? = null,
)

@Serializable
data class ProposalView(
    val userChannelId: String,
    val name: String,
    val favorite: Boolean,
    val fromName: String,
    val fromSource: String,
    val matches: List<MatchView>,
)

@Serializable data class UnmatchedView(val userChannelId: String, val name: String, val favorite: Boolean, val fromSource: String)
@Serializable data class GroupProposalView(val sourceId: String, val sourceName: String, val group: String, val toGroup: String?)

/** The provider switch report (PLAN §4.3.18 → Provider switch, §4.5). */
@Serializable
data class SwitchView(
    val targetSourceId: String,
    val targetName: String,
    /** The other sources, which the final step disables. */
    val otherSources: List<SourceRef>,
    val confident: List<ProposalView>,
    val suggested: List<ProposalView>,
    val unmatched: List<UnmatchedView>,
    val alreadyLinked: Int,
    val groups: List<GroupProposalView>,
)

@Serializable data class SourceRef(val id: String, val name: String, val enabled: Boolean)
@Serializable data class LinkRequest(val userChannelId: String, val streamId: String, val confident: Boolean = false)
@Serializable data class AcceptRequest(val links: List<LinkRequest>)
@Serializable data class RejectRequest(val userChannelId: String, val streamIds: List<String>)
@Serializable data class GroupCarry(val fromSource: String, val fromGroup: String, val toSource: String, val toGroup: String)
@Serializable data class GroupCarryAll(val toSource: String)

/**
 * The provider switch on the companion (PLAN §4.5 → Switching providers): the matcher's report for
 * a source added beside another, and the review's decisions as sync items. Linking adds a stream
 * to a user channel; nothing is unlinked, and the old source keeps playing until it's disabled.
 */
class AdminSwitch(private val companion: Companion) {
    private var cache: Pair<List<Any?>, ProviderSwitch.Report>? = null

    private fun stream(s: CatalogStream) = ChannelMatcher.Stream(s.id, s.sourceId, s.rawName, s.tvgId, s.logoUrl, s.groupName)

    private fun aliases(items: List<com.signal.sync.SyncItem>) =
        ChannelMatcher.aliases(items.filter { it.type == SyncType.ALIAS && it.scope == SyncScope.HOUSEHOLD }.mapNotNull { a -> a.fields.str("a")?.let { x -> a.fields.str("b")?.let { x to it } } })

    @Synchronized fun report(target: String, profile: String): ProviderSwitch.Report {
        val key = listOf(target, profile, companion.catalog.version, companion.sync.currentRev())
        cache?.takeIf { it.first == key }?.let { return it.second }
        val items = companion.sync.liveItems()
        val view = AdminView(items, profile)
        val byId = companion.catalog.streams.associateBy { it.id }
        val userChannels = view.streamsByUserChannel.map { (uc, links) ->
            val streams = links.mapNotNull { byId[it.streamId] }.map(::stream)
            ProviderSwitch.UserChannel(uc, view.channelProfiles[uc]?.str("custom_name") ?: streams.firstOrNull()?.rawName ?: uc, streams, uc in view.favorites)
        }
        val rejected = items.filter { it.type == SyncType.MATCH_REJECT }.mapNotNull { r -> r.fields.str("user_channel_id")?.let { u -> r.fields.str("stream_id")?.let { u to it } } }.toSet()
        val groups = items.filter { it.type == SyncType.GROUP_OVERRIDE }.mapNotNull { g -> g.fields.str("source_id")?.let { s -> g.fields.str("group_name")?.let { s to it } } }
        val report = ProviderSwitch.report(
            target, userChannels, companion.catalog.streams.filter { it.sourceId == target }.map(::stream),
            view.userChannelByStream.keys, rejected, aliases(items), groups,
        )
        cache = key to report
        return report
    }

    fun view(target: String, profile: String, now: Long): SwitchView {
        val view = AdminView(companion.sync.liveItems(), profile)
        val names = view.sources.associate { it.first.id to it.first.name }
        val r = report(target, profile)
        val byId = companion.catalog.streams.associateBy { it.id }
        fun proposal(p: ProviderSwitch.Proposal, withPrograms: Boolean): ProposalView {
            val from = p.userChannel.streams.firstOrNull { it.sourceId != target }
            return ProposalView(
                p.userChannel.id, p.userChannel.name, p.userChannel.favorite, from?.rawName.orEmpty(), from?.let { names[it.sourceId] }.orEmpty(),
                p.matches.map { m ->
                    val program = if (withPrograms) byId[m.stream.id]?.let { s ->
                        companion.guideReader.resolve(null, s.tvgId, s.rawName, view.epgSources, view.defaultEpgId)
                            ?.let { ref -> companion.guideReader.nowAndNext(ref.epgSourceId, ref.guideChannelId, ref.timeShiftMs, now).first }
                    } else null
                    MatchView(m.stream.id, m.stream.rawName, m.stream.groupName, m.score, m.confidence.name, m.reasons, program)
                },
            )
        }
        return SwitchView(
            target, names[target] ?: target, view.sources.filter { it.first.id != target }.map { SourceRef(it.first.id, it.first.name, it.first.enabled) },
            r.confident.map { proposal(it, false) }, r.suggested.map { proposal(it, true) },
            r.unmatched.map { u -> UnmatchedView(u.id, u.name, u.favorite, u.streams.firstOrNull()?.let { names[it.sourceId] }.orEmpty()) },
            r.alreadyLinked,
            r.groups.map { GroupProposalView(it.sourceId, names[it.sourceId] ?: it.sourceId, it.group, it.toGroup) },
        )
    }

    /** Counts for the Dashboard: channels to review across sources, when there's more than one. */
    fun pending(profile: String): Int {
        val view = AdminView(companion.sync.liveItems(), profile)
        val enabled = view.sources.filter { it.first.enabled }
        if (enabled.size < 2) return 0
        return enabled.sumOf { (s, _) -> report(s.id, profile).let { it.suggested.size + it.confident.size } }
    }

    /**
     * Links streams to user channels. A confirmed suggestion whose names differ teaches an alias,
     * which syncs (PLAN §4.5: aliases "grow from the user's manual confirmations").
     */
    suspend fun accept(links: List<LinkRequest>): EditResult {
        val byId = companion.catalog.streams.associateBy { it.id }
        val view = AdminView(companion.sync.liveItems(), SyncService.MAIN)
        val ops = ArrayList<AdminOp>()
        for (l in links) {
            if (view.userChannelByStream[l.streamId] != null) continue
            ops += AdminOp(
                SyncType.CHANNEL_LINK, "${l.userChannelId}|${l.streamId}", SyncScope.HOUSEHOLD,
                fields = fields("user_channel_id" to l.userChannelId, "stream_id" to l.streamId, "kind" to if (l.confident) "auto" else "manual", "preferred" to false),
            )
            if (!l.confident) {
                val to = byId[l.streamId]?.rawName ?: continue
                val from = view.streamsByUserChannel[l.userChannelId].orEmpty().firstNotNullOfOrNull { byId[it.streamId]?.rawName } ?: continue
                val a = ChannelMatcher.canonical(to)
                val b = ChannelMatcher.canonical(from)
                if (a != b && a.isNotEmpty() && b.isNotEmpty()) ops += AdminOp(SyncType.ALIAS, "$a|$b", SyncScope.HOUSEHOLD, fields = fields("a" to a, "b" to b))
            }
        }
        return companion.sync.edit(ops, "Provider switch")
    }

    suspend fun reject(r: RejectRequest): EditResult = companion.sync.edit(
        r.streamIds.map { s -> AdminOp(SyncType.MATCH_REJECT, "${r.userChannelId}|$s", SyncScope.HOUSEHOLD, fields = fields("user_channel_id" to r.userChannelId, "stream_id" to s)) },
        "Provider switch",
    )

    /** Carries a group's settings to its counterpart, in every profile that has them (PLAN §4.5 → Provider groups). */
    suspend fun carryGroup(g: GroupCarry): EditResult = companion.sync.edit(carryOps(companion.sync.liveItems(), listOf(g)), "Provider switch")

    /**
     * Carries every group that has the same name on [GroupCarryAll.toSource], in one edit. Loose
     * matches stay for the review: a guess like CA| SOCCER PPV for US| SOCCER PPV would hide the wrong group.
     */
    suspend fun carryAllGroups(r: GroupCarryAll): EditResult {
        val carries = report(r.toSource, SyncService.MAIN).groups
            .filter { it.toGroup == it.group }
            .map { GroupCarry(it.sourceId, it.group, r.toSource, it.group) }
        return companion.sync.edit(carryOps(companion.sync.liveItems(), carries), "Provider switch")
    }

    private fun carryOps(items: List<com.signal.sync.SyncItem>, carries: List<GroupCarry>): List<AdminOp> {
        val overrides = items.filter { it.type == SyncType.GROUP_OVERRIDE }.groupBy { it.id }
        return carries.flatMap { g ->
            overrides["${g.fromSource}|${g.fromGroup}"].orEmpty().map { o ->
                AdminOp(
                    SyncType.GROUP_OVERRIDE, "${g.toSource}|${g.toGroup}", SyncScope.PROFILE, o.owner,
                    JsonObject(o.fields + fields("source_id" to g.toSource, "group_name" to g.toGroup)),
                )
            }
        }
    }
}
