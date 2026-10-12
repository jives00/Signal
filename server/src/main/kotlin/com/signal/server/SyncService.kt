package com.signal.server

import com.signal.nas.GuideInputs
import com.signal.sync.BUNDLE_SCHEMA
import com.signal.sync.PullResult
import com.signal.sync.PushRequest
import com.signal.sync.PushResult
import com.signal.sync.SettingsFile
import com.signal.sync.SyncEvent
import com.signal.sync.SyncFields
import com.signal.sync.SyncItem
import com.signal.sync.SyncScope
import com.signal.sync.SyncType
import com.signal.sync.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID

/** A refusal the device or web admin should see, with its HTTP status. */
class SyncRefused(val status: Int, message: String) : Exception(message)

/** The web admin's view of sync: the revision, what's stored, the profiles and the backups. */
@Serializable
data class SyncStatus(
    val rev: Long,
    /** Live items by type and scope, e.g. "favorite/profile" → 12. */
    val counts: Map<String, Int>,
    val profiles: List<ProfileSummary>,
    val snapshots: List<SnapshotInfo>,
)

@Serializable
data class ProfileSummary(val id: String, val name: String, val createdAt: Long, val devices: List<String>, val items: Int)

/**
 * Settings sync on the companion (PLAN §5.5): applies pushes field by field in arrival order under one
 * global revision, answers pulls, and keeps profiles and backups (PLAN §9.1, §9.3). One push at a time.
 */
class SyncService(private val companion: Companion) {
    private val log = LoggerFactory.getLogger("Sync")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var rev: Long? = null

    private fun store(): SyncStore = companion.syncStore ?: throw SyncRefused(503, "The database isn't connected yet.")

    private fun nextRev(store: SyncStore): Long = ((rev ?: store.maxRev()) + 1).also { rev = it }

    fun currentRev(): Long = companion.syncStore?.let { rev ?: it.maxRev() } ?: 0

    private fun checkSchema(schema: Int) {
        if (schema > BUNDLE_SCHEMA) throw SyncRefused(409, "This app is newer than the NAS companion. Update the companion.")
        if (schema < BUNDLE_SCHEMA - 1) throw SyncRefused(409, "This app is too old for the NAS companion. Update the app.")
    }

    // ---- Passwords at rest (PLAN §5.7) ------------------------------------------------------------

    private fun seal(type: String, fields: JsonObject): JsonObject {
        val password = fields.str(SyncFields.PASSWORD)
        if (type != SyncType.SOURCE || password.isNullOrEmpty() || password.startsWith(SEALED)) return fields
        val cipher = companion.cipher ?: throw SyncRefused(503, "SIGNAL_SECRET_KEY isn't set on the companion, so it can't store passwords.")
        return JsonObject(fields + (SyncFields.PASSWORD to JsonPrimitive(SEALED + cipher.encrypt(password))))
    }

    private fun open(type: String, fields: JsonObject): JsonObject {
        val password = fields.str(SyncFields.PASSWORD)
        if (type != SyncType.SOURCE || password == null || !password.startsWith(SEALED)) return fields
        val plain = runCatching { companion.cipher!!.decrypt(password.removePrefix(SEALED)) }
            .onFailure { log.error("A source password can't be decrypted (was SIGNAL_SECRET_KEY changed?)") }
            .getOrDefault("")
        return JsonObject(fields + (SyncFields.PASSWORD to JsonPrimitive(plain)))
    }

    private fun parse(fields: String): JsonObject = runCatching { json.decodeFromString<JsonObject>(fields) }.getOrDefault(JsonObject(emptyMap()))

    private fun StoredItem.toItem(): SyncItem = SyncItem(type, id, scope, owner, open(type, parse(fields)), deleted, rev, updatedAt)

    // ---- Device endpoints ------------------------------------------------------------------------

