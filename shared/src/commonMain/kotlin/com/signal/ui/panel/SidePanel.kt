package com.signal.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signal.input.RemoteAction
import com.signal.input.RemoteDispatcher
import com.signal.input.RemoteHandler
import com.signal.input.remoteItem
import com.signal.ui.components.HDivider
import com.signal.ui.components.ListRow
import com.signal.ui.components.SButton
import com.signal.ui.components.SText
import com.signal.ui.components.VDivider
import com.signal.ui.theme.SignalTheme

/** One page of the side panel. Pages stack: Back pops one, and at the first page closes the panel. */
sealed interface PanelPage {
    val title: String

    data class Actions(override val title: String, val actions: List<PanelAction>, val subtitle: String? = null) : PanelPage

    data class TextInput(
        override val title: String,
        val initial: String,
        val digitsOnly: Boolean = false,
        val confirmLabel: String = "Save",
        val onDone: (String) -> Unit,
    ) : PanelPage

    /** One-step confirmation for destructive actions (PLAN §4.2 → The side panel). */
    data class Confirm(override val title: String, val message: String, val confirmLabel: String, val onConfirm: () -> Unit) : PanelPage

    data class Custom(override val title: String, val content: @Composable () -> Unit) : PanelPage
}

data class PanelAction(
    val label: String,
    val detail: String? = null,
    /** Marks the current choice in a picker. */
    val selected: Boolean = false,
    /** Run the action; return a page to push, or null to close the panel. */
    val onSelect: () -> PanelPage?,
)

/**
 * The right-hand, one-third-width side panel (PLAN §2.3, §4.2). One instance for the app; screens
 * open it with the focus to return to when it closes.
 */
class SidePanelState {
    private val pages = mutableStateListOf<PanelPage>()
    private var returnFocus: FocusRequester? = null

    val isOpen: Boolean get() = pages.isNotEmpty()
    val top: PanelPage? get() = pages.lastOrNull()

    fun open(page: PanelPage, returnTo: FocusRequester?) {
        pages.clear()
        pages.add(page)
        returnFocus = returnTo
    }

    fun push(page: PanelPage) {
        pages.add(page)
    }

    fun back() {
        if (pages.size > 1) pages.removeAt(pages.lastIndex) else close()
    }

    fun close() {
        pages.clear()
        val target = returnFocus
        returnFocus = null
        target?.let { runCatching { it.requestFocus() } }
    }

    /** Runs an action: pushes the page it returns, or closes the panel when it returns null. */
    fun run(action: PanelAction) {
        val next = action.onSelect()
        if (next == null) close() else push(next)
    }
}

@Composable
fun SidePanelHost(state: SidePanelState) {
    val page = state.top ?: return
    val c = SignalTheme.colors
    RemoteHandler(priority = RemoteDispatcher.Priority.PANEL) { action ->
        when (action) {
            RemoteAction.Back -> { state.back(); true }
            // The panel is modal: keys never leak to the screen beneath it.
            RemoteAction.Left, RemoteAction.Right, RemoteAction.PlayPause, RemoteAction.Rewind,
            RemoteAction.FastForward, RemoteAction.ChannelUp, RemoteAction.ChannelDown, is RemoteAction.Digit -> true
            else -> false
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Row(Modifier.width(640.dp).fillMaxHeight().background(c.bg)) {
            VDivider()
            Column(Modifier.fillMaxSize()) {
                SText(page.title, 32, Modifier.padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 24.dp), weight = FontWeight.ExtraBold, maxLines = 2)
                HDivider()
                // Keyed by page, so each page gets fresh focus on its first item.
                androidx.compose.runtime.key(page) {
                    when (page) {
                        is PanelPage.Actions -> ActionsPage(state, page)
                        is PanelPage.TextInput -> TextInputPage(state, page)
                        is PanelPage.Confirm -> ConfirmPage(state, page)
                        is PanelPage.Custom -> page.content()
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsPage(state: SidePanelState, page: PanelPage.Actions) {
    val first = remember { FocusRequester() }
    page.subtitle?.let { SText(it, 22, Modifier.padding(start = 40.dp, top = 16.dp, end = 40.dp), color = SignalTheme.colors.muted, maxLines = 3) }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(page.actions) { i, action ->
            ListRow(
                text = action.label,
                detail = action.detail,
                selected = action.selected,
                onSelect = { state.run(action) },
                focusRequester = if (i == 0) first else null,
            )
        }
    }
    LaunchedEffect(page) { runCatching { first.requestFocus() } }
}

@Composable
private fun TextInputPage(state: SidePanelState, page: PanelPage.TextInput) {
    val c = SignalTheme.colors
    var value by remember { mutableStateOf(page.initial) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    Column(Modifier.padding(40.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(if (fieldFocused) c.accent else c.surface)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { v -> value = if (page.digitsOnly) v.filter(Char::isDigit).take(5) else v },
                singleLine = true,
                textStyle = TextStyle(color = if (fieldFocused) c.onAccent else c.text, fontSize = 28.sp, fontFamily = SignalTheme.font),
                cursorBrush = SolidColor(if (fieldFocused) c.onAccent else c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = if (page.digitsOnly) KeyboardType.Number else KeyboardType.Text),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    // OK opens the on-screen keyboard, which also has voice input (PLAN §4.2).
                    .remoteItem(onSelect = { keyboard?.show() }, isTextInput = true),
            )
        }
        Row(Modifier.padding(top = 32.dp)) {
            SButton(page.confirmLabel, onSelect = {
                keyboard?.hide()
                page.onDone(value)
                state.close()
            }, primary = true)
        }
    }
    LaunchedEffect(page) { runCatching { field.requestFocus() } }
}

@Composable
private fun ConfirmPage(state: SidePanelState, page: PanelPage.Confirm) {
    val cancel = remember { FocusRequester() }
    Column(Modifier.padding(40.dp)) {
        SText(page.message, 26, maxLines = 6)
        Column(Modifier.padding(top = 40.dp)) {
            // Cancel takes focus first, so a stray OK never destroys anything.
            SButton("Cancel", onSelect = { state.back() }, focusRequester = cancel)
            Box(Modifier.height(16.dp))
            SButton(page.confirmLabel, onSelect = {
                page.onConfirm()
                state.close()
            })
        }
    }
    LaunchedEffect(page) { runCatching { cancel.requestFocus() } }
}
