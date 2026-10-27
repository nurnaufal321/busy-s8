package com.busys8.engine.input

/** InputKey: the bar's buttons, encoder directions and the five mode-switch positions. */
enum class InputKey(val apiName: String) {
    UP("up"),
    DOWN("down"),
    OK("ok"),
    BACK("back"),
    START("start"),
    BUSY("busy"),
    CUSTOM("custom"),
    OFF("off"),
    APPS("apps"),
    SETTINGS("settings");

    val isSwitch get() = this in SWITCH_POSITIONS

    companion object {
        val SWITCH_POSITIONS = listOf(BUSY, CUSTOM, OFF, APPS, SETTINGS)
        fun fromApi(name: String) = entries.firstOrNull { it.apiName == name }
    }
}

enum class InputType { PRESS, RELEASE, SHORT, LONG, REPEAT }

data class InputEvent(val key: InputKey, val type: InputType)

/**
 * Port of services/input/input.c's event synthesis: a held key fires LONG after
 * [LONG_PRESS_COUNTS] periods of [PRESS_TICKS_MS], then REPEAT every period; releasing before
 * that fires SHORT. Single-threaded; call [tick] from the device loop.
 */
class InputService(private val clock: () -> Long) {
    private val listeners = ArrayList<(InputEvent) -> Unit>()
    private val held = LinkedHashMap<InputKey, Hold>()

    private class Hold(var nextTickMs: Long, var count: Int = 0)

    fun subscribe(listener: (InputEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun press(key: InputKey) {
        if (key in held) return
        held[key] = Hold(clock() + PRESS_TICKS_MS)
        emit(key, InputType.PRESS)
    }

    fun release(key: InputKey) {
        val hold = held.remove(key) ?: return
        emit(key, InputType.RELEASE)
        if (hold.count < LONG_PRESS_COUNTS) emit(key, InputType.SHORT)
    }

    /** input_key_toggle: a press and an immediate release (API calls, encoder clicks, the switch). */
    fun toggle(key: InputKey) {
        press(key)
        release(key)
    }

    fun tick() {
        val now = clock()
        for ((key, hold) in held.entries.toList()) {
            while (now >= hold.nextTickMs && key in held) {
                hold.nextTickMs += PRESS_TICKS_MS
                hold.count++
                when {
                    hold.count == LONG_PRESS_COUNTS -> emit(key, InputType.LONG)
                    hold.count > LONG_PRESS_COUNTS -> emit(key, InputType.REPEAT)
                }
            }
        }
    }

    private fun emit(key: InputKey, type: InputType) {
        val event = InputEvent(key, type)
        listeners.toList().forEach { it(event) }
    }

    companion object {
        const val PRESS_TICKS_MS = 150L
        const val LONG_PRESS_COUNTS = 2
    }
}
