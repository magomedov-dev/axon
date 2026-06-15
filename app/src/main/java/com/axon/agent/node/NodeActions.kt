package com.axon.agent.node

/**
 * The one and only node-action table: the set of supported action keys and the
 * `by` selectors, plus which actions require extra params. Pure (no Android), so
 * the parser/validator is unit-testable; the Android id mapping lives next to the
 * actual performAction call in NodeActionHandler.
 */
object NodeActions {
    const val CLICK = "click"
    const val LONG_CLICK = "longClick"
    const val SET_TEXT = "setText"
    const val CLEAR = "clear"
    const val FOCUS = "focus"
    const val CLEAR_FOCUS = "clearFocus"
    const val SELECT = "select"
    const val SET_SELECTION = "setSelection"
    const val SCROLL_FORWARD = "scrollForward"
    const val SCROLL_BACKWARD = "scrollBackward"

    val ACTIONS = setOf(
        CLICK, LONG_CLICK, SET_TEXT, CLEAR, FOCUS, CLEAR_FOCUS,
        SELECT, SET_SELECTION, SCROLL_FORWARD, SCROLL_BACKWARD,
    )

    /** Actions that operate on editable text (need an editable node). */
    val NEEDS_EDITABLE = setOf(SET_TEXT, CLEAR)

    /** Selector keys for `by`. */
    val BY = setOf("resourceId", "text", "class", "contentDesc")
}
