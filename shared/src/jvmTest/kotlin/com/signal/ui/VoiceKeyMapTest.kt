package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Voice commands acting on the app (PLAN §4.3.14 → Voice commands, phase 2), as the assistant hands them over. */
class VoiceKeyMapTest {
    @Test fun oneClearChannelIsWatchedAtOnce() = keyMapTest {
        val r = app.voice(listOf("watch golf channel"))
        assertEquals("Golf Channel", r.shown, r.message)
        eventually("full screen on Golf Channel") { app.navigator.current is Route.FullScreen && current?.displayName == "Golf Channel" }
        assertEquals(null, panelTitle)
    }

    @Test fun aSharedNameAsksWhichChannel() = keyMapTest {
        val r = app.voice(listOf("fox"))
        assertEquals("Choose a channel", r.shown, r.message)
        eventually("the choice") { panelTitle == "Which channel?" }
        // Rows are labeled as everywhere: number, name, then the group.
        eventually("the first choice focused") { focusedText()?.contains("FOX") == true }
        val first = focusedText()
        press(RemoteAction.Down)
        eventually("the second choice") { focusedText() != first && focusedText()?.contains("FOX") == true }
        val second = focusedText()!!
        press(RemoteAction.Select)
        eventually("full screen on the second choice") { app.navigator.current is Route.FullScreen && current != null && second.contains(current!!.displayName) }
        assertEquals(null, panelTitle)
    }

    @Test fun backClosesTheChoice() = keyMapTest {
        app.voice(listOf("fox"))
        eventually("the choice") { panelTitle == "Which channel?" }
        press(RemoteAction.Back)
        eventually("closed") { panelTitle == null }
        assertEquals(Route.Home, app.navigator.current)
    }

    @Test fun pagesByName() = keyMapTest {
        app.voice(listOf("go to the guide"))
        route(Route.LiveTv)
        app.voice(listOf("open settings"))
        route(Route.Settings)
        app.voice(listOf("go home"))
        route(Route.Home)
    }

    // The recognizer's top guess was a command: it's taken even when a later guess names a channel.
    @Test fun aCommandGuessComesFirst() = keyMapTest {
        app.voice(listOf("guide", "golf channel"))
        route(Route.LiveTv)
    }

    // Live TV has no timeshift: pausing or seeking says so, and the channel plays on (user, 0.2.20).
    @Test fun liveTvCantPauseOrSeek() = keyMapTest {
        toFullScreen()
        val playing = current!!
        val seek = app.voice(listOf("back thirty seconds"))
        assertFalse(seek.acted)
        assertEquals("Live TV can't skip back", seek.shown)
        val pause = app.voice(listOf("pause"))
        assertFalse(pause.acted)
        assertEquals("Live TV can't pause", pause.shown)
        val stop = app.voice(listOf("stop"))
        assertFalse(stop.acted)
        assertEquals("Live TV can't stop", stop.shown)
        settle()
        assertEquals(playing.displayName, player.state.value.request?.label)
    }

    @Test fun theChoiceShowsRenamedGroups() = keyMapTest {
        val local = app.groups.value.first { it.group.title == "Local" }.group as com.signal.model.ChannelGroup.Provider
        app.channels.renameGroup(local, "My Locals")
        eventually("renamed") { app.groups.value.any { it.group.title == "My Locals" } }
        app.voice(listOf("fox"))
        eventually("the choice") { panelTitle == "Which channel?" }
        eventually("the renamed group") { shows("My Locals") }
    }

    @Test fun channelUpAndLastChannel() = keyMapTest(favorites = 3) {
        toFullScreen()
        val first = current!!
        app.voice(listOf("channel down"))
        eventually("the next channel") { current?.key != first.key }
        app.voice(listOf("last channel"))
        eventually("back to the first") { current?.key == first.key }
    }

    @Test fun whatsOnOpensProgramDetails() = keyMapTest {
        guideReady()
        val r = app.voice(listOf("what's on golf channel"))
        assertTrue(r.acted, r.message)
        eventually("program details") { panelTitle != null && panelTitle != "Which channel?" }
        // Nothing was tuned.
        assertEquals(Route.Home, app.navigator.current)
    }

    /**
     * A Demo title only one item of [kind] has, said as a person would: without the catalog's
     * "EN - " or year, lowercase as the recognizer gives it.
     */
    private suspend fun KeyMapScope.uniqueTitle(kind: com.signal.model.VodKind): String {
        val all = app.vod.all()
        val names = all.groupBy { com.signal.match.VodTitles.nameKey(it.name) }
        return all.first { it.kind == kind && names[com.signal.match.VodTitles.nameKey(it.name)]!!.size == 1 }
            .let { com.signal.match.VodTitles.query(it.name).lowercase() }
    }

