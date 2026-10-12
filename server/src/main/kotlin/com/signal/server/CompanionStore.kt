package com.signal.server

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/** One run of a job (PLAN §5.8), for the web admin. */
@Serializable
data class JobRun(val id: Long, val job: String, val startedAt: Long, val finishedAt: Long?, val result: String?, val detail: String?)

/** The companion's own settings and its job history (V1's companion_setting and job_run). */
interface CompanionStore {
    fun setting(name: String): String?
    fun putSetting(name: String, value: String)
    fun startJob(job: String, at: Long): Long
    fun finishJob(id: Long, at: Long, result: String, detail: String?)
    fun lastJob(job: String): JobRun?
    /** The newest [limit] runs, for Jobs & logs. */
    fun jobs(limit: Int): List<JobRun>
}

class JdbcCompanionStore(private val db: DataSource) : CompanionStore {
    override fun setting(name: String): String? = db.connection.use { c ->
        c.prepareStatement("SELECT value FROM companion_setting WHERE name = ?").use { s ->
            s.setString(1, name)
            s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }
    }

    override fun putSetting(name: String, value: String) {
        db.connection.use { c ->
            c.prepareStatement("INSERT INTO companion_setting (name, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)").use { s ->
                s.setString(1, name)
                s.setString(2, value)
                s.executeUpdate()
            }
        }
    }

    override fun startJob(job: String, at: Long): Long = db.connection.use { c ->
        c.prepareStatement("INSERT INTO job_run (job, started_at) VALUES (?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS).use { s ->
            s.setString(1, job)
            s.setLong(2, at)
            s.executeUpdate()
            s.generatedKeys.use { k -> k.next(); k.getLong(1) }
        }
    }

    override fun finishJob(id: Long, at: Long, result: String, detail: String?) {
        db.connection.use { c ->
            c.prepareStatement("UPDATE job_run SET finished_at = ?, result = ?, detail = ? WHERE id = ?").use { s ->
                s.setLong(1, at)
                s.setString(2, result)
                s.setString(3, detail?.take(60_000))
                s.setLong(4, id)
                s.executeUpdate()
            }
        }
    }

    override fun lastJob(job: String): JobRun? = db.connection.use { c ->
        c.prepareStatement("SELECT id, job, started_at, finished_at, result, detail FROM job_run WHERE job = ? ORDER BY started_at DESC LIMIT 1").use { s ->
            s.setString(1, job)
            s.executeQuery().use { r ->
                if (!r.next()) null
                else JobRun(r.getLong(1), r.getString(2), r.getLong(3), r.getLong(4).takeIf { !r.wasNull() }, r.getString(5), r.getString(6))
            }
        }
    }

    override fun jobs(limit: Int): List<JobRun> = db.connection.use { c ->
        c.prepareStatement("SELECT id, job, started_at, finished_at, result, detail FROM job_run ORDER BY started_at DESC LIMIT ?").use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(JobRun(r.getLong(1), r.getString(2), r.getLong(3), r.getLong(4).takeIf { !r.wasNull() }, r.getString(5), r.getString(6))) }
            }
        }
    }
}

/** For tests, and nothing else. */
class MemoryCompanionStore : CompanionStore {
    private val settings = ConcurrentHashMap<String, String>()
    private val jobs = mutableListOf<JobRun>()
    override fun setting(name: String) = settings[name]
    override fun putSetting(name: String, value: String) { settings[name] = value }
    @Synchronized override fun startJob(job: String, at: Long): Long = (jobs.size + 1L).also { jobs += JobRun(it, job, at, null, null, null) }
    @Synchronized override fun finishJob(id: Long, at: Long, result: String, detail: String?) {
        val i = jobs.indexOfFirst { it.id == id }
        if (i >= 0) jobs[i] = jobs[i].copy(finishedAt = at, result = result, detail = detail)
    }
    @Synchronized override fun lastJob(job: String) = jobs.lastOrNull { it.job == job }
    @Synchronized override fun jobs(limit: Int) = jobs.sortedByDescending { it.startedAt }.take(limit)
}