    suspend fun push(device: Device, request: PushRequest): PushResult = lock.withLock {
        checkSchema(request.schema)
        val store = store()
        val now = System.currentTimeMillis()
        if (request.initial && store.all().any { !it.deleted }) {
            // Pairing a device that already has settings: its values win, so keep a way back (PLAN §5.5).
            snapshotLocked(store, "Before ${device.name} joined", now)
        }
        val ops = request.items.map { op ->
            val owner = when (op.scope) {
                SyncScope.HOUSEHOLD -> ""
                SyncScope.PROFILE -> device.profileId
                SyncScope.DEVICE -> device.id
                else -> throw SyncRefused(400, "Unknown scope ${op.scope}")
            }
            AdminOp(op.type, op.id, op.scope, owner, op.fields, op.deleted)
        }
        val (written, _) = applyLocked(store, ops, device.name, now)
        companion.devices?.synced(device.id, now)
        PushResult(currentRev(), written)
    }

    /**
     * A web admin edit (PLAN §4.3.18 → Saving): the same field-by-field merge as a push, with each
     * op's owner given. Returns the ops that undo it, for the toast's Undo.
     */
    suspend fun edit(ops: List<AdminOp>, by: String = "the web admin"): EditResult = lock.withLock {
        val (changed, undo) = applyLocked(store(), ops, by, System.currentTimeMillis())
        EditResult(currentRev(), changed, undo)
    }

    /** Merges [ops] in order, writes them in one go, and tells everyone who needs to know. */
    private fun applyLocked(store: SyncStore, ops: List<AdminOp>, by: String, now: Long): Pair<Int, List<AdminOp>> {
        val guideBefore = if (ops.any { it.touchesGuide() }) guideInputs() else null
        // Later ops in the same batch see earlier ones (a new user channel, then its link).
        val pending = HashMap<List<String>, StoredItem>()
        val written = ArrayList<StoredItem>()
        val undo = ArrayList<AdminOp>()
        for (op in ops) {
            if (op.scope !in SCOPES) throw SyncRefused(400, "Unknown scope ${op.scope}")
            if (!TYPE.matches(op.type) || op.id.isEmpty() || op.id.length > 255) throw SyncRefused(400, "Bad item ${op.type}/${op.id}")
            val key = listOf(op.type, op.scope, op.owner, op.id)
            val existing = pending[key] ?: store.get(op.type, op.scope, op.owner, op.id)
            val live = existing?.takeIf { !it.deleted }?.let { parse(it.fields) }
            val item = if (op.deleted) {
                if (live == null) continue
                StoredItem(op.type, op.scope, op.owner, op.id, existing!!.fields, true, 0, now)
            } else {
                // Field by field: what arrived replaces only those fields (PLAN §5.5).
                val merged = JsonObject((live ?: JsonObject(emptyMap())) + seal(op.type, op.fields))
                if (merged == live) continue
                StoredItem(op.type, op.scope, op.owner, op.id, merged.toString(), false, 0, now)
            }
            undo += if (live == null) op.copy(fields = JsonObject(emptyMap()), deleted = true)
            else op.copy(fields = JsonObject(if (op.deleted) live else op.fields.keys.associateWith { live[it] ?: JsonNull }), deleted = false)
            val stored = item.copy(rev = nextRev(store))
            pending[key] = stored
            written += stored
        }
        store.write(written)
        if (written.isNotEmpty()) {
            itemsCache = null
            log.info("$by: ${written.size} changes; now at revision $rev")
            companion.events.tryEmit(SyncEvent(SyncEvent.SETTINGS, rev = rev ?: 0))
            if (written.any { it.scope == SyncScope.HOUSEHOLD && (it.type == SyncType.SOURCE || it.type == SyncType.EPG_SOURCE) }) {
                companion.onSourcesChanged()
                val after = guideInputs()
                if (after != guideBefore) companion.guideJob.inputsChanged(after, by)
            }
        }
        return written.size to undo.asReversed()
    }

    private fun AdminOp.touchesGuide() = scope == SyncScope.HOUSEHOLD && (type == SyncType.SOURCE || type == SyncType.EPG_SOURCE)

    @Volatile private var itemsCache: Pair<Long, List<SyncItem>>? = null

    /** Every live item, passwords readable: what the web admin's pages are built from. Cached per revision. */
    fun liveItems(): List<SyncItem> {
        val store = store()
        val r = currentRev()
        itemsCache?.takeIf { it.first == r }?.let { return it.second }
        return store.all().filter { !it.deleted }.map { it.toItem() }.also { itemsCache = r to it }
    }


