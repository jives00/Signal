package com.signal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performTextReplacement
import kotlin.test.assertTrue
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.runSkikoComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.signal.app.AppController
import com.signal.app.Route
import com.signal.data.Log
import com.signal.db.user.UserDatabase
import com.signal.input.RemoteAction
import com.signal.platform.AppInfo
import com.signal.platform.Platform
import com.signal.platform.SecretBox
import com.signal.player.AspectMode
import com.signal.player.PlayRequest
import com.signal.player.PlaybackState
import com.signal.player.Track
import com.signal.player.VideoInfo
import com.signal.player.VideoPlayer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/** Plays nothing, but reports a playing stream with two audio tracks and one subtitle track. */
class TestPlayer : VideoPlayer {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    val played = ArrayList<String>()

    override fun play(request: PlayRequest) {
        played += request.label
        positionMs = request.startPositionMs
        _state.value = PlaybackState(
            status = PlaybackState.Status.PLAYING,
            request = request,
            video = VideoInfo(1920, 1080, 59.94f, "H.264", "AAC", 2),
            audioTracks = listOf(Track("0:0", "English", "en", "AAC"), Track("0:1", "Spanish", "es", "AAC")),
            subtitleTracks = listOf(Track("1:0", "English", "en", null)),
            selectedAudioTrackId = "0:0",
        )
    }

    // On-demand: tests move the position and end the video themselves.
    var positionMs = 0L
    var durationMs = 10 * 60_000L
    override fun position() = com.signal.player.PlaybackPosition(if (_state.value.request?.isLive == false) positionMs else 0, if (_state.value.request?.isLive == false) durationMs else 0)
    override fun seekTo(positionMs: Long) { this.positionMs = positionMs.coerceIn(0, durationMs); _state.value = _state.value.copy(ended = false) }
    override fun setPaused(paused: Boolean) { _state.value = _state.value.copy(paused = paused) }
    fun end() { positionMs = durationMs; _state.value = _state.value.copy(ended = true) }
    /** The stream failed for good, after the player's own retry. */
    fun fail() { _state.value = _state.value.copy(status = PlaybackState.Status.ERROR, error = com.signal.player.PlaybackError(com.signal.player.PlaybackError.Kind.OFFLINE)) }

    override fun stop() { _state.value = PlaybackState() }
    override fun retry() { _state.value.request?.let(::play) }
    override fun selectAudioTrack(trackId: String) { _state.value = _state.value.copy(selectedAudioTrackId = trackId) }
    override fun selectSubtitleTrack(trackId: String?) { _state.value = _state.value.copy(selectedSubtitleTrackId = trackId) }
    override fun setVolume(volume: Float) = Unit
    override fun release() = stop()

    @Composable
    override fun Surface(modifier: Modifier, aspect: AspectMode) = Box(modifier)
}

class TestPlatform : Platform {
    override val appInfo = AppInfo(versionName = "test", buildTag = "dev", isDebug = true, deviceModel = "Test")
    override val secretBox = object : SecretBox {
        override fun encrypt(plain: String) = plain
        override fun decrypt(cipher: String) = cipher
    }
    override val player = TestPlayer()
    override val updater = null
    override val voiceInput = null
    // One database thread at a time, like Android's single connection: the JDBC driver opens one per
    // thread, and two writing at once deadlock with SQLITE_BUSY.
    override val io = Dispatchers.IO.limitedParallelism(1)
    var exits = 0

    // A file, not IN_MEMORY: the in-memory driver shares one connection, so transactions on two threads collide.
    private val dbFile = java.io.File.createTempFile("signal-test", ".db").apply { delete(); deleteOnExit() }
    override fun createUserDatabaseDriver() = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", java.util.Properties().apply { put("journal_mode", "WAL"); put("busy_timeout", "5000") }, UserDatabase.Schema)
    override val guideDirectory: String = java.nio.file.Files.createTempDirectory("signal-guide").toFile().apply { deleteOnExit() }.absolutePath
    override fun createGuideDatabaseDriver(path: String) = JdbcSqliteDriver("jdbc:sqlite:$path", java.util.Properties(), com.signal.db.guide.GuideDatabase.Schema)
    override fun createHttpClient() = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
    override fun systemUses24HourClock() = true
    override fun exitApp() { exits++ }
}

/** One screen under test: the real app, the Demo source, and remote actions sent through the key layer. */
@OptIn(ExperimentalTestApi::class)
class KeyMapScope(val ui: ComposeUiTest, val app: AppController, val platform: TestPlatform) {
    val player get() = platform.player

    /** Sends an action the way the key layer does after mapping a remote key (PLAN §12.3). */
    suspend fun press(action: RemoteAction, times: Int = 1) = repeat(times) {
        settle()
        ui.runOnIdle { app.dispatcher.dispatch(action) }
        settle()
    }

    /** Waits for the UI to settle, letting the test's own queued work run in between. */
    suspend fun settle() {
        repeat(3) { ui.waitForIdle(); yield() }
    }

