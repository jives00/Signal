package com.signal.server

/**
 * The companion's env vars (PLAN §5.9): only what it needs before it can read its own database,
 * and the encryption key, which must never sit next to what it protects. Everything else is a web
 * admin setting.
 */
data class Config(
    val port: Int,
    val db: DbConfig?,
    val secretKey: String?,
    val dataDir: String,
    /** The built web admin (web/dist); served as static files. */
    val webDir: String,
) {
    data class DbConfig(val host: String, val port: Int, val name: String, val user: String, val password: String) {
        // The database name is never quoted in SQL anywhere, so SIGNAL being a reserved word doesn't matter.
        val jdbcUrl: String get() = "jdbc:mariadb://$host:$port/$name"
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val db = env["DB_HOST"]?.let { host ->
                DbConfig(
                    host = host,
                    port = env["DB_PORT"]?.toIntOrNull() ?: 3306,
                    name = env["DB_NAME"] ?: "signal",
                    user = env["DB_USER"] ?: "signal",
                    password = env["DB_PASSWORD"].orEmpty(),
                )
            }
            return Config(
                port = env["PORT"]?.toIntOrNull() ?: 3013,
                db = db,
                secretKey = env["SIGNAL_SECRET_KEY"]?.takeIf { it.isNotBlank() },
                dataDir = env["DATA_DIR"] ?: "data",
                webDir = env["WEB_DIR"] ?: "web",
            )
        }
    }
}
