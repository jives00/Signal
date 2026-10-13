package com.signal.data

import com.signal.db.user.UserDatabase
import com.signal.match.ChannelMatcher
import com.signal.match.ProviderSwitch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The TV's simpler provider switch review (PLAN §4.5 → Switching providers, step 3: "The Shield has
 * a simpler version for when the NAS is down"): the same report as the web admin's, from user.db.
 * Accepting links a stream to the user channel; nothing is unlinked until the old source goes.
 */
class SwitchRepository(private val db: UserDatabase, private val settings: Settings, private val io: CoroutineDispatcher) {
    private fun stream(id: String, source: String, raw: String, tvg: String?, logo: String?, group: String) =
        ChannelMatcher.Stream(id, source, raw, tvg, logo, group)

    suspend fun report(targetSourceId: String): ProviderSwitch.Report = withContext(io) {
        val links = db.userChannelQueries.linksWithStreams().executeAsList()
        val profile = settings.profileId
        val favorites = db.syncQueries.allFavorites(profile).executeAsList().map { it.user_channel_id }.toSet()
        val names = db.syncQueries.allChannelProfiles(profile).executeAsList().associate { it.user_channel_id to it.custom_name }
        val userChannels = links.groupBy { it.user_channel_id }.map { (uc, rows) ->
            val streams = rows.map { stream(it.stream_id, it.source_id, it.raw_name, it.tvg_id, it.logo_url, it.group_name) }
            ProviderSwitch.UserChannel(uc, names[uc] ?: rows.first().raw_name, streams, uc in favorites)
        }
        val target = db.userChannelQueries.streamsForMatching(targetSourceId).executeAsList()
            .map { stream(it.id, it.source_id, it.raw_name, it.tvg_id, it.logo_url, it.group_name) }
        val rejected = db.matchQueries.allRejects().executeAsList().map { it.user_channel_id to it.stream_id }.toSet()
        val aliases = ChannelMatcher.aliases(db.matchQueries.allAliases().executeAsList().map { it.a to it.b })
        val groups = db.syncQueries.allGroupOverrides(profile).executeAsList().map { it.source_id to it.group_name }
        ProviderSwitch.report(targetSourceId, userChannels, target, links.map { it.stream_id }.toSet(), rejected, aliases, groups)
    }

    /**
     * Links [match]'s stream to [userChannelId]. A confirmed suggestion whose names differ teaches an
     * alias (PLAN §4.5: aliases "grow from the user's manual confirmations, which sync").
     */
    suspend fun accept(userChannelId: String, from: ChannelMatcher.Stream?, match: ChannelMatcher.Match) = withContext(io) {
        db.transaction {
            db.userChannelQueries.linkStream(userChannelId, match.stream.id, if (match.confidence == ChannelMatcher.Confidence.CONFIDENT) "auto" else "manual")
            if (from != null && match.confidence == ChannelMatcher.Confidence.SUGGESTED && ChannelMatcher.canonical(from.rawName) != ChannelMatcher.canonical(match.stream.rawName)) {
                val a = ChannelMatcher.canonical(match.stream.rawName)
                val b = ChannelMatcher.canonical(from.rawName)
                db.matchQueries.putAlias("$a|$b", a, b)
            }
        }
    }

    suspend fun acceptAll(proposals: List<ProviderSwitch.Proposal>) = proposals.forEach { accept(it.userChannel.id, null, it.best) }

    suspend fun reject(userChannelId: String, streamId: String) = withContext(io) { db.matchQueries.putReject(userChannelId, streamId) }
}
