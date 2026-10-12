package com.signal.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** The `signal` database in the shared MySQL container (PLAN §5.2), migrated by Flyway on start. */
class Database(config: Config.DbConfig) : AutoCloseable {
    private val pool = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = 8
            poolName = "signal"
            // Fail fast at start-up when the database isn't there, rather than hang.
            initializationFailTimeout = 10_000
        },
    )

    val dataSource: DataSource get() = pool
    val devices: DeviceStore = JdbcDeviceStore(pool)
    val store: CompanionStore = JdbcCompanionStore(pool)

    init {
        Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate()
    }

    /** True when a trivial query succeeds: for /health. */
    fun ping(): Boolean = runCatching { pool.connection.use { it.isValid(2) } }.getOrDefault(false)

    override fun close() = pool.close()
}
