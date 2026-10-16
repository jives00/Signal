package com.signal.tv.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or an update. Receiving it starts Signal's process, and SignalApplication puts
 * Signal back as the assistant when it should be (AssistantKeeper). Nothing else to do here.
 */
class AssistantStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
