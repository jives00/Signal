package com.signal.tv.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/*
 * Keeping Signal the assistant after a force stop (PLAN §4.3.14 → Voice commands, phase 2).
 *
 * On Android 11 (the Shield), force-stopping the assistant makes the system clear the assistant
 * role, and the role falls back to its default, Google Assistant. An app can't take a role back
 * (that needs MANAGE_ROLE_HOLDERS, which only system apps get), but the voice interaction service
 * the system actually binds is read from a secure setting, and it switches as soon as that changes.
 * WRITE_SECURE_SETTINGS can be granted to any app once with adb:
 *
 *   adb shell pm grant com.signal.tv android.permission.WRITE_SECURE_SETTINGS
 *
 * With it, Signal writes itself back into the settings whenever its process starts: opening it after
 * a force stop, a reboot (the system re-syncs the settings to the role holder at boot), an update.
 * Checked on the Android 16 emulator: with Google holding the role, writing the setting switched
 * the bound service to Signal, and the Assist key reached it.
 */
internal object AssistantKeeper {
    private const val TAG = "Signal"
    private const val PREFS = "voice"
    /** Set once Signal has been the assistant, so it never takes over a TV where voice was never turned on. */
    private const val WAS_ASSISTANT = "was_assistant"

    private const val VOICE_INTERACTION_SERVICE = "voice_interaction_service"
    private const val ASSISTANT = "assistant"
    private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

    private fun service(context: Context) = ComponentName(context, SignalAssistantService::class.java).flattenToString()
    private fun recognizer(context: Context) = ComponentName(context, SignalRecognitionService::class.java).flattenToString()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The permission to write the settings, granted with adb. */
    fun canKeep(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Whether the system binds Signal as the voice interaction service now (the role may still name Google). */
    fun isAssistant(context: Context) =
        Settings.Secure.getString(context.contentResolver, VOICE_INTERACTION_SERVICE) == service(context)

    /**
     * On process start: remembers that Signal is the assistant, or, when it was and lost it to a
     * force stop, puts it back. Then watches for the user giving the mic to another assistant.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        val was = prefs(app).getBoolean(WAS_ASSISTANT, false)
        if (isAssistant(app)) {
            prefs(app).edit().putBoolean(WAS_ASSISTANT, true).apply()
        } else if (was && canKeep(app)) {
            restore(app)
        } else if (was) {
            Log.i(TAG, "Voice: Signal lost the assistant (a force stop?); allow it to take it back with adb shell pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
        }
        watch(app)
    }

    private fun restore(context: Context) {
        val now = Settings.Secure.getString(context.contentResolver, VOICE_INTERACTION_SERVICE)
        runCatching {
            val resolver = context.contentResolver
            Settings.Secure.putString(resolver, VOICE_INTERACTION_SERVICE, service(context))
            Settings.Secure.putString(resolver, ASSISTANT, service(context))
            Settings.Secure.putString(resolver, VOICE_RECOGNITION_SERVICE, recognizer(context))
        }.onSuccess {
            Log.i(TAG, "Voice: Signal is the assistant again (was $now)")
        }.onFailure { Log.w(TAG, "Voice: couldn't restore the assistant: ${it.message}") }
    }

    private var watching = false

    /**
     * The assistant changing away from Signal while it runs is the user's choice (the adb command
     * that gives the mic back to Google): stop restoring it. A force stop kills Signal before the
     * system clears the role, so that change is never seen here and Signal still restores it.
     */
    private fun watch(context: Context) {
        if (watching) return
        watching = true
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val now = Settings.Secure.getString(context.contentResolver, VOICE_INTERACTION_SERVICE).orEmpty()
                when {
                    now == service(context) -> prefs(context).edit().putBoolean(WAS_ASSISTANT, true).apply()
                    // A role change passes through an empty value on its way: only another app counts.
                    now.isEmpty() -> Unit
                    prefs(context).getBoolean(WAS_ASSISTANT, false) -> {
                        Log.i(TAG, "Voice: the assistant changed to $now; not restoring Signal")
                        prefs(context).edit().putBoolean(WAS_ASSISTANT, false).apply()
                    }
                }
            }
        }
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(VOICE_INTERACTION_SERVICE), false, observer)
    }
}
