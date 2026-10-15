package com.signal.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.signal.input.remoteItem
import com.signal.model.initials
import com.signal.ui.theme.SignalSizes
import com.signal.ui.theme.SignalTheme

/** Text in Archivo at a design-unit size (PLAN §3). Never below 20 units. */
@Composable
fun SText(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = SignalTheme.colors.text,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = sTextStyle(size, weight, color),
    )
}

@Composable
private fun sTextStyle(size: Int, weight: FontWeight, color: Color) = TextStyle(
    color = color,
    fontSize = size.coerceAtLeast(SignalSizes.MIN_TEXT).sp,
    fontWeight = weight,
    fontFamily = SignalTheme.font,
    letterSpacing = if (weight == FontWeight.ExtraBold && size >= 48) (-0.02 * size).sp else 0.sp,
)

/**
 * A description shown in full: when it's taller than its space, it waits a moment, scrolls slowly to
 * the end, waits, and goes back to the top, over and over. The space is [lines] lines, or whatever
 * height [modifier] bounds it to when [lines] is null. It never takes focus.
 */
@Composable
fun AutoScrollText(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = SignalTheme.colors.text,
    weight: FontWeight = FontWeight.Normal,
    lines: Int? = null,
) {
    val style = sTextStyle(size, weight, color)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val lineHeight = remember(style, density) { with(density) { measurer.measure("Ag", style).size.height.toDp() } }
    val scroll = rememberScrollState()
    val unitsPerSecond = with(density) { AUTO_SCROLL_SPEED.dp.toPx() }
    LaunchedEffect(text) {
        scroll.scrollTo(0)
        snapshotFlow { scroll.maxValue }.collectLatest { max ->
            // Int.MAX_VALUE until the first layout.
            if (max <= 0 || max == Int.MAX_VALUE) return@collectLatest
            while (true) {
                delay(AUTO_SCROLL_PAUSE_MS)
                scroll.animateScrollTo(max, tween((max / unitsPerSecond * 1000).toInt(), easing = LinearEasing))
                delay(AUTO_SCROLL_PAUSE_MS)
                scroll.animateScrollTo(0, tween(AUTO_SCROLL_BACK_MS))
            }
        }
    }
    val bounded = if (lines != null) modifier.heightIn(max = lineHeight * lines) else modifier
    Box(bounded.clipToBounds().verticalScroll(scroll, enabled = false)) {
        BasicText(text = text, style = style)
    }
}

/** How long a description rests at the top and the end, how fast it scrolls (units a second), and the trip back up. */
private const val AUTO_SCROLL_PAUSE_MS = 2_500L
private const val AUTO_SCROLL_SPEED = 30
private const val AUTO_SCROLL_BACK_MS = 600

@Composable
fun HDivider(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(SignalSizes.DIVIDER.dp).background(SignalTheme.colors.divider))

@Composable
fun VDivider(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxHeight().width(SignalSizes.DIVIDER.dp).background(SignalTheme.colors.divider))

/**
 * A focusable element that responds to OK and long-press OK through the key layer, and tells its
 * content whether it's focused, so the content can draw the accent focus fill (PLAN §3).
 */
@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    onSelect: (() -> Unit)?,
    onOptions: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onFocusChanged {
                if (it.isFocused && !focused) onFocused?.invoke()
                focused = it.isFocused
            }
            .remoteItem(onSelect, onOptions)
            .focusable(),
    ) { content(focused) }
}

/** A flush-left list row: accent fill with white text when focused, `surface` when selected. */
@Composable
fun ListRow(
    text: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onSelect: (() -> Unit)?,
    onOptions: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    size: Int = 26,
    leading: (@Composable (focused: Boolean) -> Unit)? = null,
) {
    val c = SignalTheme.colors
    // Disabled rows are shown but never take focus, so D-pad skips them.
    val rowModifier = if (enabled) modifier.fillMaxWidth() else modifier.fillMaxWidth().focusProperties { canFocus = false }
    Focusable(rowModifier, onSelect = onSelect, onOptions = onOptions, focusRequester = focusRequester, onFocused = onFocused) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) c.accent else if (selected) c.surface else Color.Transparent)
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke(focused)
            SText(
                text, size,
                Modifier.weight(1f),
                weight = FontWeight.SemiBold,
                color = when {
                    focused -> c.onAccent
                    !enabled -> c.muted
                    else -> c.text
                },
            )
            if (detail != null) {
                SText(detail, 22, Modifier.padding(start = 24.dp), color = if (focused) c.onAccent else c.muted)
            }
        }
    }
}

