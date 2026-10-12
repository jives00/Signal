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
        if (type != SyncType.SOURCE || password.isNullOrEmpty()) return fields
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
        val guideBefore = if (request.items.any { it.touchesGuide() }) guideInputs() else null
        val written = ArrayList<StoredItem>()
        for (op in request.items) {
            val owner = when (op.scope) {
                SyncScope.HOUSEHOLD -> ""
                SyncScope.PROFILE -> device.profileId
                SyncScope.DEVICE -> device.id
                else -> throw SyncRefused(400, "Unknown scope ${op.scope}")
            }
            if (!TYPE.matches(op.type) || op.id.isEmpty() || op.id.length > 255) throw SyncRefused(400, "Bad item ${op.type}/${op.id}")
            val existing = store.get(op.type, op.scope, owner, op.id)
            val live = existing?.takeIf { !it.deleted }?.let { parse(it.fields) }
            val item = if (op.deleted) {
                if (live == null) continue
                StoredItem(op.type, op.scope, owner, op.id, existing!!.fields, true, 0, now)
            } else {
                // Field by field: what the device sent replaces only those fields (PLAN §5.5).
                val merged = JsonObject((live ?: JsonObject(emptyMap())) + seal(op.type, op.fields))
                if (merged == live) continue
                StoredItem(op.type, op.scope, owner, op.id, merged.toString(), false, 0, now)
            }
            written += item.copy(rev = nextRev(store))
        }
        store.write(written)
        companion.devices?.synced(device.id, now)
        if (written.isNotEmpty()) {
            log.info("${device.name} sent ${written.size} changes; now at revision $rev")
            companion.events.tryEmit(SyncEvent(SyncEvent.SETTINGS, rev = rev ?: 0))
            if (written.any { it.scope == SyncScope.HOUSEHOLD && (it.type == SyncType.SOURCE || it.type == SyncType.EPG_SOURCE) }) {
                val after = guideInputs()
                if (after != guideBefore) companion.guideJob.inputsChanged(after, device.name)
            }
        }
        PushResult(currentRev(), written.size)
    }

    private fun SyncItem.touchesGuide() = scope == SyncScope.HOUSEHOLD && (type == SyncType.SOURCE || type == SyncType.EPG_SOURCE)

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

    // ---- Backups (PLAN §9.3) ---------------------------------------------------------------------

    suspend fun snapshot(reason: String): SnapshotInfo = lock.withLock { snapshotLocked(store(), reason, System.currentTimeMillis()) }

    private fun snapshotLocked(store: SyncStore, reason: String, now: Long): SnapshotInfo {
        val items = store.all().filter { !it.deleted }
        val id = store.addSnapshot(now, reason, items.size, json.encodeToString(ListSerializer(StoredItem.serializer()), items))
        log.info("Snapshot: $reason (${items.size} items)")
        return SnapshotInfo(id, now, reason, items.size)
    }

    /**
     * Restores everything from a snapshot (PLAN §9.3): a snapshot of now first, then every item back to
     * its saved fields and everything newer deleted, each as a new revision so devices pull it.
     */
    suspend fun restore(id: Long): Int = lock.withLock {
        val store = store()
        val bundle = store.snapshotBundle(id) ?: throw SyncRefused(404, "No such snapshot")
        val saved = json.decodeFromString(ListSerializer(StoredItem.serializer()), bundle)
        val now = System.currentTimeMillis()
        snapshotLocked(store, "Before restoring a backup", now)
        val guideBefore = guideInputs()
        val key = { i: StoredItem -> listOf(i.type, i.scope, i.owner, i.id) }
        val savedByKey = saved.associateBy(key)
        val current = store.all().associateBy(key)
        val written = ArrayList<StoredItem>()
        for ((k, item) in current) {
            if (!item.deleted && k !in savedByKey) written += item.copy(deleted = true, rev = nextRev(store), updatedAt = now)
        }
        for ((k, item) in savedByKey) {
            val now0 = current[k]
            if (now0 == null || now0.deleted || now0.fields != item.fields) written += item.copy(deleted = false, rev = nextRev(store), updatedAt = now)
        }
        store.write(written)
        if (written.isNotEmpty()) companion.events.tryEmit(SyncEvent(SyncEvent.SETTINGS, rev = rev ?: 0))
        val after = guideInputs()
        if (after != guideBefore) companion.guideJob.inputsChanged(after, "a restored backup")
        log.info("Restored snapshot $id: ${written.size} changes")
        written.size
    }

    /** The whole bundle as a settings file, passwords readable (web admin → Export). */
    fun exportFile(): SettingsFile = SettingsFile(exportedAt = System.currentTimeMillis(), items = store().all().filter { !it.deleted }.map { it.toItem() })

    /** Nightly (PLAN §9.3): a snapshot, the oldest nightly ones pruned, tombstones over 90 days dropped. */
    suspend fun nightly() {
        val store = companion.syncStore ?: return
        snapshot(NIGHTLY)
        store.pruneSnapshots(NIGHTLY, 30)
        val dropped = lock.withLock { store.compact(System.currentTimeMillis() - TOMBSTONE_DAYS * 24 * 60 * 60_000L) }
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
        private const val TOMBSTONE_DAYS = 90L
        /** A settings event that always pulls: a profile change moves no revision. */
        const val FORCE = -1L
        private val TYPE = Regex("[a-z_]{1,40}")
    }
}
