package com.busys8.core

/**
 * Paints the canvas overlay. The firmware does this with LVGL widgets; this reproduces
 * their geometry: label sizing from LVGL font metrics, align-to-anchor placement
 * (canvas_widget_reanchor), circular scrolling and rounded rectangles.
 */
class CanvasRenderer(private val fonts: FontRegistry, private val images: ImageLoader, private val anims: AnimLoader) {
    /** One player per animation element id, plus the request it is playing. */
    private val players = HashMap<String, Pair<AnimPlayer, Element.Animation>>()

    /** Paints all canvas widgets onto [front] and [back]. The overlay background is opaque black. */
    fun render(canvas: Canvas, front: Framebuffer, back: Framebuffer, nowMs: Long) {
        if (!canvas.isOpen) {
            players.clear()
            return
        }
        front.clear()
        fillRect(back, 0, 0, Display.BACK.canvasWidth, Display.BACK.canvasHeight, Framebuffer.BLACK)
        val widgets = canvas.widgets()
        players.keys.retainAll(widgets.map { it.element.id }.toSet())
        for (w in widgets) {
            val fb = if (w.element.display == Display.FRONT) front else back
            val clip = Clip(0, 0, w.element.display.canvasWidth, w.element.display.canvasHeight)
            when (val c = w.element.content) {
                is Element.Text -> drawText(fb, clip, w.element, c, nowMs - w.createdAtMs)
                is Element.Countdown -> drawCountdown(fb, clip, w.element, c, nowMs)
                is Element.Rectangle -> drawRectangle(fb, clip, w.element, c)
                is Element.Image -> images.load(c.path)?.let { drawImage(fb, clip, w.element, it, c.opacity) }
                is Element.Raw -> drawImage(fb, clip, w.element, c.image, c.opacity)
                is Element.Animation -> player(w.element.id, c, nowMs)?.let { drawImage(fb, clip, w.element, it.frame(nowMs), c.opacity) }
            }
        }
    }

    /** Re-sending an element keeps its player, switching section as the request asks. */
    private fun player(id: String, a: Element.Animation, nowMs: Long): AnimPlayer? {
        val existing = players[id]
        if (existing != null && existing.first.file === anims.load(a.path)) {
            if (existing.second != a) {
                existing.first.play(a.section, a.loop, a.awaitPreviousEnd, nowMs)
                players[id] = existing.first to a
            }
            return existing.first
        }
        val file = anims.load(a.path) ?: return null
        val p = AnimPlayer(file, nowMs).also { it.play(a.section, a.loop, afterCurrent = false, nowMs) }
        players[id] = p to a
        return p
    }

    data class Clip(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        fun intersect(x: Int, y: Int, w: Int, h: Int) =
            Clip(maxOf(x0, x), maxOf(y0, y), minOf(x1, x + w), minOf(y1, y + h))

        fun contains(x: Int, y: Int) = x >= x0 && y >= y0 && x < x1 && y < y1
    }

    // ---- text ------------------------------------------------------------------------------

    private fun drawText(fb: Framebuffer, clip: Clip, e: Element, t: Element.Text, ageMs: Long) {
        val font = fonts.get(t.font.file)
        val textW = font.textWidth(t.text)
        val scrolling = t.width > 0 && t.scrollRate > 0 && textW > t.width
        val lines = if (t.width > 0 && !scrolling) wrap(font, t.text, t.width) else listOf(t.text)
        val boxW = if (t.width > 0) t.width else textW
        val boxH = font.lineHeight * lines.size
        val (ox, oy) = e.align.origin(e.x, e.y, boxW, boxH)
        val box = clip.intersect(ox, oy, boxW, boxH)
        if (scrolling) {
            val gap = font.advance(' '.code) * LABEL_WAIT_CHAR_COUNT
            val period = textW + gap
            val shift = scrollOffset(ageMs, period, t)
            drawLine(fb, box, font, t.text, ox - shift, oy, t.color)
            drawLine(fb, box, font, t.text, ox - shift + period, oy, t.color)
        } else {
            lines.forEachIndexed { i, line -> drawLine(fb, box, font, line, ox, oy + i * font.lineHeight, t.color) }
        }
    }

    /** Pixels scrolled so far: start delay, then one period per cycle with a pause between cycles. */
    private fun scrollOffset(ageMs: Long, period: Int, t: Element.Text): Int {
        val cycleMs = period * 60_000L / t.scrollRate
        val elapsed = ageMs - t.scrollStartDelay
        if (elapsed <= 0 || cycleMs <= 0) return 0
        val inCycle = elapsed % (cycleMs + t.scrollRepeatDelay)
        return if (inCycle >= cycleMs) 0 else (inCycle * period / cycleMs).toInt()
    }

