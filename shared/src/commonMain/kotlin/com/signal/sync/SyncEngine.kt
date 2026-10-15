package com.signal.sync

import com.signal.data.Log
import com.signal.data.Settings
import com.signal.db.user.UserDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** How the engine reaches the companion: NasConnection on the device, a fake in tests. Null means unreachable. */
interface SyncTransport {
    suspend fun push(request: PushRequest): PushResult?
    suspend fun pull(since: Long): PullResult?
}

/**
 * Settings sync on the device (PLAN §5.5, §9.2). The local copy is always the source of truth: every
 * write lands in user.db at once, and a sync sends what differs from the state last agreed with the
 * companion (sync_base), field by field, then pulls what changed elsewhere. A field edited here and
 * not yet sent keeps its local value when a pull brings another; it's sent next, and wins, because
 * the companion orders writes by arrival (PLAN §5.5).
 */
class SyncEngine(
    private val db: UserDatabase,
    private val settings: Settings,
    private val tables: SyncTables,
    private val io: CoroutineDispatcher,
    private val log: Log,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()

    data class State(
        val lastSyncAt: Long? = null,
        val rev: Long? = null,
        val profileName: String? = null,
        val error: String? = null,
        val running: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** What a sync changed here, so the app can refresh new sources and follow guide changes. */
    data class Report(
        val pushed: Int,
        val pulled: Int,
        /** Sources that are new here or whose login or URL changed: they need a refresh. */
        val sourcesToRefresh: List<String>,
        val sourcesRemoved: Boolean,
        val epgChanged: Boolean,
        val profileChanged: Boolean,
    )

    /** Loads the saved state; call once at start. */
    suspend fun load() = withContext(io) {
        _state.value = State(
            lastSyncAt = stateValue(LAST_SYNC_AT)?.toLongOrNull(),
            rev = stateValue(REV)?.toLongOrNull(),
            profileName = stateValue(PROFILE_NAME),
        )
    }

    /** True when there's something here the companion hasn't seen. */
    suspend fun hasLocalChanges(): Boolean = withContext(io) { diff(tables.readAll(), base()).isNotEmpty() }

    /**
     * Push, then pull. Null when the companion couldn't be reached or refused; the changes stay
     * here and go next time (PLAN §9.2: "Nothing blocks on it").
     */
    /**
     * [full] asks for every item instead of the changes, which also removes anything agreed before that
     * the companion no longer has: the app does one at launch, so a missed change can never stick.
     */
    suspend fun sync(transport: SyncTransport, full: Boolean = false): Report? = lock.withLock {
        _state.value = _state.value.copy(running = true)
        try {
            runSync(transport, full).also { report ->
                _state.value = _state.value.copy(error = if (report == null) "Couldn't reach the NAS" else null)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("Sync failed: ${e.message}")
            _state.value = _state.value.copy(error = e.message ?: "Sync failed")
            null
        } finally {
            _state.value = _state.value.copy(running = false)
        }
    }

    private suspend fun runSync(transport: SyncTransport, full: Boolean): Report? {
        val since = withContext(io) { stateValue(REV)?.toLongOrNull() }
        val initial = since == null

        // Push: what differs from the agreed state.
        val (current, outgoing) = withContext(io) {
            val current = tables.readAll()
            current to diff(current, base())
        }
        if (outgoing.isNotEmpty() || initial) {
            val result = transport.push(PushRequest(BUNDLE_SCHEMA, outgoing, initial)) ?: return null
            withContext(io) {
                db.transaction {
                    for (op in outgoing) {
                        if (op.deleted) db.syncQueries.removeBase(op.type, op.scope, op.owner, op.id)
                        else current[op.key]?.let { putBase(it.key, it.fields) }
                    }
                }
            }
            log.info("Sync: sent ${outgoing.size} changes${if (initial) " (first sync)" else ""}, now at revision ${result.rev}")
        }

        // Pull: what changed elsewhere. A move to another profile starts over from revision 0.
        var pulled = transport.pull(if (full) 0 else since ?: 0) ?: return null
        var profileChanged = false
        if (pulled.profileId != settings.profileId) {
            val old = settings.profileId
            log.info("Sync: this device moved to the ${pulled.profileName} profile")
            withContext(io) {
                db.transaction {
                    tables.dropProfile(old)
                    db.syncQueries.removeBaseForScope(SyncScope.PROFILE)
                }
            }
            settings.put(Settings.Key.PROFILE_ID, pulled.profileId)
            profileChanged = true
            pulled = transport.pull(0) ?: return null
        }
        val report = withContext(io) { apply(pulled, outgoing.size, profileChanged) }
        val now = Clock.System.now().toEpochMilliseconds()
        withContext(io) {
            db.syncQueries.putState(REV, pulled.rev.toString())
            db.syncQueries.putState(LAST_SYNC_AT, now.toString())
            db.syncQueries.putState(PROFILE_NAME, pulled.profileName)
        }
        _state.value = _state.value.copy(lastSyncAt = now, rev = pulled.rev, profileName = pulled.profileName)
        if (report.pulled > 0) log.info("Sync: received ${report.pulled} changes, now at revision ${pulled.rev}")
        return report
    }

    private fun apply(pulled: PullResult, pushed: Int, profileChanged: Boolean): Report {
        val refresh = LinkedHashSet<String>()
        var sourcesRemoved = false
        var epgChanged = false
        var applied = 0
        db.transaction {
            val current = tables.readAll()
            val base = base()
            val seen = HashSet<SyncKey>()
            for (raw in pulled.items) {
                val item = local(raw)
                val key = item.key
                seen += key
                val mine = current[key]
                if (item.deleted) {
                    if (mine != null || base.containsKey(key)) {
                        tables.delete(item, base[key]?.fields ?: mine?.fields)
                        applied++
                        if (item.type == SyncType.SOURCE) sourcesRemoved = true
                        if (item.type == SyncType.EPG_SOURCE) epgChanged = true
                    }
                    db.syncQueries.removeBase(key.type, key.scope, key.owner, key.id)
                    continue
                }
                val agreed = base[key]
                // Fields changed here and not sent yet keep their local value.
                val merged = if (mine == null) item.fields else JsonObject(
                    item.fields + mine.fields.filter { (name, value) -> agreed == null || value != (agreed.fields[name] ?: JsonNull) },
                )
                if (mine?.fields == merged) {
                    putBase(key, item.fields)
                    continue
                }
                if (!tables.upsert(item, merged)) continue
                putBase(key, item.fields)
                applied++
                when (item.type) {
                    SyncType.SOURCE -> if (needsRefresh(mine?.fields, merged)) refresh += item.id
                    SyncType.EPG_SOURCE -> epgChanged = true
                }
            }
            // A full answer lists every live item: anything agreed before and missing now was deleted.
            if (pulled.full) {
                for ((key, agreedItem) in base) {
                    if (key in seen) continue
                    tables.delete(agreedItem, agreedItem.fields)
                    db.syncQueries.removeBase(key.type, key.scope, key.owner, key.id)
                    applied++
                    if (key.type == SyncType.SOURCE) sourcesRemoved = true
                    if (key.type == SyncType.EPG_SOURCE) epgChanged = true
                }
            }
        }
        return Report(pushed, applied, refresh.toList(), sourcesRemoved, epgChanged, profileChanged)
    }

    private fun needsRefresh(before: JsonObject?, after: JsonObject): Boolean {
        if (after.bool("enabled") == false) return false
        if (before == null) return true
        return listOf("kind", "url", "username", SyncFields.PASSWORD, "enabled").any { before[it] != after[it] }
    }

    /** Device-scope items come back with this device's ID as owner; here they're stored with none. */
    private fun local(item: SyncItem): SyncItem = if (item.scope == SyncScope.DEVICE && item.owner.isNotEmpty()) item.copy(owner = "") else item

    /** Changes from [base] to [current]: new and changed items with only their changed fields, and deletes. */
    private fun diff(current: Map<SyncKey, SyncItem>, base: Map<SyncKey, SyncItem>): List<SyncItem> = buildList {
        for ((key, item) in current) {
            val agreed = base[key]
            if (agreed == null) { add(item); continue }
            // Only fields this version reads: a field in the agreed copy but not in the rows came
            // from a newer version, and is left alone. Sending it back as a null (0.2.16 and
            // earlier) wiped the newer version's voice names everywhere (2026-10-15).
            val changed = item.fields.filter { (name, value) -> value != (agreed.fields[name] ?: JsonNull) }
            if (changed.isNotEmpty()) add(item.copy(fields = JsonObject(changed)))
        }
        for ((key, agreed) in base) if (key !in current) add(agreed.copy(fields = JsonObject(emptyMap()), deleted = true))
    }

    private fun base(): Map<SyncKey, SyncItem> = db.syncQueries.allBase().executeAsList().associate { row ->
        val item = SyncItem(row.type, row.id, row.scope, row.owner_id, runCatching { json.decodeFromString<JsonObject>(row.fields) }.getOrDefault(JsonObject(emptyMap())))
        item.key to item
    }

    private fun putBase(key: SyncKey, fields: JsonObject) =
        db.syncQueries.putBase(key.type, key.scope, key.owner, key.id, json.encodeToString(JsonObject.serializer(), fields))

    private fun stateValue(key: String): String? = db.syncQueries.state(key).executeAsOneOrNull()

    /** Unpairing: the next pairing is a first sync again (PLAN §5.5 → pairing a device that already has settings). */
    suspend fun reset() = lock.withLock {
        withContext(io) {
            db.transaction {
                db.syncQueries.clearBase()
                db.syncQueries.clearState()
            }
        }
        _state.value = State()
    }

    // ---- Settings file (PLAN §9.2 → Fallback) --------------------------------------------------

    suspend fun exportFile(): String = withContext(io) {
        val file = SettingsFile(exportedAt = Clock.System.now().toEpochMilliseconds(), items = tables.readAll().values.sortedWith(compareBy({ it.type }, { it.id })))
        json.encodeToString(SettingsFile.serializer(), file)
    }

    /**
     * Imports a settings file: its values win and nothing here is removed, the same rule as pairing
     * a device that already has settings (PLAN §5.5). Profile items land in this device's profile.
     * When paired, the next sync sends them on. Returns how many items it wrote.
     */
    suspend fun importFile(text: String): Result<Int> = withContext(io) {
        runCatching {
            val file = json.decodeFromString<SettingsFile>(text)
            require(file.format == SettingsFile.FORMAT) { "That isn't a Signal settings file" }
            require(file.schema <= BUNDLE_SCHEMA) { "That file is from a newer version of Signal. Update first." }
            var written = 0
            db.transaction {
                for (item in file.items) if (!item.deleted && tables.upsert(local(item), item.fields)) written++
            }
            log.info("Settings file imported: $written items")
            written
        }
    }

    companion object {
        private const val REV = "rev"
        private const val LAST_SYNC_AT = "last_sync_at"
        private const val PROFILE_NAME = "profile_name"
    }
}
