package com.signal.server

import kotlinx.serialization.Serializable
import javax.sql.DataSource

/** One synced item as stored: fields as JSON, with source passwords encrypted (PLAN §5.7). */
@Serializable
data class StoredItem(
    val type: String,
    val scope: String,
    val owner: String,
    val id: String,
    val fields: String,
    val deleted: Boolean,
    val rev: Long,
    val updatedAt: Long,
)

@Serializable
data class Profile(val id: String, val name: String, val createdAt: Long)

@Serializable
data class SnapshotInfo(val id: Long, val createdAt: Long, val reason: String, val items: Int)

/** V3's sync_item, profile and snapshot tables, behind an interface so the routes are testable without MySQL. */
interface SyncStore {
    fun maxRev(): Long
    fun get(type: String, scope: String, owner: String, id: String): StoredItem?
    /** Writes every item, in one transaction. */
    fun write(items: List<StoredItem>)
    /** Changes after [rev] (tombstones included) for one device's household, profile and device scopes. */
    fun changedSince(rev: Long, profileId: String, deviceId: String): List<StoredItem>
    /** Every live item for one device's scopes. */
    fun live(profileId: String, deviceId: String): List<StoredItem>
    fun all(): List<StoredItem>
    fun ofOwner(scope: String, owner: String): List<StoredItem>
    fun deleteOwner(scope: String, owner: String)
    /** Drops tombstones last changed before [before]; returns the highest revision dropped, or null. */
    fun compact(before: Long): Long?

    fun profiles(): List<Profile>
    fun saveProfile(profile: Profile)
    fun deleteProfile(id: String)

    fun addSnapshot(at: Long, reason: String, items: Int, bundle: String): Long
    fun snapshots(): List<SnapshotInfo>
    fun snapshotBundle(id: Long): String?
    /** Keeps the newest [keep] snapshots with [reason]; others are kept until deleted (PLAN §9.3). */
    fun pruneSnapshots(reason: String, keep: Int)
    fun deleteSnapshot(id: Long): Boolean
}

class JdbcSyncStore(private val db: DataSource) : SyncStore {
    private val columns = "type, scope, owner_id, id, fields, deleted, rev, updated_at"

    private fun java.sql.ResultSet.toItem() = StoredItem(
        getString("type"), getString("scope"), getString("owner_id"), getString("id"), getString("fields"),
        getInt("deleted") != 0, getLong("rev"), getLong("updated_at"),
    )

