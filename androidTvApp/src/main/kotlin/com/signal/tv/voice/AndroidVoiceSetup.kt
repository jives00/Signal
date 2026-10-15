package com.signal.tv.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import com.signal.platform.VoiceSetup
import com.signal.platform.VoiceStatus
import com.signal.tv.MainActivity

/** Settings → Voice commands on Android TV (PLAN §4.3.14 → Voice commands). */
class AndroidVoiceSetup(private val context: Context, private val activity: () -> MainActivity?) : VoiceSetup {
    override val packageName: String get() = context.packageName

    override fun status() = VoiceStatus(
        isAssistant = context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
        hasMicrophone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        model = VoskModel.state(context),
    )

    override fun requestMicrophone() {
        activity()?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
    }

    private companion object {
        const val REQUEST_MICROPHONE = 7
    }
}
