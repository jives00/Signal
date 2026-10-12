package com.signal.server

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/** A paired device, as the web admin lists it (PLAN §4.3.18 → Devices & profiles). */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val model: String,
    val appVersion: String,
    val pairedAt: Long,
    val lastSeenAt: Long,
)

/** The `device` table, behind an interface so the routes are testable without MySQL. */
interface DeviceStore {
    fun all(): List<Device>
    fun byKeyHash(hash: String): Device?
    /** Adds the device, or replaces its row (and so its key) when it pairs again. */
    fun save(device: Device, keyHash: String)
    fun touch(id: String, appVersion: String, at: Long)
    fun rename(id: String, name: String): Boolean
    /** Forgets the device; its key stops working at once. */
    fun delete(id: String): Boolean
}

class JdbcDeviceStore(private val db: DataSource) : DeviceStore {
    private val columns = "id, name, model, app_version, paired_at, last_seen_at"

    private fun java.sql.ResultSet.toDevice() =
        Device(getString("id"), getString("name"), getString("model"), getString("app_version"), getLong("paired_at"), getLong("last_seen_at"))

    override fun all(): List<Device> = db.connection.use { c ->
        c.prepareStatement("SELECT $columns FROM device ORDER BY name").use { s ->
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.toDevice()) } }
        }
    }

    override fun byKeyHash(hash: String): Device? = db.connection.use { c ->
        c.prepareStatement("SELECT $columns FROM device WHERE key_hash = ?").use { s ->
            s.setString(1, hash)
            s.executeQuery().use { r -> if (r.next()) r.toDevice() else null }
        }
    }

    override fun save(device: Device, keyHash: String) {
        db.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO device (id, name, model, app_version, key_hash, paired_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), model = VALUES(model), app_version = VALUES(app_version), " +
                    "key_hash = VALUES(key_hash), paired_at = VALUES(paired_at), last_seen_at = VALUES(last_seen_at)",
            ).use { s ->
                s.setString(1, device.id)
                s.setString(2, device.name)
                s.setString(3, device.model)
                s.setString(4, device.appVersion)
                s.setString(5, keyHash)
                s.setLong(6, device.pairedAt)
                s.setLong(7, device.lastSeenAt)
                s.executeUpdate()
            }
        }
    }

    override fun touch(id: String, appVersion: String, at: Long) {
        update("UPDATE device SET app_version = ?, last_seen_at = ? WHERE id = ?") { setString(1, appVersion); setLong(2, at); setString(3, id) }
    }

    override fun rename(id: String, name: String) = update("UPDATE device SET name = ? WHERE id = ?") { setString(1, name); setString(2, id) } > 0

    override fun delete(id: String) = update("DELETE FROM device WHERE id = ?") { setString(1, id) } > 0

    private fun update(sql: String, bind: java.sql.PreparedStatement.() -> Unit): Int =
        db.connection.use { c -> c.prepareStatement(sql).use { s -> s.bind(); s.executeUpdate() } }
}

/** For tests, and nothing else. */
class MemoryDeviceStore : DeviceStore {
    private val rows = ConcurrentHashMap<String, Pair<Device, String>>()
    override fun all() = rows.values.map { it.first }.sortedBy { it.name }
    override fun byKeyHash(hash: String) = rows.values.firstOrNull { it.second == hash }?.first
    override fun save(device: Device, keyHash: String) { rows[device.id] = device to keyHash }
    override fun touch(id: String, appVersion: String, at: Long) {
        rows.computeIfPresent(id) { _, (d, h) -> d.copy(appVersion = appVersion, lastSeenAt = at) to h }
    }
    override fun rename(id: String, name: String) = rows.computeIfPresent(id) { _, (d, h) -> d.copy(name = name) to h } != null
    override fun delete(id: String) = rows.remove(id) != null
}

/** API keys: 32 random bytes for the device, and only their SHA-256 in the database. */
object DeviceKeys {
    private val random = SecureRandom()

    fun newKey(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun hash(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
}
