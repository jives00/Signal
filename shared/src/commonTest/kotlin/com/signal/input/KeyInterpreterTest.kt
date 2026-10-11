package com.signal.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyInterpreterTest {
    private val k = KeyInterpreter()

    @Test
    fun shortOkSelectsOnReleaseWithoutWaiting() {
        val down = k.keyDown(RemoteKey.OK, isRepeat = false)
        assertNull(down.action)
        assertTrue(down.startLongPressTimer)
        assertEquals(RemoteAction.Select, k.keyUp(RemoteKey.OK))
        assertNull(k.longPressElapsed(), "a late timer must not fire after release")
    }

    @Test
    fun heldOkFiresOptionsWhileHeldAndNothingOnRelease() {
        k.keyDown(RemoteKey.OK, isRepeat = false)
        assertEquals(RemoteAction.Options, k.longPressElapsed())
        assertNull(k.keyDown(RemoteKey.OK, isRepeat = true).action)
        assertNull(k.keyUp(RemoteKey.OK))
    }

    @Test
    fun okRepeatsNeverRestartTheTimer() {
        k.keyDown(RemoteKey.OK, isRepeat = false)
        assertTrue(!k.keyDown(RemoteKey.OK, isRepeat = true).startLongPressTimer)
    }

    @Test
    fun directionsRepeatWhileHeld() {
        assertEquals(RemoteAction.Down, k.keyDown(RemoteKey.DOWN, isRepeat = false).action)
        assertEquals(RemoteAction.Down, k.keyDown(RemoteKey.DOWN, isRepeat = true).action)
    }

    @Test
    fun backAndPlayPauseActOncePerPress() {
        assertEquals(RemoteAction.Back, k.keyDown(RemoteKey.BACK, isRepeat = false).action)
        assertNull(k.keyDown(RemoteKey.BACK, isRepeat = true).action)
        assertEquals(RemoteAction.PlayPause, k.keyDown(RemoteKey.PLAY_PAUSE, isRepeat = false).action)
    }

    @Test
    fun digitsMapToTheirValue() {
        assertEquals(RemoteAction.Digit(7), k.keyDown(RemoteKey.DIGIT_7, isRepeat = false).action)
    }

    @Test
    fun resetForgetsAHeldOk() {
        k.keyDown(RemoteKey.OK, isRepeat = false)
        k.reset()
        assertNull(k.longPressElapsed())
        assertNull(k.keyUp(RemoteKey.OK))
    }
}

class RemoteDispatcherTest {
    @Test
    fun focusedItemGetsOkBeforeScreenHandlers() {
        val d = RemoteDispatcher()
        val log = mutableListOf<String>()
        d.push("screen") { log += "screen:$it"; true }
        d.setFocused("item", RemoteDispatcher.FocusedItem(onSelect = { log += "item" }, onOptions = null))
        d.dispatch(RemoteAction.Select)
        assertEquals(listOf("item"), log)
    }

    @Test
    fun itemWithoutOptionsLetsTheScreenHandleLongPress() {
        val d = RemoteDispatcher()
        var handled: RemoteAction? = null
        d.push("screen") { handled = it; true }
        d.setFocused("item", RemoteDispatcher.FocusedItem(onSelect = {}, onOptions = null))
        d.dispatch(RemoteAction.Options)
        assertEquals(RemoteAction.Options, handled)
    }

    @Test
    fun topmostHandlerWinsAndUnhandledFallsThrough() {
        val d = RemoteDispatcher()
        val log = mutableListOf<String>()
        d.push("screen") { log += "screen"; true }
        d.push("panel") { if (it == RemoteAction.Back) { log += "panel"; true } else false }
        d.dispatch(RemoteAction.Back)
        d.dispatch(RemoteAction.Up)
        assertEquals(listOf("panel", "screen"), log)
    }

    @Test
    fun defaultRunsWhenNobodyHandles() {
        val d = RemoteDispatcher()
        var fallback: RemoteAction? = null
        d.onDefault = { fallback = it; true }
        d.dispatch(RemoteAction.Left)
        assertEquals(RemoteAction.Left, fallback)
    }

    @Test
    fun removedHandlersStopReceiving() {
        val d = RemoteDispatcher()
        var calls = 0
        d.push("o") { calls++; true }
        d.remove("o")
        d.dispatch(RemoteAction.Up)
        assertEquals(0, calls)
    }
}
