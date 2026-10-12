package com.signal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.signal.model.ChannelGroup

/** Pages and screens (PLAN §4.1). Overlays and side panels live inside their screen, not here. */
sealed interface Route {
    data object Setup : Route
    data object Home : Route
    data object LiveTv : Route
    data class FullScreen(val group: ChannelGroup) : Route
    data object Settings : Route
    /** Debug builds only: the throwaway guide-grid spike (PLAN §8 → milestone 1). */
    data object GuideSpike : Route

    /** The pages on the nav bar; the rest are reached from them. */
    val isTopLevel: Boolean get() = this == Home || this == LiveTv || this == Settings

    /** Screens with a player on them: the only ones that resume playback after sleep (PLAN §4.2). */
    val showsVideo: Boolean get() = this == Home || this == LiveTv || this is FullScreen
}

/**
 * A small back stack (PLAN §4.2 → Back: one rule everywhere). Top-level pages replace each other
 * rather than stacking, so Back from any page goes to the nav bar, then Home, then exits.
 */
class Navigator(start: Route) {
    private val stack = mutableStateListOf(start)
    val current: Route get() = stack.last()

    /** Bumped on every navigation, so screens can re-request initial focus. */
    var generation by mutableStateOf(0)
        private set

    var onNavigate: (Route) -> Unit = {}

    fun go(route: Route) {
        onNavigate(route)
        if (route.isTopLevel || route == Route.Setup) {
            stack.clear()
            stack.add(route)
        } else {
            stack.add(route)
        }
        generation++
    }

    /** Pops one screen. False at the root, where the page itself decides (nav bar, Home, exit). */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        generation++
        return true
    }

    fun resetTo(route: Route) {
        stack.clear()
        stack.add(route)
        generation++
    }
}
