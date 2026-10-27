package com.busys8

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.busys8.core.Argb
import com.busys8.core.Display
import com.busys8.core.Frame
import kotlin.math.min

/**
 * Both displays on the S8+ panel in landscape: the 72×16 RGB LED matrix as rounded squares,
 * and the 160×80 greyscale back display as a pixel strip below it. Unlit LEDs stay pure black
 * so the AMOLED pixels are off. The curved screen edges are top and bottom in landscape, so
 * everything sits inside [EDGE_MARGIN_FRACTION] of the height.
 */
@Composable
fun LedPanel(frame: Frame) {
    val back = remember(frame.seq) { backBitmap(frame.back) }
    Canvas(Modifier.fillMaxSize().background(Color.Black)) {
        val margin = size.height * EDGE_MARGIN_FRACTION
        val usableH = size.height - 2 * margin
        val pitch = min(size.width / Display.FRONT.width, usableH * 0.55f / Display.FRONT.height).toInt().toFloat()
        val ledSize = pitch * LED_FILL
        val frontW = pitch * Display.FRONT.width
        val left = (size.width - frontW) / 2
        val top = margin
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

        val backScale = min(size.width / Display.BACK.width, (usableH - pitch * Display.FRONT.height - pitch) / Display.BACK.height).toInt()
        val backW = Display.BACK.width * backScale
        val backH = Display.BACK.height * backScale
        drawImage(
            image = back,
            dstOffset = IntOffset(((size.width - backW) / 2).toInt(), (size.height - margin - backH).toInt()),
            dstSize = IntSize(backW, backH),
            filterQuality = FilterQuality.None,
        )
    }
}

private fun backBitmap(argb: IntArray): ImageBitmap {
    val grey = IntArray(argb.size) { i ->
        val l = Argb.luminance(argb[i])
        Argb.of(255, l, l, l)
    }
    return android.graphics.Bitmap.createBitmap(grey, Display.BACK.width, Display.BACK.height, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

private const val EDGE_MARGIN_FRACTION = 0.08f
private const val LED_FILL = 0.82f
