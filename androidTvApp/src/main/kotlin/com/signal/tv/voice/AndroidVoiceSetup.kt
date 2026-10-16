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
        // The role, or the setting the system binds from: after AssistantKeeper restores Signal, the
        // role still names Google but Signal is the one that answers the mic.
        isAssistant = context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true || AssistantKeeper.isAssistant(context),
        hasMicrophone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        model = VoskModel.state(context),
        keepsAssistant = AssistantKeeper.canKeep(context),
    )

    override fun requestMicrophone() {
        activity()?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
    }

    private companion object {
        const val REQUEST_MICROPHONE = 7
    }
}
