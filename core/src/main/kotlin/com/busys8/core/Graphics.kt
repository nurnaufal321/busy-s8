package com.busys8.core

/** The two physical displays. Canvas sizes follow the firmware's GUI main layer (gui.c). */
enum class Display(val apiName: String, val width: Int, val height: Int, val canvasWidth: Int) {
    FRONT("front", 72, 16, 72),

    /** The back display reserves a 12 px status bar on the right (BACK_STATUS_BAR_WIDTH). */
    BACK("back", 160, 80, 148);

    val canvasHeight get() = height

    companion object {
        fun fromApi(name: String) = entries.firstOrNull { it.apiName == name }
    }
}

/** Colours are packed ARGB ints throughout, like android.graphics.Color. */
object Argb {
    fun a(c: Int) = c ushr 24
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun of(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Parses the API's `#RRGGBBAA`, returning null when malformed (color_parse_hexa_string). */
    fun parseHexRgba(s: String): Int? {
        if (s.length != 9 || s[0] != '#') return null
        val v = s.substring(1).toLongOrNull(16) ?: return null
        val rgba = v.toInt()
        return ((rgba and 0xFF) shl 24) or (rgba ushr 8)
    }

    fun withOpacity(c: Int, opacity: Int) = (c and 0xFFFFFF) or ((a(c) * opacity / 255) shl 24)

    /** Luminance as LVGL computes it for L8 targets (lv_color_luminance). */
    fun luminance(c: Int) = (r(c) * 77 + g(c) * 151 + b(c) * 28) shr 8
}

/** An opaque-black ARGB pixel buffer. All drawing clips to its bounds. */
class Framebuffer(val width: Int, val height: Int) {
    val pixels = IntArray(width * height) { BLACK }

    fun clear(color: Int = BLACK) = pixels.fill(color)

    operator fun get(x: Int, y: Int) = pixels[y * width + x]

    /** Source-over blend of [color] onto (x, y). */
    fun blend(x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val sa = Argb.a(color)
        if (sa == 0) return
        val i = y * width + x
        if (sa == 255) {
            pixels[i] = color
            return
        }
        val d = pixels[i]
        val inv = 255 - sa
        pixels[i] = Argb.of(
            255,
            (Argb.r(color) * sa + Argb.r(d) * inv) / 255,
            (Argb.g(color) * sa + Argb.g(d) * inv) / 255,
            (Argb.b(color) * sa + Argb.b(d) * inv) / 255,
        )
    }

    fun copyFrom(other: Framebuffer) = other.pixels.copyInto(pixels)

    companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}

/** A decoded raster image in ARGB. */
class RawImage(val width: Int, val height: Int, val argb: IntArray)
