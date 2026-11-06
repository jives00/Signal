package com.signal.server

import kotlinx.serialization.Serializable
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/** SCHEDULED hasn't reached its start. WAITING has no connection yet, or lost it; RECORDING is saving. DONE, PARTIAL and FAILED are final. */
enum class RecordingState { SCHEDULED, WAITING, RECORDING, DONE, PARTIAL, FAILED }

/**
 * One recording (dvr.md). [startMs] and [stopMs] are what's captured, padding included;
 * [programStart] and [programStop] are the program's own times, null for one started by hand.
 * [gaps] counts the stretches of the program missed, and [gapMs] adds them up.
 */
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
    val programStart: Long? = null,
    val programStop: Long? = null,
    /** What "Keep recording" added to the end. */
    val extraMs: Long = 0,
    /** Where playback got to. */
    val positionMs: Long = 0,
    /** When it was watched to the end. */
    val watchedAt: Long? = null,
)

interface RecordingStore {
    fun save(recording: Recording)
    fun get(id: String): Recording?
    /** Newest first. */
    fun forProfile(profileId: String): List<Recording>
    /** Started and not finished, in any profile: what a restart picks up. */
    fun unfinished(): List<Recording>
    /** Not started yet, in any profile. */
    fun scheduled(): List<Recording>
    /** Everything with an expiry, in any profile: what retention looks at. */
    fun expiring(): List<Recording>
    /** Saves playback's place. Kept apart from [save], which a running recording calls constantly. */
    fun progress(id: String, positionMs: Long, watchedAt: Long?, expiresAt: Long?)
    fun delete(id: String): Boolean
}

class JdbcRecordingStore(private val db: DataSource) : RecordingStore {
    override fun save(recording: Recording) {
        db.connection.use { c ->
            c.prepareStatement(
                """INSERT INTO recording (id, profile_id, stream_id, title, channel_name, start_ms, stop_ms, state, login_name, ended_at,
                   updated_at, bytes, duration_ms, gaps, gap_ms, reason, expires_at, program_start_ms, program_stop_ms, extra_ms)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON DUPLICATE KEY UPDATE start_ms = VALUES(start_ms), stop_ms = VALUES(stop_ms), program_start_ms = VALUES(program_start_ms),
                   program_stop_ms = VALUES(program_stop_ms), extra_ms = VALUES(extra_ms), state = VALUES(state), login_name = VALUES(login_name),
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
                if (r.programStart != null) s.setLong(18, r.programStart) else s.setNull(18, Types.BIGINT)
                if (r.programStop != null) s.setLong(19, r.programStop) else s.setNull(19, Types.BIGINT)
                s.setLong(20, r.extraMs)
                s.executeUpdate()
            }
        }
    }

    override fun get(id: String): Recording? = query("SELECT * FROM recording WHERE id = ?", id).firstOrNull()

    override fun forProfile(profileId: String) = query("SELECT * FROM recording WHERE profile_id = ? ORDER BY start_ms DESC", profileId)

    override fun unfinished() = query("SELECT * FROM recording WHERE state IN ('WAITING', 'RECORDING')")

    override fun scheduled() = query("SELECT * FROM recording WHERE state = 'SCHEDULED'")

    override fun expiring() = query("SELECT * FROM recording WHERE expires_at IS NOT NULL")

    override fun progress(id: String, positionMs: Long, watchedAt: Long?, expiresAt: Long?) {
        db.connection.use { c ->
            c.prepareStatement("UPDATE recording SET position_ms = ?, watched_at = ?, expires_at = ? WHERE id = ?").use { s ->
                s.setLong(1, positionMs)
                if (watchedAt != null) s.setLong(2, watchedAt) else s.setNull(2, Types.BIGINT)
                if (expiresAt != null) s.setLong(3, expiresAt) else s.setNull(3, Types.BIGINT)
                s.setString(4, id)
                s.executeUpdate()
            }
        }
    }

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
        programStart = getLong("program_start_ms").takeIf { !wasNull() },
        programStop = getLong("program_stop_ms").takeIf { !wasNull() },
        extraMs = getLong("extra_ms"),
        positionMs = getLong("position_ms"),
        watchedAt = getLong("watched_at").takeIf { !wasNull() },
    )
}

class MemoryRecordingStore : RecordingStore {
    private val rows = ConcurrentHashMap<String, Recording>()
    override fun save(recording: Recording) { rows[recording.id] = recording }
    override fun get(id: String) = rows[id]
    override fun forProfile(profileId: String) = rows.values.filter { it.profileId == profileId }.sortedByDescending { it.startMs }
    override fun unfinished() = rows.values.filter { it.state == RecordingState.WAITING || it.state == RecordingState.RECORDING }
    override fun scheduled() = rows.values.filter { it.state == RecordingState.SCHEDULED }
    override fun expiring() = rows.values.filter { it.expiresAt != null }
    override fun progress(id: String, positionMs: Long, watchedAt: Long?, expiresAt: Long?) {
        rows.computeIfPresent(id) { _, r -> r.copy(positionMs = positionMs, watchedAt = watchedAt, expiresAt = expiresAt) }
    }
    override fun delete(id: String) = rows.remove(id) != null
}
