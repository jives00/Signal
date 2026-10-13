package com.signal.ui

import com.signal.app.Route
import com.signal.input.RemoteAction
import kotlin.test.Test
import kotlin.test.assertTrue

/** Movies and Series browse (PLAN §4.3.6–4.3.7, §12.1): the category column, the poster grid, Back. */
class VodKeyMapTest {
    @Test fun columnGridAndBack() = keyMapTest(start = Route.Movies) {
        focusedStartsWith("Recently added")
        assertTrue(shows("300 titles"))
        // Right goes into the grid, on the first poster.
        press(RemoteAction.Right)
        eventually("a poster focused") { focusedText()?.startsWith("Recently added") == false && focusedText() != null }
        val first = focusedText()
        press(RemoteAction.Right)
        eventually("the next poster") { focusedText() != first }
        // Back returns to the column, then Back again goes to the nav bar (PLAN §12.1).
        press(RemoteAction.Back)
        focusedStartsWith("Recently added")
        press(RemoteAction.Back)
        focusedStartsWith("Movies")
    }

    @Test fun focusingACategoryShowsIt() = keyMapTest(start = Route.Series) {
        focusedStartsWith("Recently added")
        press(RemoteAction.Down)
        focusedStartsWith("Drama Series")
        eventually("the category's titles") { shows("12 titles") }
    }

    @Test fun longPressSortsTheShelf() = keyMapTest(start = Route.Movies) {
        focusedStartsWith("Recently added")
        press(RemoteAction.Options)
        focusedStartsWith("Recently added") // the Sort panel's first choice
        press(RemoteAction.Down)
        focusedStartsWith("A–Z")
        press(RemoteAction.Select)
        eventually("sorted A–Z") { shows("Sort: A–Z") }
    }
}
