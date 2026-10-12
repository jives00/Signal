package com.signal.guide

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.signal.data.ChannelRepository
import com.signal.data.Settings
import com.signal.data.newId
import com.signal.db.user.UserDatabase
import com.signal.model.Channel
import com.signal.model.Program
import com.signal.model.Reminder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Reminders (PLAN §4.3.4): profile scope, stored against the user channel plus the program's
 * title and start, so they survive a provider switch and follow a program a new guide moves.
 */
class ReminderRepository(
    private val db: UserDatabase,
    private val settings: Settings,
    private val channels: ChannelRepository,
    private val io: CoroutineDispatcher,
) {
    private val profile get() = settings.profileId

    fun reminders(): Flow<List<Reminder>> = db.reminderQueries.remindersFor(profile).asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<Reminder> = withContext(io) { db.reminderQueries.remindersFor(profile).executeAsList().map { it.toModel() } }

    /** Setting a reminder creates the channel's user channel (PLAN §4.5). */
    suspend fun add(channel: Channel, program: Program) {
        val uc = channels.ensureUserChannel(channel)
        withContext(io) {
            db.reminderQueries.insertReminder(
                com.signal.db.user.Reminder(newId(), profile, uc, program.title, program.start, program.stop, 0, Clock.System.now().toEpochMilliseconds()),
            )
        }
    }

    suspend fun remove(id: String) = withContext(io) { db.reminderQueries.deleteReminder(id) }

    suspend fun markFired(id: String) = withContext(io) { db.reminderQueries.markFired(id) }

    suspend fun move(id: String, start: Long, stop: Long) = withContext(io) { db.reminderQueries.moveReminder(start, stop, id) }

    /** A reminder drops off once its program has ended (PLAN §4.3.4). */
    suspend fun removeEnded(now: Long) = withContext(io) { db.reminderQueries.deleteEnded(profile, now) }

    /** The reminder for [program] on [channel], if there is one. */
    fun find(reminders: List<Reminder>, userChannelId: String?, program: Program): Reminder? =
        userChannelId?.let { uc -> reminders.firstOrNull { it.userChannelId == uc && it.title == program.title && it.start == program.start } }

    private fun com.signal.db.user.Reminder.toModel() = Reminder(id, user_channel_id, title, start_ms, stop_ms, fired == 1L)
}