    private fun query(sql: String, bind: java.sql.PreparedStatement.() -> Unit = {}): List<StoredItem> = db.connection.use { c ->
        c.prepareStatement(sql).use { s ->
            s.bind()
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.toItem()) } }
        }
    }

    private fun update(sql: String, bind: java.sql.PreparedStatement.() -> Unit): Int =
        db.connection.use { c -> c.prepareStatement(sql).use { s -> s.bind(); s.executeUpdate() } }

    override fun maxRev(): Long = db.connection.use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT COALESCE(MAX(rev), 0) FROM sync_item").use { r -> r.next(); r.getLong(1) } }
    }

    override fun get(type: String, scope: String, owner: String, id: String) =
        query("SELECT $columns FROM sync_item WHERE type = ? AND scope = ? AND owner_id = ? AND id = ?") {
            setString(1, type); setString(2, scope); setString(3, owner); setString(4, id)
        }.firstOrNull()

    override fun write(items: List<StoredItem>) {
        if (items.isEmpty()) return
        db.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(
                    "INSERT INTO sync_item ($columns) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                        "fields = VALUES(fields), deleted = VALUES(deleted), rev = VALUES(rev), updated_at = VALUES(updated_at)",
                ).use { s ->
                    for (i in items) {
                        s.setString(1, i.type); s.setString(2, i.scope); s.setString(3, i.owner); s.setString(4, i.id)
                        s.setString(5, i.fields); s.setInt(6, if (i.deleted) 1 else 0); s.setLong(7, i.rev); s.setLong(8, i.updatedAt)
                        s.addBatch()
                    }
                    s.executeBatch()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    private val forDevice = "(scope = 'household' OR (scope = 'profile' AND owner_id = ?) OR (scope = 'device' AND owner_id = ?))"

    override fun changedSince(rev: Long, profileId: String, deviceId: String) =
        query("SELECT $columns FROM sync_item WHERE rev > ? AND $forDevice ORDER BY rev") { setLong(1, rev); setString(2, profileId); setString(3, deviceId) }

    override fun live(profileId: String, deviceId: String) =
        query("SELECT $columns FROM sync_item WHERE deleted = 0 AND $forDevice ORDER BY rev") { setString(1, profileId); setString(2, deviceId) }

    override fun all() = query("SELECT $columns FROM sync_item ORDER BY rev")

    override fun ofOwner(scope: String, owner: String) =
        query("SELECT $columns FROM sync_item WHERE scope = ? AND owner_id = ?") { setString(1, scope); setString(2, owner) }

    override fun deleteOwner(scope: String, owner: String) {
        update("DELETE FROM sync_item WHERE scope = ? AND owner_id = ?") { setString(1, scope); setString(2, owner) }
    }

    override fun compact(before: Long): Long? {
        val highest = db.connection.use { c ->
            c.prepareStatement("SELECT MAX(rev) FROM sync_item WHERE deleted = 1 AND updated_at < ?").use { s ->
                s.setLong(1, before)
                s.executeQuery().use { r -> r.next(); r.getLong(1).takeIf { !r.wasNull() } }
            }
        } ?: return null
        update("DELETE FROM sync_item WHERE deleted = 1 AND updated_at < ?") { setLong(1, before) }
        return highest
    }

    override fun profiles(): List<Profile> = db.connection.use { c ->
        c.prepareStatement("SELECT id, name, created_at FROM profile ORDER BY created_at, name").use { s ->
            s.executeQuery().use { r -> buildList { while (r.next()) add(Profile(r.getString(1), r.getString(2), r.getLong(3))) } }
        }
    }

    override fun saveProfile(profile: Profile) {
        update("INSERT INTO profile (id, name, created_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)") {
            setString(1, profile.id); setString(2, profile.name); setLong(3, profile.createdAt)
        }
    }

    override fun deleteProfile(id: String) {
        update("DELETE FROM profile WHERE id = ?") { setString(1, id) }
    }

    override fun addSnapshot(at: Long, reason: String, items: Int, bundle: String): Long = db.connection.use { c ->
        c.prepareStatement("INSERT INTO snapshot (created_at, reason, items, bundle) VALUES (?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS).use { s ->
            s.setLong(1, at); s.setString(2, reason); s.setInt(3, items); s.setString(4, bundle)
            s.executeUpdate()
            s.generatedKeys.use { k -> k.next(); k.getLong(1) }
        }
    }

    override fun snapshots(): List<SnapshotInfo> = db.connection.use { c ->
        c.prepareStatement("SELECT id, created_at, reason, items FROM snapshot ORDER BY created_at DESC").use { s ->
            s.executeQuery().use { r -> buildList { while (r.next()) add(SnapshotInfo(r.getLong(1), r.getLong(2), r.getString(3), r.getInt(4))) } }
        }
    }

    override fun snapshotBundle(id: Long): String? = db.connection.use { c ->
        c.prepareStatement("SELECT bundle FROM snapshot WHERE id = ?").use { s ->
            s.setLong(1, id)
            s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }
    }

    override fun pruneSnapshots(reason: String, keep: Int) {
        val old = snapshots().filter { it.reason == reason }.drop(keep)
        old.forEach { deleteSnapshot(it.id) }
    }

    override fun deleteSnapshot(id: Long) = update("DELETE FROM snapshot WHERE id = ?") { setLong(1, id) } > 0
}

/** For tests, and nothing else. */
class MemorySyncStore : SyncStore {
    private val items = LinkedHashMap<List<String>, StoredItem>()
    private val profileRows = linkedMapOf("main" to Profile("main", "Main", 0))
    private val snapshotRows = mutableListOf<Pair<SnapshotInfo, String>>()

    private fun StoredItem.k() = listOf(type, scope, owner, id)
    private fun StoredItem.visibleTo(profileId: String, deviceId: String) =
        scope == "household" || (scope == "profile" && owner == profileId) || (scope == "device" && owner == deviceId)

    @Synchronized override fun maxRev() = items.values.maxOfOrNull { it.rev } ?: 0
    @Synchronized override fun get(type: String, scope: String, owner: String, id: String) = items[listOf(type, scope, owner, id)]
    @Synchronized override fun write(items: List<StoredItem>) { items.forEach { this.items[it.k()] = it } }
    @Synchronized override fun changedSince(rev: Long, profileId: String, deviceId: String) =
        items.values.filter { it.rev > rev && it.visibleTo(profileId, deviceId) }.sortedBy { it.rev }
    @Synchronized override fun live(profileId: String, deviceId: String) = items.values.filter { !it.deleted && it.visibleTo(profileId, deviceId) }.sortedBy { it.rev }
    @Synchronized override fun all() = items.values.sortedBy { it.rev }
    @Synchronized override fun ofOwner(scope: String, owner: String) = items.values.filter { it.scope == scope && it.owner == owner }
    @Synchronized override fun deleteOwner(scope: String, owner: String) { items.values.removeAll { it.scope == scope && it.owner == owner } }
    @Synchronized override fun compact(before: Long): Long? {
        val gone = items.values.filter { it.deleted && it.updatedAt < before }
        items.values.removeAll(gone.toSet())
        return gone.maxOfOrNull { it.rev }
    }
    @Synchronized override fun profiles() = profileRows.values.toList()
    @Synchronized override fun saveProfile(profile: Profile) { profileRows[profile.id] = profileRows[profile.id]?.copy(name = profile.name) ?: profile }
    @Synchronized override fun deleteProfile(id: String) { profileRows.remove(id) }
    @Synchronized override fun addSnapshot(at: Long, reason: String, items: Int, bundle: String): Long =
        (snapshotRows.maxOfOrNull { it.first.id } ?: 0L).plus(1).also { snapshotRows += SnapshotInfo(it, at, reason, items) to bundle }
    @Synchronized override fun snapshots() = snapshotRows.map { it.first }.sortedByDescending { it.createdAt }
    @Synchronized override fun snapshotBundle(id: Long) = snapshotRows.firstOrNull { it.first.id == id }?.second
    @Synchronized override fun pruneSnapshots(reason: String, keep: Int) {
        snapshots().filter { it.reason == reason }.drop(keep).forEach { deleteSnapshot(it.id) }
    }
    @Synchronized override fun deleteSnapshot(id: Long) = snapshotRows.removeAll { it.first.id == id }
}
