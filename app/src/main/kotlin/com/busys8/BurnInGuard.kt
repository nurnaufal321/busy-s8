package com.busys8

/**
 * AMOLED burn-in protection for the LED panel.
 *
 * - Pixel orbit: the grid steps through a 3×3 pattern of offsets every [ORBIT_STEP_MS], up to
 *   half an LED pitch each way, so lit LEDs keep landing on pixels that were gaps before.
 * - Static dimming: once the front display has not changed for [STATIC_DIM_AFTER_MS], it fades
 *   to [STATIC_DIM_LEVEL] over [STATIC_DIM_FADE_MS], and snaps back on the next change.
 */
object BurnInGuard {
    const val ORBIT_STEP_MS = 2 * 60_000L
    const val STATIC_DIM_AFTER_MS = 5 * 60_000L
    const val STATIC_DIM_FADE_MS = 60_000L
    const val STATIC_DIM_LEVEL = 0.4f

    /** Visits the corners and edges before returning to the centre, so moves stay one step apart. */
    private val ORBIT = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)

    /** Grid offset, in units of half an LED pitch on each axis (-1, 0 or 1). */
    fun orbitStep(nowMs: Long): Pair<Int, Int> = ORBIT[((nowMs / ORBIT_STEP_MS) % ORBIT.size).toInt()]

    /** Brightness multiplier for content that last changed at [lastChangeMs]. */
    fun dimLevel(nowMs: Long, lastChangeMs: Long): Float {
        val staticFor = nowMs - lastChangeMs - STATIC_DIM_AFTER_MS
        if (staticFor <= 0) return 1f
        val fade = (staticFor.toFloat() / STATIC_DIM_FADE_MS).coerceAtMost(1f)
        return 1f - (1f - STATIC_DIM_LEVEL) * fade
    }
}