    // The grammar hears "play [unk]"; the free recognizer has the title.
    @Test fun aMovieByTitlePlays() = keyMapTest {
        val title = uniqueTitle(com.signal.model.VodKind.MOVIE)
        val r = app.voice(listOf("play [unk]"), free = "play $title")
        assertTrue(r.acted, r.message)
        route(Route.VodPlayer)
        assertEquals(title, com.signal.match.VodTitles.query(app.vodPlayback.current.value!!.item.name).lowercase())
    }

    @Test fun anEpisodeBySeasonAndNumber() = keyMapTest {
        val title = uniqueTitle(com.signal.model.VodKind.SERIES)
        val r = app.voice(listOf("play [unk] season one episode two"), free = "play $title season one episode two")
        assertTrue(r.acted, r.message)
        route(Route.VodPlayer)
        val e = app.vodPlayback.current.value?.episode
        assertEquals(1 to 2, e?.season to e?.episode)
    }

    @Test fun searchFillsInSearch() = keyMapTest {
        app.voice(listOf("search [unk]"), free = "search golden")
        route(Route.Search)
        eventually("the query") { shows("golden") }
    }

    @Test fun aTitleThatMatchesNothingOpensSearch() = keyMapTest {
        val r = app.voice(listOf("play [unk]"), free = "play zebra quartz")
        assertEquals("Searching \"zebra quartz\"", r.shown, r.message)
        route(Route.Search)
    }

    // "Play [unk]" isn't "play": words the grammar didn't know make it a name.
    @Test fun aGuessWithUnknownWordsIsNotACommand() = keyMapTest(start = Route.Settings) {
        val r = app.voice(listOf("play [unk]"))
        assertFalse(r.acted)
        assertEquals("Didn't catch that", r.shown)
    }

    // A clear channel still wins over the free recognizer's words.
    @Test fun aClearChannelBeatsATitle() = keyMapTest {
        app.voice(listOf("watch golf channel"), free = "watch golf channel")
        eventually("full screen on Golf Channel") { app.navigator.current is Route.FullScreen && current?.displayName == "Golf Channel" }
    }

    private val voiceReady = object : com.signal.platform.VoiceSetup {
        override fun status() = com.signal.platform.VoiceStatus(true, true, com.signal.platform.VoiceStatus.Model.READY)
        override fun requestMicrophone() = Unit
        override val packageName = "com.signal.tv"
    }

    // Settings → What to say lists every command, and Up/Down scroll through it.
    @Test fun settingsListsWhatToSay() = keyMapTest(start = Route.Settings, voice = voiceReady) {
        focusedStartsWith("Sources")
        while (focusedText() != "What to say") press(RemoteAction.Down)
        press(RemoteAction.Right)
        eventually("the list") { focusedText()?.startsWith("Press the mic button") == true }
        screenshot("what-to-say")
        repeat(40) { if (!shows("\"play The Office season two episode five\"")) press(RemoteAction.Down) }
        assertTrue(shows("Plays that episode."))
    }

    // 0.2.23 on the Shield: the grammar forced "play matrix" into channel words ("play me truckers"),
    // and a title by those words came up. With the free recognizer's words, its title wins.
    @Test fun theFreeRecognizersTitleBeatsTheGrammarsWords() = keyMapTest {
        val title = uniqueTitle(com.signal.model.VodKind.MOVIE)
        val r = app.voice(listOf("play golf", "play the news"), free = "play $title")
        assertTrue(r.acted, r.message)
        route(Route.VodPlayer)
        assertEquals(title, com.signal.match.VodTitles.query(app.vodPlayback.current.value!!.item.name).lowercase())
    }

    // Without the free recognizer, the grammar's words name a title only exactly: "golden" is part of
    // several Demo titles, and it isn't a title of its own.
    @Test fun grammarWordsNameATitleOnlyExactly() = keyMapTest(start = Route.Settings) {
        val r = app.voice(listOf("play golden"))
        assertFalse(r.acted, r.message)
        assertEquals(null, panelTitle)
    }

    // ---- Games and reminders (step 4) ----

