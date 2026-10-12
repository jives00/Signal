package com.signal.tv

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.signal.platform.SettingsFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings files on the Shield (PLAN §9.2 → Fallback). The system file picker when there is one,
 * which reaches USB sticks; Android TV builds without one save to, and open from, Downloads.
 */
class AndroidSettingsFiles(private val context: Context, private val activity: () -> MainActivity?) : SettingsFiles {
    override suspend fun save(suggestedName: String, text: String): String? {
        val picked = try {
            activity()?.pickFileToCreate(suggestedName) ?: return null
        } catch (_: ActivityNotFoundException) {
            return saveToDownloads(suggestedName, text)
        } ?: return null
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(picked, "wt")?.use { it.write(text.toByteArray()) } ?: error("Couldn't write the file")
        }
        return picked.lastPathSegment?.substringAfterLast(':') ?: "the chosen file"
    }

    override suspend fun open(): String? {
        val picked = try {
            activity()?.pickFileToOpen() ?: return null
        } catch (_: ActivityNotFoundException) {
            return newestInDownloads()
        } ?: return null
        return withContext(Dispatchers.IO) { context.contentResolver.openInputStream(picked)?.use { it.readBytes().decodeToString() } }
    }

    private suspend fun saveToDownloads(name: String, text: String): String = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file")
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Couldn't write the file")
        "Downloads/$name"
    }

    /** Without a picker: the newest settings file in Downloads this app can read (the ones it saved). */
    private suspend fun newestInDownloads(): String? = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("signal-settings%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        } ?: error("No settings file in Downloads")
    }
}
