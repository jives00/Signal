package com.signal.input

/**
 * Routes an action to whoever should handle it (PLAN §12.3):
 * 1. The focused item gets [RemoteAction.Select] and [RemoteAction.Options] first.
 * 2. Then the handler stack, topmost first: an open side panel, an overlay, then the screen.
 * 3. Then the default: directions move focus, Back goes up a level.
 */
class RemoteDispatcher {
    fun interface Handler {
        fun handle(action: RemoteAction): Boolean
    }

    /** What the focused element does on OK and long-press OK. */
    class FocusedItem(
        val onSelect: (() -> Unit)?,
        val onOptions: (() -> Unit)?,
        /** Text fields keep raw typing keys (digits, Backspace, Space) for themselves. */
        val isTextInput: Boolean = false,
    )

    /** Layers, lowest first. Within a layer, the most recently registered handler wins. */
    object Priority {
        const val SCREEN = 0
        const val OVERLAY = 10
        const val PANEL = 20
        const val PROMPT = 30
    }

    private class Entry(val owner: Any, val priority: Int, val seq: Long, val handler: Handler)

    private val handlers = ArrayList<Entry>()
    private var seq = 0L
    var focusedItem: FocusedItem? = null
        private set
    private var focusedOwner: Any? = null

    var onDefault: (RemoteAction) -> Boolean = { false }

    fun push(owner: Any, priority: Int = Priority.SCREEN, handler: Handler) {
        handlers.removeAll { it.owner === owner }
        handlers += Entry(owner, priority, seq++, handler)
        handlers.sortWith(compareBy<Entry>({ it.priority }, { it.seq }))
    }

    fun remove(owner: Any) {
        handlers.removeAll { it.owner === owner }
    }

    /** True while any handler at [priority] or above is registered, e.g. a side panel is open. */
    fun hasLayer(priority: Int): Boolean = handlers.any { it.priority >= priority }

    fun setFocused(owner: Any, item: FocusedItem) {
        focusedOwner = owner
        focusedItem = item
    }

    fun clearFocused(owner: Any) {
        if (focusedOwner === owner) {
            focusedOwner = null
            focusedItem = null
        }
    }

    fun dispatch(action: RemoteAction): Boolean {
        val item = focusedItem
        when (action) {
            RemoteAction.Select -> item?.onSelect?.let { it(); return true }
            RemoteAction.Options -> item?.onOptions?.let { it(); return true }
            else -> {}
        }
        for (entry in handlers.toList().asReversed()) {
            if (entry.handler.handle(action)) return true
        }
        return onDefault(action)
    }
}
