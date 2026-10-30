package com.signal.app

import com.signal.data.Log
import com.signal.data.Settings
import com.signal.model.Source
import com.signal.model.SourceAccount
import com.signal.parse.XtreamParser
import com.signal.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/**
 * Provider accounts (PLAN §9.1 → Provider accounts): which login of a source a play goes through.
 *
 * A device plays from the login it's given (the device setting source_accounts), unless the provider
 * says that login's streams are all in use when playback starts: then it borrows a login with a free
 * one, so it doesn't cut off another TV that shares the login. Changing channels keeps the login in
 * use without asking, because this device's own stream is what makes it look busy, and providers keep
 * counting a stopped stream for a while. When every login is busy it plays from its own anyway.
 */
class ProviderAccounts(
    private val settings: Settings,
    private val log: Log,
    playerState: StateFlow<PlaybackState>,
    scope: CoroutineScope,
    /** The provider's answer for one login; null when it doesn't answer in time. */
    private val check: suspend (Source) -> XtreamParser.AccountInfo?,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** The source and login this device last played from. */
    private var held: Pair<String, String>? = null
    /** When this device last picked a login or stopped playing; while it plays, [playing] is true. */
    private var usedAt = 0L
    private var playing = false

    init {
        scope.launch {
            playerState.map { it.status }.distinctUntilChanged().collect { status ->
                val was = playing
                playing = status == PlaybackState.Status.BUFFERING || status == PlaybackState.Status.PLAYING
                if (was && !playing) usedAt = now()
            }
        }
    }

    /** The login [source] plays from on this device when it's free. */
    fun chosen(source: Source): SourceAccount {
        val id = chosenIds()[source.id]
        return source.logins.firstOrNull { it.id == id } ?: source.logins.first()
    }

    /** Settings → Sources → (source) → Account: this device's login for [sourceId]; [SourceAccount.MAIN] clears it. */
    fun choose(sourceId: String, accountId: String) {
        val ids = chosenIds().toMutableMap()
        if (accountId == SourceAccount.MAIN) ids.remove(sourceId) else ids[sourceId] = accountId
        settings.put(Settings.Key.SOURCE_ACCOUNTS, if (ids.isEmpty()) null else JsonObject(ids.mapValues { JsonPrimitive(it.value) }).toString())
        if (held?.first == sourceId) held = null
    }

    private fun chosenIds(): Map<String, String> = runCatching {
        Json.parseToJsonElement(settings.raw(Settings.Key.SOURCE_ACCOUNTS) ?: return emptyMap()).jsonObject
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    /** [source] with the login to play from now. A source with one login comes back as it is. */
    suspend fun pick(source: Source): Source = source.withLogin(pickLogin(source))

    suspend fun pickLogin(source: Source): SourceAccount {
        val logins = source.logins
        if (logins.size < 2) return logins.first()
        held?.takeIf { it.first == source.id && stillHeld() }?.let { (_, id) -> logins.firstOrNull { it.id == id }?.let { return it } }
        val home = chosen(source)
        val use = when (free(source, home)) {
            false -> borrow(source, logins.filter { it.id != home.id }) ?: home.also {
                log.info("Account: every login of ${source.name} is in use; playing from ${home.name}")
            }
            else -> home
        }
        if (use.id != home.id) log.info("Account: ${home.name} is in use; borrowing ${use.name}")
        held = source.id to use.id
        usedAt = now()
        return use
    }

    /**
     * Failover (PLAN §4.5): the provider refused [source] with its connection limit. Another login not in
     * [tried] that has a free stream, or null.
     */
    suspend fun another(source: Source, tried: Set<String>): SourceAccount? {
        val use = borrow(source, source.logins.filter { it.id !in tried }) ?: return null
        held = source.id to use.id
        usedAt = now()
        return use
    }

    private fun stillHeld() = playing || now() - usedAt < HOLD_MS

    /** The first of [logins] the provider says has a free stream, asked all at once. */
    private suspend fun borrow(source: Source, logins: List<SourceAccount>): SourceAccount? = coroutineScope {
        logins.map { async { it to free(source, it) } }.awaitAll().firstOrNull { it.second == true }?.first
    }

    /** True or false when the provider says; null when it can't tell (no answer, no counts). */
    private suspend fun free(source: Source, login: SourceAccount): Boolean? {
        val info = check(source.withLogin(login)) ?: return null
        if (!info.authenticated) return false
        val max = info.maxConnections?.takeIf { it > 0 } ?: return null
        val active = info.activeConnections ?: return null
        return active < max
    }

    companion object {
        /** How long after a stop this device still counts a login as its own. */
        const val HOLD_MS = 60_000L
    }
}
