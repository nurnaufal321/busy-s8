package com.busys8.core

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

val vendorDir = File(System.getProperty("busys8.vendor") ?: "../vendor")
val vendorAssets = FileAssetSource(vendorDir)

/** ImageIO-backed decoder for JVM tests. */
val imageIoDecoder = ImageDecoder { bytes ->
    ImageIO.read(ByteArrayInputStream(bytes))?.let { img ->
        RawImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }
}

/** Renders a framebuffer region as text art: '#' lit, '.' black. */
fun Framebuffer.ascii(x0: Int = 0, y0: Int = 0, w: Int = width, h: Int = height): String =
    (y0 until y0 + h).joinToString("\n") { y ->
        (x0 until x0 + w).joinToString("") { x -> if (this[x, y] and 0xFFFFFF != 0) "#" else "." }
    }
