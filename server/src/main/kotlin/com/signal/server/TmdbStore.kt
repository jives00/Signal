package com.signal.server

import javax.sql.DataSource

/** A catalog title's TMDB match (V4's vod_match). */
data class VodMatch(val vodItemId: String, val sourceId: String, val kind: String, val tmdbId: Long?, val status: String, val attemptedAt: Long) {
    companion object Status {
        const val MATCHED = "matched"
        const val UNMATCHED = "unmatched"
        /** Fixed in the web admin; the matcher leaves it alone. */
        const val MANUAL = "manual"
    }
}

/** V4's tmdb_title, vod_match and tmdb_list, behind an interface so enrichment is testable without MySQL. */
interface TmdbStore {
    fun title(kind: String, tmdbId: Long): Pair<String, Long>?
    fun putTitle(kind: String, tmdbId: Long, body: String, at: Long)
    /** Every stored title of [kind], streamed: for building the index without holding all bodies. */
    fun forEachTitle(kind: String, f: (Long, String) -> Unit)
    fun matches(): List<VodMatch>
    fun match(vodItemId: String): VodMatch? = matches().firstOrNull { it.vodItemId == vodItemId }
    fun putMatch(m: VodMatch)
    fun list(kind: String, list: String): List<Long>
    fun putList(kind: String, list: String, ids: List<Long>)
}

class JdbcTmdbStore(private val db: DataSource) : TmdbStore {
    override fun title(kind: String, tmdbId: Long): Pair<String, Long>? = db.connection.use { c ->
        c.prepareStatement("SELECT body, fetched_at FROM tmdb_title WHERE kind = ? AND tmdb_id = ?").use { s ->
            s.setString(1, kind); s.setLong(2, tmdbId)
            s.executeQuery().use { r -> if (r.next()) r.getString(1) to r.getLong(2) else null }
        }
    }

    override fun putTitle(kind: String, tmdbId: Long, body: String, at: Long) {
        db.connection.use { c ->
            c.prepareStatement("INSERT INTO tmdb_title (kind, tmdb_id, body, fetched_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE body = VALUES(body), fetched_at = VALUES(fetched_at)").use { s ->
                s.setString(1, kind); s.setLong(2, tmdbId); s.setString(3, body); s.setLong(4, at)
                s.executeUpdate()
            }
        }
    }

    override fun forEachTitle(kind: String, f: (Long, String) -> Unit) {
        db.connection.use { c ->
            c.prepareStatement("SELECT tmdb_id, body FROM tmdb_title WHERE kind = ?").use { s ->
                s.setString(1, kind)
                s.fetchSize = 500
                s.executeQuery().use { r -> while (r.next()) f(r.getLong(1), r.getString(2)) }
            }
        }
    }

    override fun match(vodItemId: String): VodMatch? = db.connection.use { c ->
        c.prepareStatement("SELECT vod_item_id, source_id, kind, tmdb_id, status, attempted_at FROM vod_match WHERE vod_item_id = ?").use { s ->
            s.setString(1, vodItemId)
            s.executeQuery().use { r -> if (r.next()) VodMatch(r.getString(1), r.getString(2), r.getString(3), r.getLong(4).takeIf { !r.wasNull() }, r.getString(5), r.getLong(6)) else null }
        }
    }

    override fun matches(): List<VodMatch> = db.connection.use { c ->
        c.prepareStatement("SELECT vod_item_id, source_id, kind, tmdb_id, status, attempted_at FROM vod_match").use { s ->
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(VodMatch(r.getString(1), r.getString(2), r.getString(3), r.getLong(4).takeIf { !r.wasNull() }, r.getString(5), r.getLong(6))) }
            }
        }
    }

    override fun putMatch(m: VodMatch) {
        db.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO vod_match (vod_item_id, source_id, kind, tmdb_id, status, attempted_at) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE tmdb_id = VALUES(tmdb_id), status = VALUES(status), attempted_at = VALUES(attempted_at)",
            ).use { s ->
                s.setString(1, m.vodItemId); s.setString(2, m.sourceId); s.setString(3, m.kind)
                if (m.tmdbId == null) s.setNull(4, java.sql.Types.BIGINT) else s.setLong(4, m.tmdbId)
                s.setString(5, m.status); s.setLong(6, m.attemptedAt)
                s.executeUpdate()
            }
        }
    }

    override fun list(kind: String, list: String): List<Long> = db.connection.use { c ->
        c.prepareStatement("SELECT tmdb_id FROM tmdb_list WHERE kind = ? AND list = ? ORDER BY position").use { s ->
            s.setString(1, kind); s.setString(2, list)
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.getLong(1)) } }
        }
    }

    override fun putList(kind: String, list: String, ids: List<Long>) {
        db.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("DELETE FROM tmdb_list WHERE kind = ? AND list = ?").use { s -> s.setString(1, kind); s.setString(2, list); s.executeUpdate() }
                c.prepareStatement("INSERT INTO tmdb_list (kind, list, position, tmdb_id) VALUES (?, ?, ?, ?)").use { s ->
                    ids.forEachIndexed { i, id -> s.setString(1, kind); s.setString(2, list); s.setInt(3, i); s.setLong(4, id); s.addBatch() }
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
}

/** For tests, and nothing else. */
class MemoryTmdbStore : TmdbStore {
    private val titles = HashMap<Pair<String, Long>, Pair<String, Long>>()
    private val matchRows = LinkedHashMap<String, VodMatch>()
    private val lists = HashMap<Pair<String, String>, List<Long>>()
    @Synchronized override fun title(kind: String, tmdbId: Long) = titles[kind to tmdbId]
    @Synchronized override fun putTitle(kind: String, tmdbId: Long, body: String, at: Long) { titles[kind to tmdbId] = body to at }
    @Synchronized override fun forEachTitle(kind: String, f: (Long, String) -> Unit) { titles.filterKeys { it.first == kind }.forEach { (k, v) -> f(k.second, v.first) } }
    @Synchronized override fun matches() = matchRows.values.toList()
    @Synchronized override fun putMatch(m: VodMatch) { matchRows[m.vodItemId] = m }
    @Synchronized override fun list(kind: String, list: String) = lists[kind to list].orEmpty()
    @Synchronized override fun putList(kind: String, list: String, ids: List<Long>) { lists[kind to list] = ids }
}