    fun pull(device: Device, since: Long, schema: Int): PullResult {
        checkSchema(schema)
        val store = store()
        val compacted = companion.store?.setting(COMPACTED_REV)?.toLongOrNull() ?: 0
        val full = since <= 0 || since < compacted
        val items = if (full) store.live(device.profileId, device.id) else store.changedSince(since, device.profileId, device.id)
        val profile = store.profiles().firstOrNull { it.id == device.profileId } ?: Profile("main", "Main", 0)
        companion.devices?.synced(device.id, System.currentTimeMillis())
        return PullResult(BUNDLE_SCHEMA, currentRev(), full, profile.id, profile.name, items.map { it.toItem() })
    }

    // ---- The guide's inputs (PLAN §5.2): the household's sources and EPG sources -------------------

    fun guideInputs(): GuideInputs? {
        val store = companion.syncStore ?: return null
        val items = store.all().filter { !it.deleted && it.scope == SyncScope.HOUSEHOLD }
        val epg = items.filter { it.type == SyncType.EPG_SOURCE }.map { SyncFields.epgSource(it.id, parse(it.fields)) }.sortedBy { it.position }
        if (epg.isEmpty()) return null
        val needed = epg.mapNotNull { it.sourceId }.toSet()
        val sources = items.filter { it.type == SyncType.SOURCE && it.id in needed }.map { SyncFields.source(it.id, open(it.type, parse(it.fields))) }
        return GuideInputs(sources, epg)
    }

    // ---- Profiles (PLAN §9.1) --------------------------------------------------------------------

    fun status(): SyncStatus {
        val store = store()
        val all = store.all().filter { !it.deleted }
        val devices = companion.devices?.all().orEmpty()
        return SyncStatus(
            rev = currentRev(),
            counts = all.groupingBy { "${it.type}/${it.scope}" }.eachCount().toSortedMap(),
            profiles = store.profiles().map { p ->
                ProfileSummary(p.id, p.name, p.createdAt, devices.filter { it.profileId == p.id }.map { it.name }, all.count { it.scope == SyncScope.PROFILE && it.owner == p.id })
            },
            snapshots = store.snapshots(),
        )
    }

    /** A new profile, empty or a copy of another one (PLAN §9.1). */
    suspend fun createProfile(name: String, copyFrom: String?): Profile = lock.withLock {
        val store = store()
        val now = System.currentTimeMillis()
        val profile = Profile(UUID.randomUUID().toString(), name, now)
        store.saveProfile(profile)
        if (copyFrom != null) {
            val copied = store.ofOwner(SyncScope.PROFILE, copyFrom).filter { !it.deleted }.map { it.copy(owner = profile.id, rev = nextRev(store), updatedAt = now) }
            store.write(copied)
        }
        profile
    }

    fun renameProfile(id: String, name: String): Boolean {
        val store = store()
        val p = store.profiles().firstOrNull { it.id == id } ?: return false
        store.saveProfile(p.copy(name = name))
        companion.events.tryEmit(SyncEvent(SyncEvent.SETTINGS, rev = FORCE))
        return true
    }

    /** Main can't go. A deleted profile's devices move to Main, and pick that up on their next pull. */
    suspend fun deleteProfile(id: String): Boolean = lock.withLock {
        if (id == MAIN) throw SyncRefused(409, "Main can't be deleted.")
        val store = store()
        if (store.profiles().none { it.id == id }) return@withLock false
        companion.devices?.moveProfile(id, MAIN)
        store.deleteOwner(SyncScope.PROFILE, id)
        store.deleteProfile(id)
        itemsCache = null
        companion.events.tryEmit(SyncEvent(SyncEvent.SETTINGS, rev = FORCE))
        true
    }

    fun assignDevice(deviceId: String, profileId: String): Boolean {
        if (store().profiles().none { it.id == profileId }) throw SyncRefused(404, "No such profile")
        val ok = companion.devices?.setProfile(deviceId, profileId) == true
        // The device learns on its next pull; nudge it now.
        if (ok) companion.events.tryEmit(SyncEvent(SyncEvent.SETTINGS, rev = FORCE))
        return ok
    }

