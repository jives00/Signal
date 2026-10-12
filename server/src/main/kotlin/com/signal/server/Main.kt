package com.signal.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.concurrent.thread

private val log = LoggerFactory.getLogger("Signal")

fun main() {
    val config = Config.fromEnv()
    File(config.dataDir).mkdirs()
    if (config.secretKey == null) log.warn("SIGNAL_SECRET_KEY isn't set: provider credentials can't be stored")
    if (config.db == null) log.warn("DB_HOST isn't set: running without a database")
    val companion = Companion(config)
    // Serve straight away, even before the database answers, so /health can say what's wrong
    // instead of the container restarting in a loop.
    config.db?.let { db -> connectInBackground(companion, db) }
    companion.guideJob.start()
    log.info("Signal companion ${companion.version} on port ${config.port}")
    embeddedServer(Netty, port = config.port) { signalModule(companion) }.start(wait = true)
}

/** Connects and migrates, retrying every 30 seconds until it works (MySQL may start after us). */
private fun connectInBackground(companion: Companion, db: Config.DbConfig) = thread(name = "db-connect", isDaemon = true) {
    var attempt = 0
    while (companion.database == null) {
        attempt++
        runCatching { Database(db) }
            .onSuccess {
                companion.database = it
                log.info("Database ${db.host}:${db.port}/${db.name} connected")
            }
            .onFailure {
                // The first failure, then every tenth, so a long outage doesn't flood the log.
                if (attempt == 1 || attempt % 10 == 0) log.error("Database ${db.host}:${db.port}/${db.name} not available (attempt $attempt): ${it.message}")
                Thread.sleep(30_000)
            }
    }
}