/** A flush-left button (PLAN §3 → movie detail's button rows): outlined, and accent only when focused, so one button looks focused at a time. */
@Composable
fun SButton(
    text: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    /** A chosen option among several (the source form's kind): `surface` fill, like [ListRow]. Only focus uses the accent. */
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    /** Long-press OK: movie detail opens its menu from any button (PLAN §4.3.8). */
    onOptions: (() -> Unit)? = null,
    /** Fill the width [modifier] gives it: movie detail's 2×2 grid. */
    fillWidth: Boolean = false,
    /** Drawn instead of [text], which stays as its description (Search's mic). */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val c = SignalTheme.colors
    Focusable(modifier, onSelect = onSelect, onOptions = onOptions, focusRequester = focusRequester) { focused ->
        Box(
            (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .height(72.dp)
                .background(if (focused) c.accent else if (selected) c.surface else Color.Transparent)
                .let { if (!focused && !selected) it.border(SignalSizes.DIVIDER.dp, c.divider) else it }
                .padding(horizontal = if (icon != null) 20.dp else 32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (icon != null) Icon(icon, 32, if (focused) c.onAccent else c.text, Modifier.semantics { contentDescription = text })
            else SText(text, 26, weight = FontWeight.SemiBold, color = if (focused) c.onAccent else c.text)
        }
    }
}

/** Logo tile: the channel's logo, or its initials until one loads and as the fallback (PLAN §3). */
@Composable
fun LogoTile(name: String, logoUrl: String?, width: Int, height: Int, modifier: Modifier = Modifier, onAccent: Boolean = false, tile: Boolean = true) {
    val c = SignalTheme.colors
    Box(
        modifier.size(width.dp, height.dp).background(if (!tile) Color.Transparent else if (onAccent) Color.White.copy(alpha = 0.16f) else c.neutral400),
        contentAlignment = Alignment.Center,
    ) {
        val initials = @Composable {
            SText(name.initials(), (height * 0.4f).toInt().coerceAtLeast(20), weight = FontWeight.ExtraBold, color = if (onAccent) c.onAccent else c.secondaryText)
        }
        ImageOrPlaceholder(logoUrl, null, ContentScale.Fit, Modifier.fillMaxSize().padding(4.dp), placeholder = initials)
    }
}

/**
 * An image with an optional fallback URL, and [placeholder] shown until one of them loads (or when
 * both fail). It avoids `SubcomposeAsyncImage`, which subcomposes every image: in the guide's channel
 * column and the poster grids that cost 50 ms or more per frame on the Shield (PLAN §8 → milestone 5).
 */
@Composable
fun ImageOrPlaceholder(
    url: String?,
    fallbackUrl: String?,
    contentScale: ContentScale,
    imageModifier: Modifier,
    /** False: nothing while loading, and [placeholder] only with no image or when it fails. Saves a text layout per poster. */
    placeholderWhileLoading: Boolean = true,
    placeholder: @Composable () -> Unit,
) {
    var model by remember(url, fallbackUrl) { mutableStateOf(url?.takeIf { it.isNotBlank() } ?: fallbackUrl?.takeIf { it.isNotBlank() }) }
    var loaded by remember(url, fallbackUrl) { mutableStateOf(false) }
    var failed by remember(url, fallbackUrl) { mutableStateOf(false) }
    if (model == null || failed || (!loaded && placeholderWhileLoading)) placeholder()
    model?.let { m ->
        coil3.compose.AsyncImage(
            model = m,
            contentDescription = null,
            contentScale = contentScale,
            modifier = imageModifier,
            onSuccess = { loaded = true },
            onError = {
                loaded = false
                val fallback = fallbackUrl?.takeIf { it.isNotBlank() }
                if (fallback != null && m != fallback) model = fallback else failed = true
            },
        )
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Int = 6, onAccent: Boolean = false) {
    val c = SignalTheme.colors
    Box(modifier.fillMaxWidth().height(height.dp).background(if (onAccent) Color.White.copy(alpha = 0.35f) else c.neutral300)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(if (onAccent) Color.White else c.accent))
    }
}

/** A titled column of rows, used by Settings sections and side panel pages. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    SText(text, 28, modifier.padding(start = 40.dp, top = 32.dp, bottom = 16.dp), weight = FontWeight.ExtraBold)
}

@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        content()
    }
}

/**
 * Requests focus once the target exists. A lazy list composes its items during layout, after the
 * effect that asks, so the first try can miss; retry for a few frames.
 */
suspend fun FocusRequester.requestFocusWhenReady(frames: Int = 5) {
    repeat(frames) {
        if (runCatching { requestFocus() }.getOrDefault(false)) return
        withFrameNanos { }
    }
}
