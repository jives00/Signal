package com.signal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.input.RemoteAction
import com.signal.input.RemoteDispatcher
import com.signal.input.RemoteHandler
import com.signal.ui.LocalApp
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme

/**
 * The reminder prompt (PLAN §4.3.4 → Reminders): in the corner over any screen, for 15 seconds. It
 * never takes focus, so D-pad navigation carries on; OK is Watch and Back is Dismiss while it shows.
 */
@Composable
fun ReminderPrompt() {
    val app = LocalApp.current
    val active = app.activeReminder ?: return
    val c = SignalTheme.colors
    RemoteHandler(priority = RemoteDispatcher.Priority.PROMPT) { action ->
        when (action) {
            RemoteAction.Select -> { app.watchReminder(active); true }
            RemoteAction.Back -> { app.dismissReminder(); true }
            else -> false
        }
    }
    Box(Modifier.fillMaxSize().padding(SignalSizes.EDGE_MARGIN.dp), contentAlignment = Alignment.TopEnd) {
        Column(Modifier.width(620.dp).background(c.surface).padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SignalIcons.bell, 24, c.accentText)
                SText("Starting now", 22, Modifier.padding(start = 12.dp), weight = FontWeight.SemiBold, color = c.accentText)
            }
            SText(active.reminder.title, 32, Modifier.padding(top = 8.dp), weight = FontWeight.ExtraBold, maxLines = 2)
            SText("on ${labelOf(active.channel)}", 24, color = c.secondaryText)
            Row(Modifier.padding(top = 20.dp)) {
                SText("OK", 22, Modifier.background(c.accent).padding(horizontal = 12.dp, vertical = 4.dp), weight = FontWeight.SemiBold, color = c.onAccent)
                SText("Watch", 22, Modifier.padding(start = 12.dp, end = 32.dp, top = 4.dp), weight = FontWeight.SemiBold)
                SText("Back", 22, Modifier.background(c.neutral300).padding(horizontal = 12.dp, vertical = 4.dp), weight = FontWeight.SemiBold)
                SText("Dismiss", 22, Modifier.padding(start = 12.dp, top = 4.dp), weight = FontWeight.SemiBold)
            }
        }
    }
}
