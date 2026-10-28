package com.busys8.engine.apps

import com.busys8.core.Align
import com.busys8.core.ApiFont
import com.busys8.core.Gfx
import com.busys8.engine.input.InputKey

/**
 * A port of gui/modules/var_item_list.c's interaction: the dial moves between items, OK/START
 * starts or stops editing the focused item, the dial changes the value while editing, and BACK
 * stops editing (or, when not editing, is left to the caller to leave the screen).
 *
 * The front display shows one item at a time: its label, and its value on the right with
 * arrows while editing. The back display shows the whole list.
 */
class ListMenu(private val title: String? = null, private val items: () -> List<Item>) {
    /** When the title was last flashed; 0 = on the next render. */
    private var titleShownAtMs = 0L

    /** Shows the page title on the front display again (e.g. when returning to this page). */
    fun flashTitle() {
        titleShownAtMs = 0
    }

    sealed interface Item {
        val label: String
        val visible: Boolean get() = true
    }

    /** Opens something else when selected (a sub-screen). */
    class Action(override val label: String, val value: () -> String = { "" }, val onSelect: () -> Unit) : Item

    /** A value stepped with the dial between [min] and [max]. */
    class Number(
        override val label: String,
        val min: Int,
        val max: Int,
        val step: Int,
        val get: () -> Int,
        val set: (Int) -> Unit,
        val format: (Int) -> String = { it.toString() },
        override val visible: Boolean = true,
    ) : Item

    /** One of a list of named options. */
    class Choice(
        override val label: String,
        val options: List<String>,
        val get: () -> Int,
        val set: (Int) -> Unit,
        override val visible: Boolean = true,
    ) : Item

    class Switch(override val label: String, val get: () -> Boolean, val set: (Boolean) -> Unit, override val visible: Boolean = true) : Item

    var focus = 0
        private set
    var editing = false
        private set

    private fun shown() = items().filter { it.visible }

    /** Handles a short press; returns false for BACK when not editing (the caller leaves). */
    fun input(key: InputKey): Boolean {
        val list = shown()
        if (list.isEmpty()) return false
        focus = focus.coerceIn(0, list.lastIndex)
        val item = list[focus]
        when (key) {
            InputKey.UP -> if (editing) step(item, +1) else focus = minOf(focus + 1, list.lastIndex)
            InputKey.DOWN -> if (editing) step(item, -1) else focus = maxOf(focus - 1, 0)
            InputKey.OK, InputKey.START -> when (item) {
                is Action -> item.onSelect()
                is Switch -> item.set(!item.get())
                else -> editing = !editing
            }
            InputKey.BACK -> if (editing) editing = false else return false
            else -> return false
        }
        return true
    }

    private fun step(item: Item, dir: Int) {
        when (item) {
            is Number -> {
                // var_item_editor_increment/decrement: snap to the step grid anchored at min.
                val v = item.get()
                val next = if (dir > 0) item.min + ((v - item.min) / item.step + 1) * item.step
                else item.min + ((v - item.min - 1).floorDiv(item.step)) * item.step
                item.set(next.coerceIn(item.min, item.max))
            }
            is Choice -> item.set((item.get() + dir).coerceIn(0, item.options.lastIndex))
            is Switch -> item.set(!item.get())
            is Action -> Unit
        }
    }

    fun valueText(item: Item): String = when (item) {
        is Action -> item.value()
        is Number -> item.format(item.get())
        is Choice -> item.options[item.get().coerceIn(0, item.options.lastIndex)]
        is Switch -> if (item.get()) "On" else "Off"
    }

    /**
     * One item: label on the left, value on the right, position dots along the bottom. For the
     * first [TITLE_MS] after entering, the page title is shown instead, since the phone has no
     * back display to carry the bar's location header.
     */
    fun renderFront(front: Gfx, nowMs: Long) {
        if (title != null) {
            if (titleShownAtMs == 0L) titleShownAtMs = nowMs
            if (nowMs - titleShownAtMs < TITLE_MS) {
                front.text(title.uppercase(), ApiFont.NORMAL, 36, 8, LABEL_COLOR, Align.CENTER)
                return
            }
        }
        val list = shown()
        if (list.isEmpty()) return
        focus = focus.coerceIn(0, list.lastIndex)
        val item = list[focus]
        val value = valueText(item)
        val valueColor = if (editing) EDIT_COLOR else VALUE_COLOR
        // Value on the right, with arrows while editing; label fills what is left.
        val valueW = front.textWidth(value, ApiFont.NORMAL) + if (editing) 8 else 0
        val right = 72 - 1
        if (editing) {
            front.text("<", ApiFont.SMALL, right - valueW, 8, valueColor, Align.MID_LEFT)
            front.text(">", ApiFont.SMALL, right, 8, valueColor, Align.MID_RIGHT)
            front.text(value, ApiFont.NORMAL, right - 4, 8, valueColor, Align.MID_RIGHT)
        } else {
            front.text(value, ApiFont.NORMAL, right, 8, valueColor, Align.MID_RIGHT)
        }
        val labelArea = front.withClip(0, 0, maxOf(0, 72 - valueW - 3), 16)
        labelArea.text(item.label, ApiFont.SMALL, 1, 8, if (editing) DIM else LABEL_COLOR, Align.MID_LEFT)
        // Position dots along the bottom edge.
        if (list.size > 1) {
            val x0 = 36 - list.size
            list.indices.forEach { i -> front.fill(x0 + i * 2, 15, 1, 1, if (i == focus) LABEL_COLOR else DOT_COLOR) }
        }
    }

    /** The whole list, focused row highlighted. */
    fun renderBack(back: Gfx) {
        val list = shown()
        list.forEachIndexed { i, it ->
            val y = 6 + i * 12
            if (i == focus) back.fill(2, y - 1, 144, 11, if (editing) 0xFF404040.toInt() else 0xFF2A2A2A.toInt())
            back.text(it.label, ApiFont.NORMAL, 6, y, LABEL_COLOR)
            back.text(valueText(it), ApiFont.NORMAL, 142, y, if (i == focus && editing) EDIT_COLOR else VALUE_COLOR, Align.TOP_RIGHT)
        }
    }

    private companion object {
        const val TITLE_MS = 900L
        const val LABEL_COLOR = 0xFFFFFFFF.toInt()
        const val VALUE_COLOR = 0xFFB0B0B0.toInt()
        const val EDIT_COLOR = 0xFFFFD000.toInt()
        const val DIM = 0xFF707070.toInt()
        const val DOT_COLOR = 0xFF404040.toInt()
    }
}

/** var_item_editor timebox format: "20 m", "1 h", "1:30". */
fun formatMinutes(mn: Int): String {
    val h = mn / 60
    val m = mn % 60
    return when {
        h == 0 -> "$m m"
        m == 0 -> "$h h"
        else -> "%d:%02d".format(h, m)
    }
}