    /** LVGL-style line breaking: break after spaces, split words wider than the line. */
    private fun wrap(font: LvFont, text: String, width: Int): List<String> {
        val lines = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            var line = StringBuilder()
            var lineW = 0
            for (word in Regex("\\S+\\s*|\\s+").findAll(paragraph).map { it.value }) {
                val wordW = font.textWidth(word.trimEnd())
                if (lineW + wordW <= width) {
                    line.append(word)
                    lineW += font.textWidth(word)
                    continue
                }
                if (line.isNotEmpty()) {
                    lines += line.toString().trimEnd()
                    line = StringBuilder()
                    lineW = 0
                }
                for (ch in word) {
                    val cw = font.advance(ch.code)
                    if (lineW + cw > width && line.isNotEmpty()) {
                        lines += line.toString().trimEnd()
                        line = StringBuilder()
                        lineW = 0
                    }
                    line.append(ch)
                    lineW += cw
                }
            }
            lines += line.toString().trimEnd()
        }
        return lines
    }

    // ---- countdown ---------------------------------------------------------------------------

    private fun drawCountdown(fb: Framebuffer, clip: Clip, e: Element, c: Element.Countdown, nowMs: Long) {
        val text = countdownText(c, nowMs / 1000)
        val font = fonts.get(ApiFont.SUPERSCRIPT.file)
        val w = font.textWidth(text)
        val (ox, oy) = e.align.origin(e.x, e.y, w, font.lineHeight)
        drawLine(fb, clip.intersect(ox, oy, w, font.lineHeight), font, text, ox, oy, c.color)
    }

    // ---- rectangle ---------------------------------------------------------------------------

    private fun drawRectangle(fb: Framebuffer, clip: Clip, e: Element, r: Element.Rectangle) {
        val (ox, oy) = e.align.origin(e.x, e.y, r.width, r.height)
        val radius = minOf(r.radius, minOf(r.width, r.height) / 2)
        val b = r.borderWidth
        for (py in 0 until r.height) for (px in 0 until r.width) {
            val x = ox + px
            val y = oy + py
            if (!clip.contains(x, y) || !insideRounded(px, py, r.width, r.height, radius)) continue
            val inner = b == 0 || insideRounded(px - b, py - b, r.width - 2 * b, r.height - 2 * b, maxOf(0, radius - b))
            if (inner) {
                val fill = when (r.fill) {
                    RectFill.NONE -> 0
                    RectFill.SOLID -> r.fillColors.first
                    RectFill.GRADIENT_H -> lerp(r.fillColors.first, r.fillColors.second, px, r.width)
                    RectFill.GRADIENT_V -> lerp(r.fillColors.first, r.fillColors.second, py, r.height)
                }
                fb.blend(x, y, fill)
            } else {
                fb.blend(x, y, r.borderColor)
            }
        }
    }

    private fun insideRounded(px: Int, py: Int, w: Int, h: Int, radius: Int): Boolean {
        if (px < 0 || py < 0 || px >= w || py >= h) return false
        if (radius == 0) return true
        val cx = when {
            px < radius -> radius - 1
            px >= w - radius -> w - radius
            else -> return true
        }
        val cy = when {
            py < radius -> radius - 1
            py >= h - radius -> h - radius
            else -> return true
        }
        val dx = px - cx
        val dy = py - cy
        return dx * dx + dy * dy <= radius * radius - radius
    }

    private fun lerp(a: Int, b: Int, i: Int, n: Int): Int {
        if (n <= 1) return a
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (n - 1 - i) + ((b shr shift) and 0xFF) * i) / (n - 1)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---- images ------------------------------------------------------------------------------

    private fun drawImage(fb: Framebuffer, clip: Clip, e: Element, img: RawImage, opacity: Int) {
        val (ox, oy) = e.align.origin(e.x, e.y, img.width, img.height)
        for (py in 0 until img.height) for (px in 0 until img.width) {
            if (clip.contains(ox + px, oy + py)) fb.blend(ox + px, oy + py, Argb.withOpacity(img.argb[py * img.width + px], opacity))
        }
    }

    companion object {
        /** LV_LABEL_WAIT_CHAR_COUNT: gap between repetitions in circular scroll, in spaces. */
        const val LABEL_WAIT_CHAR_COUNT = 3

        /** Draws one line of [text] whose line box starts at ([x], [lineTop]), clipped to [clip]. */
        fun drawLine(fb: Framebuffer, clip: Clip, font: LvFont, text: String, x: Int, lineTop: Int, color: Int) {
            var pen = x
            for (ch in text) {
                val g = font.glyph(ch.code)
                if (g != null) {
                    val gx = pen + g.ofsX
                    val gy = lineTop + (font.lineHeight - font.baseLine) - g.height - g.ofsY
                    for (row in 0 until g.height) for (col in 0 until g.width) {
                        val a = g.alpha[row * g.width + col].toInt() and 0xFF
                        if (a == 0 || !clip.contains(gx + col, gy + row)) continue
                        fb.blend(gx + col, gy + row, Argb.withOpacity(color, a))
                    }
                    pen += g.advance
                }
            }
        }

        /** countdown.c formatting, including its `hours % 60`. */
        fun countdownText(c: Element.Countdown, nowS: Long): String {
            var delta = c.timestamp - nowS
            if (c.timeSince) delta = -delta
            if (delta < 0) delta = 0
            val s = delta % 60
            val m = (delta / 60) % 60
            val h = (delta / 3600) % 60
            return if (h > 0 || c.alwaysHours) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }

        fun fillRect(fb: Framebuffer, x: Int, y: Int, w: Int, h: Int, color: Int) {
            for (py in y until y + h) for (px in x until x + w) fb.blend(px, py, color)
        }
    }
}
