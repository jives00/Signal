package com.signal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.signal.demo.DemoData
import com.signal.input.RemoteAction
import com.signal.input.RemoteHandler
import com.signal.ui.LocalApp
import com.signal.ui.components.SText
import com.signal.ui.components.formatTime
import com.signal.ui.theme.SignalTheme
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Throwaway guide-grid spike (PLAN §8 → milestone 1, §10): 2,000 channels × 7 days of Demo
 * programs, laid out like handoff screen 2 (88-unit rows, 400-unit channel column, 10.4 units per
 * minute, a now line). It answers one question before milestone 2 builds the real guide: does a
 * virtualized grid scroll at 60fps on the Shield?
 *
 * Focus is one (row, time) position handled at screen level, not a focusable per cell: thousands
 * of focus targets would be the first thing to cost frames. Frame times are measured on screen.
 * Debug builds only; deleted when milestone 2's guide replaces it.
 */
@Composable
fun GuideSpike(onClose: () -> Unit) {
    val c = SignalTheme.colors
    val app = LocalApp.current
    val channels = remember { DemoData.channels() }
    val start = remember { Clock.System.now().toEpochMilliseconds().let { it - it % (30 * 60_000L) } }
    val cache = remember { HashMap<Int, List<DemoData.DemoProgram>>() }
    fun programs(row: Int) = cache.getOrPut(row) { DemoData.programs(row, start) }

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var focusRow by remember { mutableStateOf(0) }
    var focusTime by remember { mutableStateOf(start + 60_000L) } // A moment inside the focused program.
    var windowStart by remember { mutableStateOf(start) }
    var stats by remember { mutableStateOf("measuring…") }

    val pxPerMin = 10.4f
    val visibleMinutes = (1920 - 400) / pxPerMin

    // Frame timing: average and the share of frames over 16.7ms, over the last 120 frames.
    LaunchedEffect(Unit) {
        val times = ArrayDeque<Long>()
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    times.addLast(now - last)
                    if (times.size > 120) times.removeFirst()
                }
                last = now
            }
            if (times.size >= 30 && times.size % 15 == 0) {
                val avg = times.average() / 1_000_000
                val slow = times.count { it > 17_500_000 } * 100 / times.size
                stats = "avg ${(avg * 10).toInt() / 10.0} ms/frame · ${slow}% slow frames"
            }
        }
    }

    fun move(dRow: Int, dProgram: Int) {
        if (dRow != 0) {
            focusRow = (focusRow + dRow).coerceIn(0, channels.lastIndex)
            scope.launch {
                val first = list.firstVisibleItemIndex
                val visible = list.layoutInfo.visibleItemsInfo.size - 1
                if (focusRow < first) list.scrollToItem(focusRow)
                else if (focusRow >= first + visible) list.scrollToItem(focusRow - visible + 1)
            }
        }
        if (dProgram != 0) {
            val progs = programs(focusRow)
            val i = progs.indexOfFirst { focusTime in it.startMs until it.endMs }.coerceAtLeast(0)
            val target = progs[(i + dProgram).coerceIn(0, progs.lastIndex)]
            focusTime = maxOf(target.startMs, start)
            val windowEnd = windowStart + (visibleMinutes * 60_000).toLong()
            if (target.startMs < windowStart) windowStart = maxOf(start, target.startMs)
            else if (target.startMs >= windowEnd - 30 * 60_000L) windowStart = target.startMs - 30 * 60_000L
        }
    }

    RemoteHandler { action ->
        when (action) {
            RemoteAction.Up -> move(-1, 0)
            RemoteAction.Down -> move(1, 0)
            RemoteAction.Left -> move(0, -1)
            RemoteAction.Right -> move(0, 1)
            RemoteAction.ChannelUp -> move(-7, 0)
            RemoteAction.ChannelDown -> move(7, 0)
            RemoteAction.Back -> onClose()
            else -> return@RemoteHandler false
        }
        true
    }

    val use24 = app.settings.bool(com.signal.data.Settings.Key.CLOCK_24H, app.platform.systemUses24HourClock())
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().height(64.dp).background(c.bg)) {
            SText("Spike · ${channels.size} ch", 22, Modifier.width(400.dp).padding(start = 24.dp, top = 18.dp), color = c.muted)
            Box(Modifier.fillMaxSize()) {
                var t = windowStart
                while (t < windowStart + (visibleMinutes * 60_000).toLong()) {
                    val x = ((t - windowStart) / 60_000f * pxPerMin)
                    val local = Instant.fromEpochMilliseconds(t).toLocalDateTime(TimeZone.currentSystemDefault())
                    SText(formatTime(local.hour, local.minute, use24), 22, Modifier.offset(x = x.dp + 12.dp, y = 18.dp), weight = FontWeight.SemiBold, color = c.secondaryText)
                    t += 30 * 60_000L
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, userScrollEnabled = false) {
            items(channels.size, key = { it }) { row ->
                val ch = channels[row]
                Row(Modifier.fillMaxWidth().height(88.dp)) {
                    Row(Modifier.width(400.dp).fillMaxHeight().background(if (row == focusRow) c.surface else c.bg).padding(start = 24.dp, top = 28.dp)) {
                        SText(ch.number.toString(), 22, Modifier.width(72.dp), color = c.muted)
                        SText(ch.name, 24, weight = FontWeight.SemiBold)
                    }
                    Box(Modifier.fillMaxSize()) {
                        val windowEnd = windowStart + (visibleMinutes * 60_000).toLong()
                        // Only the programs that intersect the visible window are composed.
                        for (p in programs(row)) {
                            if (p.endMs <= windowStart) continue
                            if (p.startMs >= windowEnd) break
                            val left = ((maxOf(p.startMs, windowStart) - windowStart) / 60_000f * pxPerMin)
                            val width = ((minOf(p.endMs, windowEnd) - maxOf(p.startMs, windowStart)) / 60_000f * pxPerMin)
                            val focused = row == focusRow && focusTime in p.startMs until p.endMs
                            val now = Clock.System.now().toEpochMilliseconds() in p.startMs until p.endMs
                            Box(
                                Modifier
                                    .offset(x = left.dp)
                                    .width((width - 2).coerceAtLeast(1f).dp)
                                    .fillMaxHeight()
                                    .background(if (focused) c.accent else if (now) c.surface else Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 28.dp),
                            ) {
                                SText(p.title, 24, weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text)
                            }
                        }
                    }
                }
            }
        }
    }
    // Frame timing, readable over the grid.
    SText(stats, 26, Modifier.align(androidx.compose.ui.Alignment.BottomEnd).background(c.accent).padding(horizontal = 24.dp, vertical = 12.dp), weight = FontWeight.SemiBold, color = c.onAccent)
    }
}
