package com.signal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.signal.player.AspectMode
import com.signal.player.VideoPlayer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * One video view for the whole app, in a layer behind every screen (PLAN §5.6 → "one player
 * instance whose video output moves between surfaces"). Screens don't own video views; a
 * [VideoBox] only marks where the video should appear, and the single view moves and resizes there.
 *
 * Why: on Android the video draws through a hole in the window. A video view created fresh for
 * full screen didn't show on the Shield until something else forced a redraw (audio over a grey
 * screen). Resizing a view that already exists never has that problem, and the decoder never
 * changes surface, so the preview goes full screen without re-buffering.
 */
@Stable
class VideoLayer {
    /** Where the video goes, in root pixels; null when no screen shows video. */
    var bounds by mutableStateOf<Rect?>(null)
        private set
    var aspect by mutableStateOf(AspectMode.FIT)
        private set
    private var owner: Any? = null
    internal var releasedAt by mutableStateOf(0L)
        private set

    fun claim(owner: Any, bounds: Rect, aspect: AspectMode) {
        this.owner = owner
        this.bounds = bounds
        this.aspect = aspect
    }

    /** Only the current owner can release, so a screen leaving never hides the next screen's video. */
    fun release(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            releasedAt++
        }
    }

    internal fun clearIfUnowned() {
        if (owner == null) bounds = null
    }
}

val LocalVideoLayer = staticCompositionLocalOf { VideoLayer() }

/**
 * Hosts the one video view. Put it at the root, before (under) the screens. The view always stays
 * composed; with no video slot on screen it collapses to nothing rather than being destroyed.
 */
@Composable
fun VideoLayerHost(layer: VideoLayer, player: VideoPlayer) {
    // Navigating swaps slots: the old screen releases a moment before the new one claims. Wait
    // briefly before collapsing, so the view keeps its surface across the swap.
    LaunchedEffect(layer.releasedAt) {
        delay(150)
        layer.clearIfUnowned()
    }
    val b = layer.bounds
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.layout { measurable, _ ->
                val w = b?.width?.roundToInt()?.coerceAtLeast(0) ?: 0
                val h = b?.height?.roundToInt()?.coerceAtLeast(0) ?: 0
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(b?.left?.roundToInt() ?: 0, b?.top?.roundToInt() ?: 0) }
            },
        ) {
            player.Surface(Modifier.fillMaxSize(), layer.aspect)
        }
    }
}
