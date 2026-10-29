package com.busys8

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.busys8.core.Display
import com.busys8.core.Frame
import com.busys8.engine.system.ClockStrip
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * The 72×16 RGB front display as rounded-square LEDs at the top of the panel in landscape, and
 * under it, at the same LED size, the date and time ([ClockStrip]) when [showClock] is set.
 *
 * The phone has one screen and it faces the room, like the bar's front display. The back
 * display (which faces the bar's owner) is still rendered and served over the API, just not
 * shown here. Unlit LEDs stay pure black so the AMOLED pixels are off, and [BurnInGuard]
 * moves and dims the grid. The curved screen edges are top and bottom in landscape, so the
 * grid keeps out of [EDGE_MARGIN_FRACTION] of the height.
 */
@Composable
fun LedPanel(frame: Frame, showClock: Boolean, zone: () -> String, clock12h: () -> Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val lastChange = remember { LastChange() }
    lastChange.observe(frame.front, now)
    val dim = BurnInGuard.dimLevel(now, lastChange.atMs)
    val (orbitX, orbitY) = BurnInGuard.orbitStep(now)

    val clock = if (showClock) ClockStrip.render(now, zone(), clock12h()) else null

    Canvas(Modifier.fillMaxSize().background(Color.Black)) {
        val usableH = size.height * (1 - 2 * EDGE_MARGIN_FRACTION)
        val w = Display.FRONT.width
        val rows = Display.FRONT.height + CLOCK_GAP + ClockStrip.HEIGHT
        // One spare LED of width leaves room for the orbit's ±half-pitch moves. The layout stays
        // the same with the clock hidden, so the picture never jumps.
        val pitch = min(size.width / (w + 1), usableH / rows).toInt().toFloat()
        val ledSize = pitch * LED_FILL
        val left = (size.width - pitch * w) / 2 + orbitX * pitch / 2
        val top = (size.height - pitch * rows) / 2 + orbitY * pitch / 2
        val corner = CornerRadius(ledSize * 0.25f)
        fun led(x: Int, y: Int, c: Int) {
            if (c and 0xFFFFFF == 0) return
            drawRoundRect(
                color = Color(c or 0xFF000000.toInt()),
                topLeft = Offset(left + x * pitch + (pitch - ledSize) / 2, top + y * pitch + (pitch - ledSize) / 2),
                size = Size(ledSize, ledSize),
                cornerRadius = corner,
                alpha = dim,
            )
        }
        for (y in 0 until Display.FRONT.height) for (x in 0 until w) led(x, y, frame.front[y * w + x])
        if (clock != null) {
            val y0 = Display.FRONT.height + CLOCK_GAP
            for (y in 0 until ClockStrip.HEIGHT) for (x in 0 until ClockStrip.WIDTH) led(x, y0 + y, clock[y * ClockStrip.WIDTH + x])
        }
    }
}

/** Remembers when the front display content last changed. */
private class LastChange {
    private var previous: IntArray? = null
    var atMs = System.currentTimeMillis()
        private set

    fun observe(front: IntArray, nowMs: Long) {
        if (previous?.contentEquals(front) != true) {
            previous = front.copyOf()
            atMs = nowMs
        }
    }
}

private const val EDGE_MARGIN_FRACTION = 0.08f
private const val LED_FILL = 0.82f
/** Unlit rows between the picture and the clock. */
private const val CLOCK_GAP = 1
