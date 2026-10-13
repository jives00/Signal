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
    /** Speech to text for Search's mic button (PLAN §4.3.14), or null where there's none (the dev harness). */
    val voiceInput: VoiceInput?
    val io: CoroutineDispatcher
    /** Saving and opening a settings file (PLAN §9.2 → Fallback), or null where there's no way to. */
    val settingsFiles: SettingsFiles? get() = null

    fun createUserDatabaseDriver(): SqlDriver

    /** A directory for guide.db files: each build is a new file there, swapped in when complete (PLAN §6). */
    val guideDirectory: String

    /** Opens (creating if needed) the guide database at [path]. */
    fun createGuideDatabaseDriver(path: String): SqlDriver
    fun createHttpClient(): HttpClient

    /** The device's own 12/24-hour preference, the default until the user picks one (PLAN §4.3.15). */
    fun systemUses24HourClock(): Boolean

    /** Opens a link in the app that handles it: a trailer in YouTube on the Shield (PLAN §4.3.8). False if nothing can. */
    fun openUrl(url: String): Boolean = false

    /** Back on the nav bar of Home exits the app straight away (PLAN §4.3.1). */
    fun exitApp()
}

/** A settings file on a USB stick or in Downloads. */
interface SettingsFiles {
    /** Saves [text] as a new file and returns where it went, for the screen to say; null when cancelled. */
    suspend fun save(suggestedName: String, text: String): String?
    /** Lets the user pick a file and returns its text; null when cancelled. */
    suspend fun open(): String?
}

/** Listens once and hands back the recognized text; nothing when the user cancels. */
fun interface VoiceInput {
    fun listen(onText: (String) -> Unit)
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
