package com.signal.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.io.File

fun main() {
    val log = LoggerFactory.getLogger("Signal")
    val config = Config.fromEnv()
    File(config.dataDir).mkdirs()
    if (config.secretKey == null) log.warn("SIGNAL_SECRET_KEY isn't set: provider credentials can't be stored")
    if (config.db == null) log.warn("DB_HOST isn't set: running without a database")
    // Start even when the database is down, so /health can say so instead of the container looping.
    val database = config.db?.let { db ->
        runCatching { Database(db) }
            .onFailure { log.error("Database ${db.host}:${db.port}/${db.name} not available: ${it.message}") }
            .getOrNull()
    }
    val companion = Companion(config, database)
    log.info("Signal companion ${companion.version} on port ${config.port}")
    embeddedServer(Netty, port = config.port) { signalModule(companion) }.start(wait = true)
}
