package com.busys8

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.busys8.core.Display
import com.busys8.core.Frame
import kotlin.math.min

/**
 * The 72×16 RGB front display as rounded-square LEDs, centred on the panel in landscape.
 *
 * The phone has one screen and it faces the room, like the bar's front display. The back
 * display (which faces the bar's owner) is still rendered and served over the API, just not
 * shown here. Unlit LEDs stay pure black so the AMOLED pixels are off. The curved screen edges
 * are top and bottom in landscape, so the grid keeps out of [EDGE_MARGIN_FRACTION] of the height.
 */
@Composable
fun LedPanel(frame: Frame) {
    Canvas(Modifier.fillMaxSize().background(Color.Black)) {
        val usableH = size.height * (1 - 2 * EDGE_MARGIN_FRACTION)
        val pitch = min(size.width / Display.FRONT.width, usableH / Display.FRONT.height).toInt().toFloat()
        val ledSize = pitch * LED_FILL
        val left = (size.width - pitch * Display.FRONT.width) / 2
        val top = (size.height - pitch * Display.FRONT.height) / 2
        val corner = CornerRadius(ledSize * 0.25f)
        for (y in 0 until Display.FRONT.height) for (x in 0 until Display.FRONT.width) {
            val c = frame.front[y * Display.FRONT.width + x]
            if (c and 0xFFFFFF == 0) continue
            drawRoundRect(
                color = Color(c),
                topLeft = Offset(left + x * pitch + (pitch - ledSize) / 2, top + y * pitch + (pitch - ledSize) / 2),
                size = Size(ledSize, ledSize),
                cornerRadius = corner,
            )
        }
    }
}

private const val EDGE_MARGIN_FRACTION = 0.08f
private const val LED_FILL = 0.82f
