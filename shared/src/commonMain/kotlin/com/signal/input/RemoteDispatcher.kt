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

    private val handlers = ArrayList<Pair<Any, Handler>>()
    var focusedItem: FocusedItem? = null
        private set
    private var focusedOwner: Any? = null

    var onDefault: (RemoteAction) -> Boolean = { false }

    fun push(owner: Any, handler: Handler) {
        handlers.removeAll { it.first === owner }
        handlers += owner to handler
    }

    fun remove(owner: Any) {
        handlers.removeAll { it.first === owner }
    }

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
        for (i in handlers.indices.reversed()) {
            if (handlers[i].second.handle(action)) return true
        }
        return onDefault(action)
    }
}