    /** "Give *device* its own profile" (PLAN §9.1): a copy of its current profile, named after it. */
    suspend fun ownProfile(device: Device): Profile {
        val profile = createProfile(device.name, copyFrom = device.profileId)
        assignDevice(device.id, profile.id)
        return profile
    }

    /**
     * Copy to… (PLAN §9.1): one profile's items of [types] into others. Merge keeps what the targets
     * have and overwrites the same items; replace also removes the targets' other items of those types.
     */
    suspend fun copySection(from: String, to: List<String>, types: Set<String>, replace: Boolean): EditResult {
        val items = liveItems().filter { it.scope == SyncScope.PROFILE && it.type in types }
        val source = items.filter { it.owner == from }
        val sourceIds = source.map { it.type to it.id }.toSet()
        val ops = to.filter { it != from }.flatMap { target ->
            val removals = if (!replace) emptyList() else items.filter { it.owner == target && (it.type to it.id) !in sourceIds }
                .map { AdminOp(it.type, it.id, it.scope, target, deleted = true) }
            removals + source.map { AdminOp(it.type, it.id, it.scope, target, it.fields) }
        }
        return edit(ops, "Copy to")
    }

    // ---- Backups (PLAN §9.3) ---------------------------------------------------------------------

    suspend fun snapshot(reason: String): SnapshotInfo = lock.withLock { snapshotLocked(store(), reason, System.currentTimeMillis()) }

    private fun snapshotLocked(store: SyncStore, reason: String, now: Long): SnapshotInfo {
        val items = store.all().filter { !it.deleted }
        val id = store.addSnapshot(now, reason, items.size, json.encodeToString(ListSerializer(StoredItem.serializer()), items))
        log.info("Snapshot: $reason (${items.size} items)")
        return SnapshotInfo(id, now, reason, items.size)
    }

    /** What a snapshot holds, for the restore summary (PLAN §9.3: counts such as sources and favorites). */
    fun snapshotSummary(id: Long): SnapshotSummary {
        val saved = savedItems(id)
        return SnapshotSummary(
            counts = saved.groupingBy { "${it.type}/${it.scope}" }.eachCount().toSortedMap(),
            profiles = saved.filter { it.scope == SyncScope.PROFILE }.map { it.owner }.distinct(),
            devices = saved.filter { it.scope == SyncScope.DEVICE }.map { it.owner }.distinct(),
        )
    }

    private fun savedItems(id: Long): List<StoredItem> {
        val bundle = store().snapshotBundle(id) ?: throw SyncRefused(404, "No such snapshot")
        return json.decodeFromString(ListSerializer(StoredItem.serializer()), bundle)
    }

    /**
     * Restores from a snapshot (PLAN §9.3): everything, one profile's items, or one device's. A
     * snapshot of now comes first; then each item in [part] goes back to its saved fields and anything
     * newer in [part] is deleted, each as a new revision so devices pull it.
     */
    suspend fun restore(id: Long, part: RestorePart = RestorePart.All): Int = lock.withLock {
        val store = store()
        val inPart: (StoredItem) -> Boolean = when (part) {
            RestorePart.All -> { _ -> true }
            is RestorePart.Profile -> { i -> i.scope == SyncScope.PROFILE && i.owner == part.id }
            is RestorePart.Device -> { i -> i.scope == SyncScope.DEVICE && i.owner == part.id }
        }
        val saved = savedItems(id).filter(inPart)
        val now = System.currentTimeMillis()
        snapshotLocked(store, "Before restoring a backup", now)
        val guideBefore = guideInputs()
        val key = { i: StoredItem -> listOf(i.type, i.scope, i.owner, i.id) }
        val savedByKey = saved.associateBy(key)
        val current = store.all().filter(inPart).associateBy(key)
        val written = ArrayList<StoredItem>()
        for ((k, item) in current) {
            if (!item.deleted && k !in savedByKey) written += item.copy(deleted = true, rev = nextRev(store), updatedAt = now)
        }
        for ((k, item) in savedByKey) {
            val was = current[k]
            if (was == null || was.deleted || was.fields != item.fields) written += item.copy(deleted = false, rev = nextRev(store), updatedAt = now)
        }
        store.write(written)
        itemsCache = null
        if (written.isNotEmpty()) companion.events.tryEmit(SyncEvent(SyncEvent.SETTINGS, rev = rev ?: 0))
        val after = guideInputs()
        if (after != guideBefore) {
            companion.onSourcesChanged()
            companion.guideJob.inputsChanged(after, "a restored backup")
        }
        log.info("Restored snapshot $id ($part): ${written.size} changes")
        written.size
    }

