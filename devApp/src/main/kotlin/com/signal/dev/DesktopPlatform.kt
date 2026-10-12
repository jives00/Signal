package com.signal.dev

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.db.guide.GuideDatabase
import com.signal.db.user.UserDatabase
import com.signal.platform.AppInfo
import com.signal.platform.Platform
import com.signal.platform.SecretBox
import com.signal.player.VideoPlayer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.security.SecureRandom
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess

/**
 * The dev harness's platform (PLAN §7.1): the real shared app on Windows, with its data in
 * ~/.signal-dev so it never touches anything else. A dev tool only, never distributed.
 */
class DesktopPlatform(override val player: VideoPlayer) : Platform {
    private val dir = File(System.getProperty("user.home"), ".signal-dev").apply { mkdirs() }

    override val appInfo = AppInfo(
        versionName = versionName(),
        buildTag = "dev",
        isDebug = true,
        deviceModel = "Dev harness · ${System.getProperty("os.name")}",
    )
    override val secretBox: SecretBox = KeyFileSecretBox(File(dir, "secret.key"))
    override val updater = null
    // One database thread at a time, like Android's single connection: the JDBC driver opens one per
    // thread, and two writing at once deadlock with SQLITE_BUSY.
    override val io = Dispatchers.IO.limitedParallelism(1)

    override fun createUserDatabaseDriver(): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:${File(dir, "user.db").absolutePath}", Properties(), UserDatabase.Schema)

    override val guideDirectory: String = File(dir, "guide").apply { mkdirs() }.absolutePath

    override fun createGuideDatabaseDriver(path: String): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:$path", Properties(), GuideDatabase.Schema)

    override fun createHttpClient() = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 120_000
        }
    }

    override fun systemUses24HourClock(): Boolean {
        val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()) as? SimpleDateFormat)?.toPattern() ?: return false
        return 'H' in pattern
    }

    override fun exitApp() = exitProcess(0)

    private fun versionName(): String =
        javaClass.getResourceAsStream("/signal-version.txt")?.bufferedReader()?.readText()?.trim() ?: "dev"
}

/** AES-GCM with a random key kept in a local file. Fine for a dev tool; the Shield uses the Keystore. */
private class KeyFileSecretBox(private val file: File) : SecretBox {
    private val key: SecretKeySpec by lazy {
        if (!file.exists()) file.writeBytes(ByteArray(32).also(SecureRandom()::nextBytes))
        SecretKeySpec(file.readBytes(), "AES")
    }

    override fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    override fun decrypt(cipher: String): String {
        val bytes = Base64.getDecoder().decode(cipher)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }
}
