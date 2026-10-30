package com.signal.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.data.Log
import com.signal.data.Settings
import com.signal.db.user.UserDatabase
import com.signal.model.ProviderStream
import com.signal.model.Source
import com.signal.model.SourceAccount
import com.signal.model.SourceKind
import com.signal.model.StreamUrls
import com.signal.parse.XtreamParser
import com.signal.player.PlaybackState
import com.signal.sync.SyncFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider accounts (PLAN §9.1 → Provider accounts): which login a play goes through. */
class ProviderAccountsTest {
    private val db = UserDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { UserDatabase.Schema.create(it) })
    private val settings = Settings(db, Dispatchers.IO)
    private val state = MutableStateFlow(PlaybackState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_000_000L

    /** Streams in use per username, out of one each; a username not here doesn't answer. */
    private val inUse = HashMap<String, Int>()
    private val checked = ArrayList<String>()
    private val accounts = ProviderAccounts(settings, Log { _, _ -> }, state, scope, check = { s ->
        synchronized(checked) { checked += s.username }
        inUse[s.username]?.let { XtreamParser.AccountInfo(true, "Active", 1, it, null) }
    }, now = { clock })

    private val source = Source(
        id = "s", name = "Strong", kind = SourceKind.XTREAM, url = "http://host", username = "main", password = "pm",
        accounts = listOf(SourceAccount("a2", "Bedroom", "second", "p2"), SourceAccount("a3", "Den", "third", "p3")),
    )

    @AfterTest fun stop() = scope.cancel()

    private suspend fun setStatus(status: PlaybackState.Status) {
        state.value = PlaybackState(status = status)
        delay(100) // the collector runs on another thread
    }

    @Test fun aFreeHomeLoginPlaysFromIt(): Unit = runBlocking {
        inUse += mapOf("main" to 0, "second" to 0, "third" to 0)
        accounts.choose("s", "a2")
        assertEquals("second", accounts.pick(source).username)
        assertEquals(listOf("second"), checked)
    }

    @Test fun aBusyHomeLoginBorrowsTheFirstFreeOne(): Unit = runBlocking {
        inUse += mapOf("main" to 1, "second" to 1, "third" to 0)
        val picked = accounts.pick(source)
        assertEquals("third", picked.username)
        assertEquals("p3", picked.password)
    }

    @Test fun everyLoginBusyOrNoAnswerPlaysFromHome(): Unit = runBlocking {
        inUse += mapOf("main" to 1, "second" to 1, "third" to 1)
        assertEquals("main", accounts.pick(source).username)
        inUse.clear()
        clock += ProviderAccounts.HOLD_MS * 2
        assertEquals("main", accounts.pick(source).username)
    }

    @Test fun changingChannelsKeepsTheLoginInUseWithoutAsking(): Unit = runBlocking {
        inUse += mapOf("main" to 1, "second" to 0, "third" to 0)
        assertEquals("second", accounts.pick(source).username)
        setStatus(PlaybackState.Status.PLAYING)
        checked.clear()
        // Its own stream now makes "second" look busy, and home is free again: it stays put.
        inUse["second"] = 1
        inUse["main"] = 0
        clock += ProviderAccounts.HOLD_MS * 10
        assertEquals("second", accounts.pick(source).username)
        assertTrue(checked.isEmpty())

        // Stopped: still its own for a while, then asked again.
        setStatus(PlaybackState.Status.IDLE)
        clock += ProviderAccounts.HOLD_MS - 1
        assertEquals("second", accounts.pick(source).username)
        assertTrue(checked.isEmpty())
        clock += ProviderAccounts.HOLD_MS + 1
        assertEquals("main", accounts.pick(source).username)
        assertEquals(listOf("main"), checked)
    }

    @Test fun anotherSkipsTriedAndBusyLogins(): Unit = runBlocking {
        inUse += mapOf("main" to 0, "second" to 0, "third" to 1)
        assertEquals("a2", accounts.another(source, setOf("main"))?.id)
        assertNull(accounts.another(source, setOf("main", "a2")))
    }

    @Test fun aSourceWithOneLoginIsNeverChecked(): Unit = runBlocking {
        val single = source.copy(accounts = emptyList())
        assertEquals("main", accounts.pick(single).username)
        val m3u = source.copy(kind = SourceKind.M3U)
        assertEquals("main", accounts.pick(m3u).username)
        assertTrue(checked.isEmpty())
    }

    @Test fun theChoiceIsADeviceSettingAndMainClearsIt() {
        accounts.choose("s", "a3")
        assertEquals("a3", accounts.chosen(source).id)
        assertEquals("""{"s":"a3"}""", settings.raw(Settings.Key.SOURCE_ACCOUNTS))
        accounts.choose("s", SourceAccount.MAIN)
        assertEquals(SourceAccount.MAIN, accounts.chosen(source).id)
        assertNull(settings.raw(Settings.Key.SOURCE_ACCOUNTS))
        // A login removed in the web admin falls back to the source's own.
        accounts.choose("s", "gone")
        assertEquals(SourceAccount.MAIN, accounts.chosen(source).id)
    }

    @Test fun urlsCarryTheLoginAndAccountsSync() {
        val stream = ProviderStream(id = "x", sourceId = "s", providerId = "42", rawName = "ESPN", groupName = "Sports", logoUrl = null, tvgId = null, providerNumber = null, position = 0, url = null)
        assertEquals("http://host/live/second/p2/42.ts", StreamUrls.live(source.withLogin(source.logins[1]), stream))
        assertEquals("http://host/live/main/pm/42.ts", StreamUrls.live(source.withLogin(source.logins[0]), stream))
        val back = SyncFields.source("s", SyncFields.source(source, 1))
        assertEquals(source.accounts, back.accounts)
        assertNull(SyncFields.source(source.copy(accounts = emptyList()), 1)[SyncFields.ACCOUNTS]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
    }
}