    /** The whole bundle as a settings file, passwords readable (web admin → Export). */
    fun exportFile(): SettingsFile = SettingsFile(exportedAt = System.currentTimeMillis(), items = liveItems())

    /**
     * Imports a settings file into the companion (PLAN §9.2 → Fallback), the pairing rule again: the
     * file's values win and nothing is removed. Profile items land in [profileId]; device items only
     * when that device is still paired. A snapshot comes first.
     */
    suspend fun importFile(file: SettingsFile, profileId: String): EditResult {
        if (file.format != SettingsFile.FORMAT) throw SyncRefused(400, "That isn't a Signal settings file.")
        checkSchema(file.schema)
        if (store().profiles().none { it.id == profileId }) throw SyncRefused(404, "No such profile")
        snapshot("Before importing a settings file")
        val devices = companion.devices?.all().orEmpty().map { it.id }.toSet()
        val ops = file.items.filter { !it.deleted }.mapNotNull { i ->
            when (i.scope) {
                SyncScope.HOUSEHOLD -> AdminOp(i.type, i.id, i.scope, "", i.fields)
                SyncScope.PROFILE -> AdminOp(i.type, i.id, i.scope, profileId, i.fields)
                SyncScope.DEVICE -> i.owner.takeIf { it in devices }?.let { AdminOp(i.type, i.id, i.scope, it, i.fields) }
                else -> null
            }
        }
        return edit(ops, "a settings file")
    }

    /** Nightly (PLAN §9.3): a snapshot, the oldest nightly ones pruned, old tombstones dropped. */
    suspend fun nightly() {
        val store = companion.syncStore ?: return
        snapshot(NIGHTLY)
        store.pruneSnapshots(NIGHTLY, companion.settings.snapshotsToKeep)
        val dropped = lock.withLock { store.compact(System.currentTimeMillis() - companion.settings.tombstoneDays * 24 * 60 * 60_000L) }
        if (dropped != null) {
            val previous = companion.store?.setting(COMPACTED_REV)?.toLongOrNull() ?: 0
            companion.store?.putSetting(COMPACTED_REV, maxOf(previous, dropped).toString())
        }
    }

    companion object Keys {
        const val MAIN = "main"
        const val NIGHTLY = "Nightly"
        private const val SEALED = "enc:"
        private const val COMPACTED_REV = "sync_compacted_rev"
        /** A settings event that always pulls: a profile change moves no revision. */
        const val FORCE = -1L
        private val TYPE = Regex("[a-z_]{1,40}")
        private val SCOPES = setOf(SyncScope.HOUSEHOLD, SyncScope.PROFILE, SyncScope.DEVICE)
    }
}

/** An edit from the web admin, or a push turned into one: the owner is always explicit. */
@Serializable
data class AdminOp(
    val type: String,
    val id: String,
    val scope: String,
    val owner: String = "",
    val fields: JsonObject = JsonObject(emptyMap()),
    val deleted: Boolean = false,
)

/** What an edit changed, and the ops that undo it. */
@Serializable
data class EditResult(val rev: Long, val changed: Int, val undo: List<AdminOp>)

@Serializable
data class SnapshotSummary(val counts: Map<String, Int>, val profiles: List<String>, val devices: List<String>)

sealed interface RestorePart {
    data object All : RestorePart
    data class Profile(val id: String) : RestorePart
    data class Device(val id: String) : RestorePart
}
