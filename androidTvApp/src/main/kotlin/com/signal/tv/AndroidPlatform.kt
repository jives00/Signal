package com.signal.tv

import android.app.Activity
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.format.DateFormat
import android.util.Base64
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.signal.db.guide.GuideDatabase
import com.signal.db.user.UserDatabase
import com.signal.platform.AppInfo
import com.signal.platform.Platform
import com.signal.platform.SecretBox
import com.signal.tv.player.ExoVideoPlayer
import com.signal.update.Updater
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import java.lang.ref.WeakReference
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidPlatform(private val context: Context) : Platform {
    var activity: WeakReference<Activity>? = null

    override val appInfo = AppInfo(
        versionName = BuildConfig.VERSION_NAME,
        buildTag = BuildConfig.BUILD_TAG,
        isDebug = BuildConfig.DEV_TOOLS,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
    )
    override val secretBox: SecretBox = KeystoreSecretBox()
    override val player = ExoVideoPlayer(context)
    override val io = Dispatchers.IO
    override val settingsFiles = AndroidSettingsFiles(context) { activity?.get() as? MainActivity }
    private val http by lazy { createHttpClient() }

    // Release builds from an apk-* tag only; debug and local builds never offer updates.
    override val updater: Updater? by lazy {
        if (BuildConfig.DEV_TOOLS || BuildConfig.BUILD_TAG == "dev") null else AndroidUpdater(context, http, BuildConfig.BUILD_TAG)
    }

    // Write-ahead logging: reads run beside a write instead of queueing behind it. A source refresh swaps
    // in the whole catalog in one transaction (minutes on the Shield), and with the default rollback
    // journal every read waited for it, tuning included: nothing played until the refresh ended (0.2.9).
    override fun createUserDatabaseDriver(): SqlDriver = AndroidSqliteDriver(
        UserDatabase.Schema, context, "user.db",
        callback = object : AndroidSqliteDriver.Callback(UserDatabase.Schema) {
            override fun onConfigure(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.enableWriteAheadLogging()
            }
        },
    )

    // Not backed up: the guide is rebuilt from its sources whenever it's missing.
    override val guideDirectory: String = java.io.File(context.noBackupFilesDir, "guide").apply { mkdirs() }.absolutePath

    // An absolute path, which SQLiteOpenHelper accepts in place of a name.
    override fun createGuideDatabaseDriver(path: String): SqlDriver = AndroidSqliteDriver(GuideDatabase.Schema, context, path)

    override fun createHttpClient() = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 120_000
        }
    }

    // Read from system settings, and asked for by every guide cell's time and every clock: a short cache.
    @Volatile private var uses24Hour = false
    @Volatile private var uses24HourReadAt = 0L

    override fun systemUses24HourClock(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (uses24HourReadAt == 0L || now - uses24HourReadAt > 10_000) {
            uses24Hour = DateFormat.is24HourFormat(context)
            uses24HourReadAt = now
        }
        return uses24Hour
    }

    override fun exitApp() {
        activity?.get()?.finish()
    }

    override fun openUrl(url: String): Boolean {
        val act = activity?.get() ?: return false
        // YouTube links open in the YouTube app when it's installed, else whatever handles the URL.
        val youtube = Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
        val intents = listOfNotNull(
            youtube?.let { android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$it")) },
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        )
        return intents.any { runCatching { act.startActivity(it) }.isSuccess }
    }
}

/** Credentials at rest, encrypted with an AES-GCM key held in the Android Keystore (PLAN §5.7). */
private class KeystoreSecretBox : SecretBox {
    private val alias = "signal-secrets"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun decrypt(cipher: String): String {
        val bytes = Base64.decode(cipher, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }
}