    suspend fun eventually(what: String = "condition", timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            settle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for $what. Focused: ${focusedText()}, route: ${app.navigator.current}, panel: ${app.panel.top?.title}")
            }
            Thread.sleep(10) // Database work runs on real IO threads.
        }
    }

    /**
     * The text inside the focused element: "" for one without text (the Home hero's video), and
     * null when nothing is focused but the key layer's root.
     */
    fun focusedText(): String? = ui.runOnUiThread {
        val node = ui.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        when {
            node == null || node.id == keyLayerRoot().id -> null
            // The guide grid is one focus target and describes what's focused inside it.
            SemanticsProperties.ContentDescription in node.config -> node.config[SemanticsProperties.ContentDescription].joinToString(" ")
            else -> texts(node).joinToString(" ")
        }
    }

    /** RemoteInput's root: the outermost focusable node. */
    private fun keyLayerRoot(): SemanticsNode {
        val queue = ArrayDeque(listOf(ui.onRoot(useUnmergedTree = true).fetchSemanticsNode()))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (SemanticsProperties.Focused in n.config) return n
            queue.addAll(n.children)
        }
        error("No focusable node")
    }

    suspend fun focusedStartsWith(prefix: String) = eventually("focus on \"$prefix\"") { focusedText()?.startsWith(prefix) == true }

    /** A text field holds focus, editable or not (fields are read-only until OK). */
    fun textFieldFocused(): Boolean = ui.runOnUiThread {
        ui.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()
    }

    /** Types into the focused text field the way the remote does: OK makes it editable first. */
    suspend fun typeInFocusedField(text: String) {
        val editable = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        eventually("a text field focused") { ui.runOnUiThread { ui.onAllNodes(editable and androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty() } }
        val setText = androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.isFocused()
        assertTrue(ui.runOnUiThread { ui.onAllNodes(setText).fetchSemanticsNodes().isEmpty() }, "read-only until OK")
        press(RemoteAction.Select)
        eventually("editable") { ui.runOnUiThread { ui.onAllNodes(setText).fetchSemanticsNodes().isNotEmpty() } }
        ui.onAllNodes(setText)[0].performTextReplacement(text)
    }

    fun shows(text: String): Boolean = ui.runOnUiThread {
        ui.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().any { it.isOnScreen() }
    }

    /** Not inside anything unplaced: a kept-alive page or list that's hidden still has its semantics nodes. */
    private fun SemanticsNode.isOnScreen(): Boolean {
        var info: androidx.compose.ui.layout.LayoutInfo? = layoutInfo
        while (info != null) {
            if (!info.isPlaced) return false
            info = info.parentInfo
        }
        return true
    }

    /** Saves the screen to build/screenshots/[name].png, for looking at a layout. */
    fun screenshot(name: String) {
        val image = ui.onRoot().captureToImage().toAwtImage()
        val dir = java.io.File("build/screenshots").apply { mkdirs() }
        javax.imageio.ImageIO.write(image, "png", java.io.File(dir, "$name.png"))
    }

    suspend fun route(route: Route) = eventually("route $route") { app.navigator.current == route }

    private fun texts(node: SemanticsNode): List<String> =
        (node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) + node.children.flatMap(::texts)
}

/**
 * Runs [block] against the real app (PLAN §12.3: one UI test per screen's key-map row). The app
 * starts with the Demo source loaded, [favorites] of its channels favorited, on [start].
 */
@OptIn(ExperimentalTestApi::class)
fun keyMapTest(
    start: Route = Route.Home,
    withDemo: Boolean = true,
    favorites: Int = 0,
    block: suspend KeyMapScope.() -> Unit,
) = runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(1920f, 1080f), density = androidx.compose.ui.unit.Density(1f)) {
    val platform = TestPlatform()
    startApp(platform, start, withDemo, favorites, block)
}

private suspend fun ComposeUiTest.startApp(platform: TestPlatform, start: Route, withDemo: Boolean, favorites: Int, block: suspend KeyMapScope.() -> Unit) {
    val app = AppController(platform, Log { _, _ -> })
    runBlocking {
        if (withDemo) {
            app.addDemoSource().getOrThrow()
            app.channels.channelsNow(app.channels.groupsNow().first { it.group is com.signal.model.ChannelGroup.Provider }.group)
                .take(favorites)
                .forEach { app.channels.setFavorite(it, true) }
        }
    }
    setContent { SignalApp(app) }
    val scope = KeyMapScope(this, app, platform)
    scope.eventually("the app ready", 10_000) { app.ready }
    runOnIdle { if (withDemo || start == Route.Setup) app.navigator.resetTo(start) }
    scope.settle()
    try {
        scope.block()
    } finally {
        app.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() // Nothing from this test runs on into the next.
    }
}

val KeyMapScope.current get() = app.playback.current.value
val KeyMapScope.panelTitle get() = app.panel.top?.title

suspend fun KeyMapScope.heroFocused() = eventually("the hero focused") { focusedText() == "" && current != null }

/** Home's hero → full screen on the hero's channel. */
suspend fun KeyMapScope.toFullScreen() {
    heroFocused()
    press(com.signal.input.RemoteAction.Select)
    eventually("full screen") { app.navigator.current is Route.FullScreen }
}

/** Live TV, with focus on the playing channel's row. */
suspend fun KeyMapScope.liveTvReady() {
    eventually("Live TV focused on the playing channel") {
        val ch = current
        ch != null && focusedText()?.contains(ch.displayName) == true
    }
}

/** Waits for the Demo guide the app builds after the Demo source is added. */
suspend fun KeyMapScope.guideReady() = eventually("the Demo guide built", timeoutMs = 60_000) { app.guideStore.db != null && !app.guideBuilding }
