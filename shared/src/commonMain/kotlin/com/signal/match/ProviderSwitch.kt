package com.signal.match

/**
 * The provider switch report (PLAN §4.5 → Switching providers): for a new source added beside the
 * old one, which of the user's channels have a confident match there, which need a look, and which
 * would become unavailable. Nothing is linked here; the review accepts, and the user disables the
 * old source last. Pure, so the NAS and the TV build the same report.
 */
object ProviderSwitch {
    /** A user channel with the streams it links to today (any source still in the catalog). */
    data class UserChannel(val id: String, val name: String, val streams: List<ChannelMatcher.Stream>, val favorite: Boolean = false)

    data class Proposal(val userChannel: UserChannel, val matches: List<ChannelMatcher.Match>) {
        val best: ChannelMatcher.Match get() = matches.first()
    }

    /** A provider group with the user's settings, and its likely counterpart on the new source. */
    data class GroupProposal(val sourceId: String, val group: String, val toGroup: String?)

    data class Report(
        val targetSourceId: String,
        val confident: List<Proposal>,
        val suggested: List<Proposal>,
        /** No match on the new source: they'd become unavailable once the old source goes. */
        val unmatched: List<UserChannel>,
        /** Already linked to a stream on the new source. */
        val alreadyLinked: Int,
        val groups: List<GroupProposal>,
    )

    fun report(
        targetSourceId: String,
        userChannels: List<UserChannel>,
        targetStreams: List<ChannelMatcher.Stream>,
        /** Streams already linked to some user channel: a stream belongs to at most one. */
        linkedStreamIds: Set<String>,
        /** (user channel, stream) pairs the user rejected. */
        rejected: Set<Pair<String, String>> = emptySet(),
        aliases: Map<String, String> = ChannelMatcher.builtInAliases,
        /** Groups with settings on other sources, as (source, group). */
        groupsWithSettings: List<Pair<String, String>> = emptyList(),
    ): Report {
        val index = ChannelMatcher.Index(targetStreams, aliases)
        var already = 0
        val proposals = ArrayList<Proposal>()
        val unmatched = ArrayList<UserChannel>()
        for (uc in userChannels) {
            if (uc.streams.any { it.sourceId == targetSourceId }) { already++; continue }
            val refs = uc.streams.filter { it.sourceId != targetSourceId }
            if (refs.isEmpty()) continue
            val matches = refs.flatMap { ref -> index.matches(ref, limit = 3) { it.id in linkedStreamIds || (uc.id to it.id) in rejected } }
                .groupBy { it.stream.id }.map { (_, ms) -> ms.minWith(compareBy<ChannelMatcher.Match> { it.confidence }.thenByDescending { it.score }) }
                .sortedWith(compareBy<ChannelMatcher.Match> { it.confidence }.thenByDescending { it.score }).take(3)
            if (matches.isEmpty()) unmatched += uc else proposals += Proposal(uc, matches)
        }
        // One stream can join one user channel: the strongest claim keeps it confident, the rest go to review.
        val taken = HashSet<String>()
        val confident = ArrayList<Proposal>()
        val suggested = ArrayList<Proposal>()
        for (p in proposals.sortedByDescending { it.best.score }) {
            if (p.best.confidence == ChannelMatcher.Confidence.CONFIDENT && taken.add(p.best.stream.id)) confident += p else suggested += p
        }
        return Report(
            targetSourceId,
            confident.sortedBy { it.userChannel.name.lowercase() },
            suggested.sortedWith(compareBy({ !it.userChannel.favorite }, { it.userChannel.name.lowercase() })),
            unmatched.sortedWith(compareBy({ !it.favorite }, { it.name.lowercase() })),
            already,
            groups(groupsWithSettings.filter { it.first != targetSourceId }, targetStreams.map { it.groupName }.distinct()),
        )
    }

    /** Provider groups match by normalized name, as suggestions (PLAN §4.5 → Provider groups). */
    fun groups(withSettings: List<Pair<String, String>>, targetGroups: List<String>): List<GroupProposal> {
        val byName = targetGroups.groupBy { GuideNames.normalize(it) }
        return withSettings.distinct().map { (source, group) ->
            val n = GuideNames.normalize(group)
            val exact = byName[n]?.firstOrNull()
            val close = exact ?: run {
                val words = n.split(' ').filter { it.isNotEmpty() }.toSet()
                targetGroups.map { g ->
                    val gw = GuideNames.normalize(g).split(' ').filter { it.isNotEmpty() }.toSet()
                    g to if (words.isEmpty() || gw.isEmpty()) 0.0 else words.intersect(gw).size.toDouble() / (words + gw).size
                }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
            }
            GroupProposal(source, group, close)
        }
    }
}
