package com.busys8.core

/** LVGL blend modes the firmware's widgets use (lv_blend_mode_t). */
enum class Blend { NORMAL, ADD, MULTIPLY }

/**
 * Drawing context for the native apps' screens, clipped to a region of one display.
 * Text uses the same LVGL metrics as [CanvasRenderer]; images blend like LVGL widgets.
 */
class Gfx(val fb: Framebuffer, val fonts: FontRegistry, val clip: CanvasRenderer.Clip = CanvasRenderer.Clip(0, 0, fb.width, fb.height)) {

    fun withClip(x: Int, y: Int, w: Int, h: Int) = Gfx(fb, fonts, clip.intersect(x, y, w, h))

    fun font(f: ApiFont) = fonts.get(f.file)

    fun image(img: RawImage, x: Int, y: Int, opacity: Int = 255, blend: Blend = Blend.NORMAL) {
        for (py in 0 until img.height) for (px in 0 until img.width) {
            val dx = x + px
            val dy = y + py
            if (!clip.contains(dx, dy)) continue
            put(dx, dy, img.argb[py * img.width + px], opacity, blend)
        }
    }

    fun fill(x: Int, y: Int, w: Int, h: Int, color: Int, blend: Blend = Blend.NORMAL) {
        for (dy in y until y + h) for (dx in x until x + w) if (clip.contains(dx, dy)) put(dx, dy, color, 255, blend)
    }

    /** A vertical two-stop gradient, as LVGL's LV_GRAD_DIR_VER. */
    fun gradientV(x: Int, y: Int, w: Int, h: Int, top: Int, bottom: Int, radius: Int = 0) {
        for (row in 0 until h) {
            val c = mix(top, bottom, if (h <= 1) 0f else row / (h - 1f))
            for (col in 0 until w) {
                if (radius > 0 && cornerCut(col, row, w, h, radius)) continue
                if (clip.contains(x + col, y + row)) put(x + col, y + row, c, 255, Blend.NORMAL)
            }
        }
    }

    fun textWidth(s: String, f: ApiFont) = font(f).textWidth(s)

    fun lineHeight(f: ApiFont) = font(f).lineHeight

    /** Draws one line whose box (text width × line height) has its [align] point at ([x], [y]). */
    fun text(s: String, f: ApiFont, x: Int, y: Int, color: Int, align: Align = Align.TOP_LEFT) {
        val lf = font(f)
        val (ox, oy) = align.origin(x, y, lf.textWidth(s), lf.lineHeight)
        CanvasRenderer.drawLine(fb, clip, lf, s, ox, oy, color)
    }

    private fun put(x: Int, y: Int, src: Int, opacity: Int, blend: Blend) {
        val a = Argb.a(src) * opacity / 255
        if (a == 0) return
        when (blend) {
            Blend.NORMAL -> fb.blend(x, y, Argb.withOpacity(src, a))
            Blend.ADD -> {
                val d = fb[x, y]
                fb.pixels[y * fb.width + x] = Argb.of(
                    255,
                    minOf(255, Argb.r(d) + Argb.r(src) * a / 255),
                    minOf(255, Argb.g(d) + Argb.g(src) * a / 255),
                    minOf(255, Argb.b(d) + Argb.b(src) * a / 255),
                )
            }
            Blend.MULTIPLY -> {
                val d = fb[x, y]
                fun ch(dc: Int, sc: Int) = dc * (255 - a) / 255 + dc * sc / 255 * a / 255
                fb.pixels[y * fb.width + x] = Argb.of(255, ch(Argb.r(d), Argb.r(src)), ch(Argb.g(d), Argb.g(src)), ch(Argb.b(d), Argb.b(src)))
            }
        }
    }

    private fun cornerCut(col: Int, row: Int, w: Int, h: Int, r: Int): Boolean {
        val cx = if (col < r) r - 1 else if (col >= w - r) w - r else return false
        val cy = if (row < r) r - 1 else if (row >= h - r) h - r else return false
        val dx = col - cx
        val dy = row - cy
        return dx * dx + dy * dy > r * r
    }

    companion object {
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun rgb(hex: Int) = (0xFF shl 24) or hex

        /** lv_color_darken(c, LV_OPA_50). */
        fun darken(c: Int, amount: Int = 128) = mix(c, 0xFF000000.toInt(), amount / 255f)
    }
}
