package com.busys8

import com.busys8.BurnInGuard.ORBIT_STEP_MS
import com.busys8.BurnInGuard.STATIC_DIM_AFTER_MS
import com.busys8.BurnInGuard.STATIC_DIM_FADE_MS
import com.busys8.BurnInGuard.STATIC_DIM_LEVEL
import org.junit.Assert.assertEquals
import org.junit.Test

class BurnInGuardTest {
    @Test
    fun `orbit visits all nine positions, one step at a time`() {
        val steps = (0 until 9).map { BurnInGuard.orbitStep(it * ORBIT_STEP_MS) }
        assertEquals(9, steps.toSet().size)
        for (i in steps.indices) {
            val (ax, ay) = steps[i]
            val (bx, by) = steps[(i + 1) % steps.size]
            assert(maxOf(kotlin.math.abs(ax - bx), kotlin.math.abs(ay - by)) == 1) { "jump between $i and ${i + 1}" }
        }
        assertEquals(steps[0], BurnInGuard.orbitStep(9 * ORBIT_STEP_MS))
    }

    @Test
    fun `static content fades to the dim level and stays there`() {
        assertEquals(1f, BurnInGuard.dimLevel(STATIC_DIM_AFTER_MS, 0), 0f)
        assertEquals(1 - (1 - STATIC_DIM_LEVEL) / 2, BurnInGuard.dimLevel(STATIC_DIM_AFTER_MS + STATIC_DIM_FADE_MS / 2, 0), 1e-4f)
        assertEquals(STATIC_DIM_LEVEL, BurnInGuard.dimLevel(STATIC_DIM_AFTER_MS + 10 * STATIC_DIM_FADE_MS, 0), 1e-6f)
    }
}