    /**
     * Makes Golf Channel's program on now (or its next one, [later]) a game with teams in the title,
     * as the NAS's GameLookup does, search text included. Returns the program's channel.
     */
    private suspend fun KeyMapScope.makeGame(title: String, later: Boolean = false): com.signal.model.Channel {
        guideReady()
        val ch = app.search.channels("golf channel").first { it.displayName == "Golf Channel" }
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val p = app.guide.programs(ch, now, now + 6 * 60 * 60_000L).first { if (later) it.start > now else it.isOn(now) }
        val q = app.guideStore.db!!.guideQueries
        q.setProgramTitle(title, p.id)
        q.deleteSearchText(p.id)
        q.insertSearchText(p.id, com.signal.match.SearchText.fold(title), p.subTitle?.let(com.signal.match.SearchText::fold).orEmpty())
        return ch
    }

    @Test fun watchTheGameOnNow() = keyMapTest {
        val ch = makeGame("NFL Football - Pittsburgh Steelers vs. Cleveland Browns")
        val r = app.voice(listOf("watch the steelers game"))
        assertTrue(r.acted, r.message)
        eventually("full screen on the game's channel") { app.navigator.current is Route.FullScreen && current?.key == ch.key }
        // The city works too.
        app.navigator.resetTo(Route.Home)
        assertTrue(app.voice(listOf("put on the cleveland game")).acted)
    }

    @Test fun aGameLaterSaysWhen() = keyMapTest {
        makeGame("NFL Football - Pittsburgh Steelers vs. Cleveland Browns", later = true)
        val r = app.voice(listOf("watch the steelers game"))
        assertFalse(r.acted)
        assertTrue(r.shown.startsWith("Pittsburgh Steelers vs. Cleveland Browns starts"), r.shown)
        assertTrue(r.shown.endsWith("on Golf Channel"), r.shown)
    }

    @Test fun remindAboutTheNextGame() = keyMapTest {
        makeGame("NFL Football - Pittsburgh Steelers vs. Cleveland Browns", later = true)
        val r = app.voice(listOf("remind me about the steelers game"))
        assertTrue(r.shown.startsWith("Reminder set: Pittsburgh Steelers vs. Cleveland Browns"), r.message)
        eventually("the reminder") { app.reminderList.value.any { it.title.endsWith("Pittsburgh Steelers vs. Cleveland Browns") } }
        // Asking again says it's already set.
        assertTrue(app.voice(listOf("remind me about the steelers game")).shown.startsWith("Already reminding you"))
    }

    // Program titles come from the free recognizer: the grammar doesn't know them.
    @Test fun remindAboutAProgramByTitle() = keyMapTest {
        makeGame("Zebra Quartz Hour", later = true)
        val r = app.voice(listOf("remind me about [unk]"), free = "remind me about zebra quartz hour")
        assertTrue(r.shown.startsWith("Reminder set: Zebra Quartz Hour"), r.message)
        eventually("the reminder") { app.reminderList.value.any { it.title == "Zebra Quartz Hour" } }
    }

    @Test fun teamsJoinTheGrammar() = keyMapTest {
        makeGame("NFL Football - Pittsburgh Steelers vs. Cleveland Browns")
        val phrases = app.voicePhrases()
        for (p in listOf("steelers game", "pittsburgh steelers game", "cleveland", "browns")) assertTrue(p in phrases, p)
    }

    // ---- Channels to match ----

    // By default, with favorites set, only they (and playlists and voice names) are matched.
    @Test fun favoritesAreWhatVoiceMatches() = keyMapTest(favorites = 3) {
        val favorite = app.channels.channelsNow(com.signal.model.ChannelGroup.Favorites).first()
        val r = app.voice(listOf("watch ${favorite.displayName.lowercase()}"))
        assertTrue(r.acted, r.message)
        eventually("the favorite") { current?.stream?.id == favorite.stream.id }
        // Tennis TV is visible but not a favorite.
        app.navigator.resetTo(Route.Home)
        assertFalse(app.voice(listOf("watch tennis tv")).acted)
        assertFalse("tennis" in app.voicePhrases())
        // All visible channels: it's matched again.
        app.settings.put(com.signal.data.Settings.Key.VOICE_CHANNELS, com.signal.search.SearchRepository.VOICE_ALL)
        eventually("the setting saved") { app.settings.raw(com.signal.data.Settings.Key.VOICE_CHANNELS) != null }
        assertTrue(app.voice(listOf("watch tennis tv")).acted)
        assertTrue("tennis" in app.voicePhrases())
    }

    // With no favorites, playlists or voice names yet, every visible channel counts.
    @Test fun withNoFavoritesEveryChannelCounts() = keyMapTest {
        assertTrue(app.voice(listOf("watch tennis tv")).acted)
    }

    @Test fun nothingPlayingSaysSo() = keyMapTest(start = Route.Settings) {
        val r = app.voice(listOf("pause"))
        assertFalse(r.acted)
        assertEquals("Nothing is playing", r.shown)
    }
}
