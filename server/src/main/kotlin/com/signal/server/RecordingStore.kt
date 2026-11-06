package com.signal.server

import kotlinx.serialization.Serializable
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/** WAITING has no connection yet, or lost it; RECORDING is saving. DONE, PARTIAL and FAILED are final. */
enum class RecordingState { WAITING, RECORDING, DONE, PARTIAL, FAILED }

/** One recording (dvr.md). [gaps] counts the stretches missed, and [gapMs] adds them up. */
@Serializable
data class Recording(
    val id: String,
    val profileId: String,
    val streamId: String,
    val title: String,
    val channelName: String,
    val startMs: Long,
    val stopMs: Long,
    val state: RecordingState,
    val loginName: String? = null,
    val endedAt: Long? = null,
    val updatedAt: Long,
    val bytes: Long = 0,
    val durationMs: Long = 0,
    val gaps: Int = 0,
    val gapMs: Long = 0,
    val reason: String? = null,
    val expiresAt: Long? = null,
)

interface RecordingStore {
    fun save(recording: Recording)
    fun get(id: String): Recording?
    /** Newest first. */
    fun forProfile(profileId: String): List<Recording>
    /** Anything not finished, in any profile: what a restart picks up. */
    fun unfinished(): List<Recording>
    fun delete(id: String): Boolean
}

class JdbcRecordingStore(private val db: DataSource) : RecordingStore {
    override fun save(recording: Recording) {
        db.connection.use { c ->
            c.prepareStatement(
                """INSERT INTO recording (id, profile_id, stream_id, title, channel_name, start_ms, stop_ms, state, login_name, ended_at,
                   updated_at, bytes, duration_ms, gaps, gap_ms, reason, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON DUPLICATE KEY UPDATE stop_ms = VALUES(stop_ms), state = VALUES(state), login_name = VALUES(login_name),
                   ended_at = VALUES(ended_at), updated_at = VALUES(updated_at), bytes = VALUES(bytes), duration_ms = VALUES(duration_ms),
                   gaps = VALUES(gaps), gap_ms = VALUES(gap_ms), reason = VALUES(reason), expires_at = VALUES(expires_at)""",
            ).use { s ->
                val r = recording
                s.setString(1, r.id)
                s.setString(2, r.profileId)
                s.setString(3, r.streamId)
                s.setString(4, r.title.take(500))
                s.setString(5, r.channelName.take(500))
                s.setLong(6, r.startMs)
                s.setLong(7, r.stopMs)
                s.setString(8, r.state.name)
                s.setString(9, r.loginName)
                if (r.endedAt != null) s.setLong(10, r.endedAt) else s.setNull(10, Types.BIGINT)
                s.setLong(11, r.updatedAt)
                s.setLong(12, r.bytes)
                s.setLong(13, r.durationMs)
                s.setInt(14, r.gaps)
                s.setLong(15, r.gapMs)
                s.setString(16, r.reason?.take(500))
                if (r.expiresAt != null) s.setLong(17, r.expiresAt) else s.setNull(17, Types.BIGINT)
                s.executeUpdate()
            }
        }
    }

    override fun get(id: String): Recording? = query("SELECT * FROM recording WHERE id = ?", id).firstOrNull()

    override fun forProfile(profileId: String) = query("SELECT * FROM recording WHERE profile_id = ? ORDER BY start_ms DESC", profileId)

    override fun unfinished() = query("SELECT * FROM recording WHERE state IN ('WAITING', 'RECORDING')")

    override fun delete(id: String): Boolean = db.connection.use { c ->
        c.prepareStatement("DELETE FROM recording WHERE id = ?").use { s ->
            s.setString(1, id)
            s.executeUpdate() > 0
        }
    }

    private fun query(sql: String, vararg args: String): List<Recording> = db.connection.use { c ->
        c.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setString(i + 1, a) }
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.toRecording()) } }
        }
    }

    private fun ResultSet.toRecording() = Recording(
        id = getString("id"),
        profileId = getString("profile_id"),
        streamId = getString("stream_id"),
        title = getString("title"),
        channelName = getString("channel_name"),
        startMs = getLong("start_ms"),
        stopMs = getLong("stop_ms"),
        state = RecordingState.valueOf(getString("state")),
        loginName = getString("login_name"),
        endedAt = getLong("ended_at").takeIf { !wasNull() },
        updatedAt = getLong("updated_at"),
        bytes = getLong("bytes"),
        durationMs = getLong("duration_ms"),
        gaps = getInt("gaps"),
        gapMs = getLong("gap_ms"),
        reason = getString("reason"),
        expiresAt = getLong("expires_at").takeIf { !wasNull() },
    )
}

class MemoryRecordingStore : RecordingStore {
    private val rows = ConcurrentHashMap<String, Recording>()
    override fun save(recording: Recording) { rows[recording.id] = recording }
    override fun get(id: String) = rows[id]
    override fun forProfile(profileId: String) = rows.values.filter { it.profileId == profileId }.sortedByDescending { it.startMs }
    override fun unfinished() = rows.values.filter { it.state == RecordingState.WAITING || it.state == RecordingState.RECORDING }
    override fun delete(id: String) = rows.remove(id) != null
}
