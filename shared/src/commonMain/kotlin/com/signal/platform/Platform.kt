package com.signal.platform

import app.cash.sqldelight.db.SqlDriver
import com.signal.player.VideoPlayer
import com.signal.update.Updater
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Everything the shared app needs from the platform it runs on: the Android TV app and the dev
 * harness each provide one. The shared code never touches Android or desktop APIs directly.
 */
interface Platform {
    val appInfo: AppInfo
    val secretBox: SecretBox
    val player: VideoPlayer
    /** Null where updates don't apply (the dev harness, debug builds). */
    val updater: Updater?
    val io: CoroutineDispatcher

    fun createUserDatabaseDriver(): SqlDriver

    /** A directory for guide.db files: each build is a new file there, swapped in when complete (PLAN §6). */
    val guideDirectory: String

    /** Opens (creating if needed) the guide database at [path]. */
    fun createGuideDatabaseDriver(path: String): SqlDriver
    fun createHttpClient(): HttpClient

    /** The device's own 12/24-hour preference, the default until the user picks one (PLAN §4.3.15). */
    fun systemUses24HourClock(): Boolean

    /** Back on the nav bar of Home exits the app straight away (PLAN §4.3.1). */
    fun exitApp()
}

data class AppInfo(
    val versionName: String,
    /** The apk-* tag this build came from; "dev" for local and debug builds. */
    val buildTag: String,
    val isDebug: Boolean,
    val deviceModel: String,
)

/** Encrypts credentials at rest: Android Keystore on the Shield, a local key file in the harness (PLAN §5.7). */
interface SecretBox {
    fun encrypt(plain: String): String
    fun decrypt(cipher: String): String
}
