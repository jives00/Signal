package com.signal.server

import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.nas.NasPaths
import com.signal.nas.NowPlaying
import com.signal.nas.PlayKind
import com.signal.parse.XtreamParser
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Who's using each source right now (source-activity.md): TVs report what they play, the viewer and
 * the web admin's Watch page are known when they're handed a stream, and recordings are the
 * companion's own. Kept in memory only: after a restart, TVs report again within a minute.
 */
class SourceActivity(private val now: () -> Long = System::currentTimeMillis, private val quietMs: Long = QUIET_MS) {
    enum class Who { TV, VIEWER, WEB }

    data class Use(val who: Who, val name: String, val profileId: String, val playing: NowPlaying, val seenAt: Long)

    private val uses = ConcurrentHashMap<String, Use>()

    /** [key] plays [playing], or stopped when its source is null. */
    fun report(key: String, who: Who, name: String, profileId: String, playing: NowPlaying) {
        if (playing.sourceId == null) uses.remove(key) else uses[key] = Use(who, name, profileId, playing, now())
    }

    /** A heartbeat: still playing the same thing. */
    fun seen(key: String) {
        uses.computeIfPresent(key) { _, u -> u.copy(seenAt = now()) }
    }

    /** Stopped [streamId], or whatever it plays when null. A newer play that got here first is left alone. */
    fun stopped(key: String, streamId: String?) {
        uses.computeIfPresent(key) { _, u -> if (streamId == null || u.playing.streamId == streamId) null else u }
    }

    /** What's in use now. A device that went quiet, by crashing or losing its network, drops out. */
    fun current(): List<Use> {
        val cutoff = now() - quietMs
        uses.values.removeIf { it.seenAt < cutoff }
        return uses.values.toList()
    }

    companion object Limits {
        /** Past two missed heartbeats from a TV (one a minute); the browser's come every 30 s. */
        const val QUIET_MS = 150_000L
    }
}

@Serializable
data class ActivityView(val sources: List<SourceActivityView>)

@Serializable
data class SourceActivityView(
    val id: String,
    val name: String,
    /** Streams it may play at once (`Source.connections`), if known. */
    val limit: Int?,
    val logins: List<LoginActivityView>,
)

@Serializable
data class LoginActivityView(
    val id: String,
    val name: String,
    /** What the provider says now; null when it didn't answer, or for a playlist, which has nothing to ask. */
    val providerActive: Int? = null,
    val providerLimit: Int? = null,
    val uses: List<UseView> = emptyList(),
)

@Serializable
data class UseView(
    /** TV, VIEWER, WEB or RECORDING. */
    val who: String,
    /** The device's name, or the web admin. */
    val name: String,
    val kind: PlayKind,
    /** The channel, movie or series. */
    val title: String?,
    /** An episode's code and name. */
    val subtitle: String? = null,
    /** Live TV: the program on now, from the guide; a recording's program. */
    val program: String? = null,
    val paused: Boolean = false,
    val since: Long,
    val recordingId: String? = null,
)

/** Builds [ActivityView]. The provider's counts are cached briefly: the dashboard asks every few seconds. */
class ActivityViews(
    private val companion: Companion,
    private val channels: AdminChannels,
    private val accountInfo: suspend (Source) -> XtreamParser.AccountInfo?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val counts = ConcurrentHashMap<String, Pair<Long, XtreamParser.AccountInfo?>>()
    private val programs = ConcurrentHashMap<String, Pair<Long, String?>>()

    suspend fun view(): ActivityView = coroutineScope {
        val sources = AdminView(companion.sync.liveItems(), SyncService.MAIN).sources.map { it.first }.filter { it.enabled }
        val placed = companion.activity.current().mapNotNull { u -> place(sources, u) } +
            companion.recorder.connected().map { (r, key) ->
                key to UseView("RECORDING", "Recording", PlayKind.LIVE, r.channelName, program = r.title, since = r.startMs, recordingId = r.id)
            }
        val seen = HashSet<String>()
        val logins = sources.map { s -> s to s.logins.filter { seen.add(key(s, it.id)) } }
        val info = logins.flatMap { (s, ls) -> ls.map { l -> async { key(s, l.id) to providerCount(s, l.id) } } }.awaitAll().toMap()
        ActivityView(
            logins.map { (s, ls) ->
                SourceActivityView(
                    s.id, s.name, s.connections,
                    ls.map { l ->
                        val k = key(s, l.id)
                        LoginActivityView(l.id, l.name, info[k]?.activeConnections, info[k]?.maxConnections, placed.filter { it.first == k }.map { it.second }.sortedBy { it.since })
                    },
                )
            },
        )
    }

    private suspend fun place(sources: List<Source>, u: SourceActivity.Use): Pair<String, UseView>? {
        val p = u.playing
        val source = sources.firstOrNull { it.id == p.sourceId } ?: return null
        val login = source.logins.firstOrNull { it.id == p.accountId } ?: source.logins.first()
        val program = if (p.kind == PlayKind.LIVE) p.streamId?.let { program(u.profileId, it) } else null
        return key(source, login.id) to UseView(u.who.name, u.name, p.kind, p.title, p.subtitle, program, p.paused, p.since)
    }

    /** A login's identity: two sources on one username share its streams. A playlist has only itself. */
    private fun key(source: Source, loginId: String): String {
        if (source.kind != SourceKind.XTREAM) return source.id
        val login = source.logins.firstOrNull { it.id == loginId } ?: source.logins.first()
        return source.withLogin(login).username
    }

    private suspend fun providerCount(source: Source, loginId: String): XtreamParser.AccountInfo? {
        if (source.kind != SourceKind.XTREAM) return null
        val k = key(source, loginId)
        counts[k]?.takeIf { now() - it.first < COUNT_CACHE_MS }?.let { return it.second }
        val login = source.logins.first { it.id == loginId }
        val answer = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { runCatching { accountInfo(source.withLogin(login)) }.getOrNull() }
        counts[k] = now() to answer
        return answer
    }

    /** The program on now. The guide's first read after a restart is slow, so it gives up rather than hold the page. */
    private suspend fun program(profileId: String, streamId: String): String? {
        val k = "$profileId/$streamId"
        programs[k]?.takeIf { now() - it.first < PROGRAM_CACHE_MS }?.let { return it.second }
        val title = withTimeoutOrNull(PROGRAM_TIMEOUT_MS) { withContext(Dispatchers.IO) { runCatching { channels.channel(profileId, streamId, now())?.now?.title }.getOrNull() } }
        programs[k] = now() to title
        return title
    }

    private companion object Timing {
        const val COUNT_CACHE_MS = 20_000L
        const val PROVIDER_TIMEOUT_MS = 5_000L
        const val PROGRAM_CACHE_MS = 60_000L
        const val PROGRAM_TIMEOUT_MS = 3_000L
    }
}

/** TVs report what they play. On the main port only: the viewer's own reports come with its stream. */
fun Route.activityDeviceRoutes(companion: Companion) {
    post(NasPaths.PLAYING) {
        val device = call.device(companion) ?: return@post
        companion.activity.report(device.id, SourceActivity.Who.TV, device.name, device.profileId, call.receive<NowPlaying>())
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Inside /api: the dashboard's cards. */
fun Route.activityAdminRoutes(companion: Companion) {
    val views = ActivityViews(companion, AdminChannels(companion), { companion.catalog.accountInfo(it) })
    get("/activity") { call.respond(views.view()) }
}
