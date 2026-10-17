package com.signal.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.signal.db.user.UserDatabase
import com.signal.demo.DemoData
import com.signal.demo.DemoVod
import com.signal.match.VodCategoryFilter
import com.signal.model.VodCategory
import com.signal.model.VodItem
import com.signal.model.VodKind
import com.signal.match.CharacterCleanup
import com.signal.model.CredentialMasking
import com.signal.model.LiveFormat
import com.signal.model.ProviderStream
import com.signal.model.Source
import com.signal.model.SourceKind
import com.signal.parse.JsonArrayObjects
import com.signal.parse.M3uParser
import com.signal.parse.XtreamParser
import com.signal.platform.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** Sources and their cached channel lists (PLAN Â§6). Credentials are encrypted at rest (PLAN Â§5.7). */
class SourceRepository(
    private val db: UserDatabase,
    private val http: HttpClient,
    private val secrets: SecretBox,
    private val io: CoroutineDispatcher,
    private val log: Log,
    /** Movie and series categories to leave out of the catalog (PLAN §4.3.6 → Categories). */
    private val vodFilter: () -> VodCategoryFilter = { VodCategoryFilter.NONE },
) {
    /** Every source of the household, the ones this device's profile leaves out marked [Source.hiddenHere]. */
    fun sources(): Flow<List<Source>> = combine(
        db.sourceQueries.all().asFlow().mapToList(io),
        db.sourceQueries.hiddenHere().asFlow().mapToList(io),
    ) { rows, hidden -> rows.map { it.toModel(it.id in hidden) } }

    suspend fun all(): List<Source> = withContext(io) {
        val hidden = hiddenHere()
        db.sourceQueries.all().executeAsList().map { it.toModel(it.id in hidden) }
    }

    /** The sources this device shows and refreshes ([Source.active]). */
    suspend fun active(): List<Source> = all().filter { it.active }

    suspend fun byId(id: String): Source? = withContext(io) { db.sourceQueries.byId(id).executeAsOneOrNull()?.let { it.toModel(it.id in hiddenHere()) } }

    private fun hiddenHere(): Set<String> = db.sourceQueries.hiddenHere().executeAsList().toSet()

    suspend fun count(): Long = withContext(io) { db.sourceQueries.count().executeAsOne() }

    suspend fun save(source: Source) = withContext(io) {
        val existing = db.sourceQueries.byId(source.id).executeAsOneOrNull()
        db.sourceQueries.upsert(
            com.signal.db.user.Source(
                id = source.id,
                name = source.name,
                kind = source.kind.name,
                url = source.url.trim(),
                username = source.username.trim(),
                password_enc = if (source.password.isEmpty()) "" else secrets.encrypt(source.password),
                user_agent = source.userAgent,
                referrer = source.referrer,
                enabled = if (source.enabled) 1 else 0,
                live_format = source.liveFormat.name,
                connection_limit = source.connectionLimit.toLong(),
                expires_at = existing?.expires_at,
                provider_connections = existing?.provider_connections,
                last_refreshed_at = existing?.last_refreshed_at ?: 0,
                last_error = existing?.last_error,
                created_at = existing?.created_at ?: Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    suspend fun delete(id: String) = withContext(io) {
        db.transaction {
            db.vodQueries.deleteAllForSource(id)
            db.streamQueries.deleteForSource(id)
            db.sourceQueries.delete(id)
        }
    }

    /** Re-downloads and re-parses a source's channel list (PLAN Â§2.1 "Source refresh"). */
    suspend fun refresh(id: String): Result<Int> = withContext(io) {
        val source = byId(id) ?: return@withContext Result.failure(IllegalArgumentException("No source $id"))
        runCatching {
            // The parsed channels are dropped once saved, before the much longer movie and series lists: 57k
            // of them held through those added to the peak that ran the Shield out of memory (0.2.8).
            val (count, demoVod) = load(source).let { loaded ->
                db.transaction {
                    db.streamQueries.deleteForSource(source.id)
                    val removeEmoji = true
                    loaded.streams.forEach { s -> db.streamQueries.insert(s.toRow(removeEmoji)) }
                    db.sourceQueries.markRefreshed(
                        Clock.System.now().toEpochMilliseconds(),
                        loaded.expiresAt,
                        loaded.connectionLimit?.toLong(),
                        source.id,
                    )
                }
                loaded.streams.size to loaded.vod
            }
            // Movies and series after the channels: a failure there keeps the channels and the old catalog.
            val vod = refreshVod(source, demoVod)
            log.info("Refreshed ${source.name}: $count streams" + (vod?.let { (m, s) -> ", $m movies, $s series" } ?: ""))
            count
        }.onFailure { e ->
            log.warn("Refresh of ${source.name} failed: ${e.message}")
            db.sourceQueries.markFailed(e.message ?: e::class.simpleName, source.id)
        }
    }

    /** Settings â Sources â Test connection: logs in or downloads, without saving anything. */
    suspend fun test(source: Source): Result<String> = withContext(io) {
        runCatching {
            val loaded = load(source)
            buildString {
                append("${loaded.streams.size} channels")
                loaded.connectionLimit?.let { append(" · $it connections") }
            }
        }
    }

    private class Loaded(
        val streams: List<ProviderStream>,
        val expiresAt: Long? = null,
        val connectionLimit: Int? = null,
        /** Movies and series (PLAN §4.3.6): null when the source has none or they failed, which keeps the old ones. */
        val vod: Vod? = null,
    )

    private class Vod(val categories: List<VodCategory>, val items: List<VodItem>)

    private suspend fun load(source: Source): Loaded = when (source.kind) {
        SourceKind.DEMO -> Loaded(
            DemoData.streams(source.id),
            vod = Vod(
                DemoVod.categories(source.id, VodKind.MOVIE) + DemoVod.categories(source.id, VodKind.SERIES),
                DemoVod.movies(source.id) + DemoVod.series(source.id),
            ),
        )
        SourceKind.M3U -> Loaded(loadM3u(source))
        SourceKind.XTREAM -> loadXtream(source)
    }

    private suspend fun loadM3u(source: Source): List<ProviderStream> {
        val response = http.get(source.url) { source.headers.forEach { (k, v) -> header(k, v) } }
        response.ensureOk(source)
        // Streamed line by line: a 50k-entry list is never held as one string (PLAN Â§6).
        val parser = M3uParser(source)
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readLine() ?: break
            parser.line(line)
        }
        return parser.result()
    }

    private suspend fun loadXtream(source: Source): Loaded {
        val base = source.url.trimEnd('/') + "/player_api.php"
        suspend fun call(action: String?): String {
            val response = http.get(base) {
                parameter("username", source.username)
                parameter("password", source.password)
                if (action != null) parameter("action", action)
                source.headers.forEach { (k, v) -> header(k, v) }
            }
            response.ensureOk(source)
            return response.bodyAsText()
        }
        val account = XtreamParser.accountInfo(call(null))
        if (!account.authenticated) error("Login failed: check the username and password")
        val categories = XtreamParser.categories(call("get_live_categories"))
        // Read as it downloads, like the movie lists: whole, it ran the Shield out of memory (0.2.8).
        val live = XtreamParser.LiveStreams(source, categories)
        streamList(source, "get_live_streams") { obj, _ -> live.add(obj) }
        return Loaded(live.streams, account.expiresAt, account.maxConnections)
    }

    /**
     * Movies and series (PLAN §4.3.6–4.3.7), after the channels. A provider's lists can be hundreds of
     * thousands of titles (75 MB of JSON), so they stream into staging a batch at a time and swap in
     * whole once all have arrived. Null when the source has none, or they failed: that keeps the old
     * catalog. Returns the movie and series counts.
     */
    private suspend fun refreshVod(source: Source, demo: Vod?): Pair<Int, Int>? {
        if (source.kind == SourceKind.M3U) return null
        return runCatching {
            db.vodQueries.clearStaging(source.id)
            var movies = 0
            var series = 0
            val batch = ArrayList<VodItem>(STAGING_BATCH)
            fun flush() {
                if (batch.isEmpty()) return
                db.transaction { batch.forEach { db.vodQueries.insertStaging(it.toStagingRow()) } }
                batch.clear()
            }
            fun cats(m: Map<String, String>, kind: VodKind) = m.entries.mapIndexed { i, (id, name) -> VodCategory(source.id, kind, id, name, i) }
            val categories = demo?.categories
                ?: (cats(XtreamParser.categories(xtream(source, "get_vod_categories")), VodKind.MOVIE) +
                    cats(XtreamParser.categories(xtream(source, "get_series_categories")), VodKind.SERIES))
            // Hidden categories' titles never reach staging: the filter matches the names as stored.
            val filter = vodFilter()
            val hidden = categories.filter { filter.hides(it.toRow().name) }.map { it.kind to it.categoryId }.toSet()
            var skipped = 0
            fun add(item: VodItem) {
                if ((item.kind to item.categoryId) in hidden) { skipped++; return }
                batch += item
                if (item.kind == VodKind.MOVIE) movies++ else series++
                if (batch.size >= STAGING_BATCH) flush()
            }
            if (demo != null) {
                demo.items.forEach(::add)
            } else {
                streamList(source, "get_vod_streams") { obj, i -> XtreamParser.movie(source, obj, i)?.let(::add) }
                streamList(source, "get_series") { obj, i -> XtreamParser.seriesItem(source, obj, i)?.let(::add) }
            }
            flush()
            if (skipped > 0) log.info("Left out $skipped titles in ${hidden.size} hidden categories")
            // Providers answer the big lists with an empty `[]` at times (the user's did on 2026-10-13, while
            // its live list came through). That's a failed refresh, not an emptied catalog.
            if (movies + series == 0 && db.vodQueries.countForSource(source.id).executeAsOne() > 0) {
                error("no movies or series in the answer; keeping the ones from before")
            }
            db.transaction {
                db.vodQueries.swapInStaging(source.id)
                categories.forEach { db.vodQueries.insertCategory(it.toRow()) }
            }
            movies to series
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            runCatching { db.vodQueries.clearStaging(source.id) }
            log.warn("Movies and series from ${source.name} failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * Movies and series alone, for an Xtream source that has none: a refresh whose lists failed (0.2.2 ran
     * out of memory on large catalogs) left the channels fresh, so the 24-hour refresh won't retry them.
     */
    suspend fun refreshVodIfMissing(id: String): Pair<Int, Int>? = withContext(io) {
        val source = byId(id)?.takeIf { it.active && it.kind == SourceKind.XTREAM } ?: return@withContext null
        if (db.vodQueries.countForSource(source.id).executeAsOne() > 0) return@withContext null
        refreshVod(source, null)?.also { (m, s) -> log.info("Loaded ${source.name}'s movies and series: $m movies, $s series") }
    }

    /** A `player_api.php` list read as it downloads, one object at a time ([JsonArrayObjects]). */
    private suspend fun streamList(source: Source, action: String, onObject: (kotlinx.serialization.json.JsonObject, Int) -> Unit) {
        // prepareGet + execute: a plain get would buffer the whole body before handing it over.
        http.prepareGet(source.url.trimEnd('/') + "/player_api.php") {
            parameter("username", source.username)
            parameter("password", source.password)
            parameter("action", action)
            source.headers.forEach { (k, v) -> header(k, v) }
        }.execute { response ->
            response.ensureOk(source)
            var i = 0
            val splitter = JsonArrayObjects { text -> XtreamParser.objectOf(text)?.let { onObject(it, i++) } }
            val channel = response.bodyAsChannel()
            val buf = ByteArray(64 * 1024)
            var bytes = 0L
            var first = 0.toByte()
            while (true) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0 && first == 0.toByte()) first = (0 until n).firstOrNull { !buf[it].toInt().toChar().isWhitespace() }?.let { buf[it] } ?: 0
                bytes += n
                if (n > 0) splitter.feed(buf, n)
            }
            // Never the body itself: an Xtream error answer can carry the login.
            if (i == 0) log.warn("$action from ${source.name}: no titles in $bytes bytes (starts with '${first.toInt().toChar()}', ${response.status.value})")
        }
    }

    /** One Xtream `player_api.php` call with the source's login, for detail pages (get_vod_info, get_series_info). */
    suspend fun xtream(source: Source, action: String, vararg params: Pair<String, String>): String = withContext(io) {
        val response = http.get(source.url.trimEnd('/') + "/player_api.php") {
            parameter("username", source.username)
            parameter("password", source.password)
            parameter("action", action)
            params.forEach { (k, v) -> parameter(k, v) }
            source.headers.forEach { (k, v) -> header(k, v) }
        }
        response.ensureOk(source)
        response.bodyAsText()
    }

    private fun HttpResponse.ensureOk(source: Source) {
        if (!status.isSuccess()) {
            // The URL is never logged whole: it can embed credentials (PLAN Â§1).
            error("HTTP ${status.value} from ${CredentialMasking.forLog(call.request.url.toString(), source)}")
        }
    }

    private fun com.signal.db.user.Source.toModel(hiddenHere: Boolean) = Source(
        id = id,
        name = name,
        kind = runCatching { SourceKind.valueOf(kind) }.getOrDefault(SourceKind.M3U),
        url = url,
        username = username,
        password = if (password_enc.isEmpty()) "" else runCatching { secrets.decrypt(password_enc) }.getOrDefault(""),
        userAgent = user_agent,
        referrer = referrer,
        enabled = enabled == 1L,
        liveFormat = runCatching { LiveFormat.valueOf(live_format) }.getOrDefault(LiveFormat.TS),
        connectionLimit = connection_limit.toInt(),
        lastRefreshedAt = last_refreshed_at,
        lastError = last_error,
        providerConnections = provider_connections?.toInt(),
        expiresAt = expires_at,
        hiddenHere = hiddenHere,
    )

    private fun VodCategory.toRow() = com.signal.db.user.Vod_category(sourceId, kind.name, categoryId, CharacterCleanup.clean(name, true).ifEmpty { name }, position.toLong())

    private fun VodItem.toStagingRow(): com.signal.db.user.Vod_item_staging {
        val (titleKey, searchName) = VodRepository.keys(name)
        return com.signal.db.user.Vod_item_staging(
            id, sourceId, kind.name, providerId, name, categoryId, posterUrl, year?.toLong(), rating, addedAt, tmdbId, containerExt, plot, url, position.toLong(), titleKey, searchName,
        )
    }

    private fun ProviderStream.toRow(removeEmoji: Boolean) = com.signal.db.user.Stream(
        id = id,
        source_id = sourceId,
        provider_id = providerId,
        raw_name = rawName,
        display_name = CharacterCleanup.clean(rawName, removeEmoji).ifEmpty { rawName },
        group_name = CharacterCleanup.clean(groupName, removeEmoji).ifEmpty { groupName },
        logo_url = logoUrl,
        tvg_id = tvgId,
        provider_number = providerNumber?.toLong(),
        position = position.toLong(),
        url = url,
        headers = if (headers.isEmpty()) null else Json.encodeToString(headers),
    )

    private companion object Limits {
        /** Titles written to staging per transaction. */
        const val STAGING_BATCH = 1_000
    }
}
