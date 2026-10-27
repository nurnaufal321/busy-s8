package com.busys8.engine.apps.busy

import com.busys8.core.Align
import com.busys8.core.AnimLoader
import com.busys8.core.AnimPlayer
import com.busys8.core.ApiFont
import com.busys8.core.Blend
import com.busys8.core.Gfx
import com.busys8.core.ImageLoader
import com.busys8.engine.timer.TimerState

/** Asset paths (busy/storage_macros.h). */
object BusyAssets {
    private const val ROOT = "/ext/apps_assets/busy"
    fun anim(name: String) = "$ROOT/animations/$name.anim"
    fun image(name: String) = "$ROOT/images/$name.image"
    fun sound(name: String) = "$ROOT/sounds/$name.snd"
    fun theme(name: String) = "$ROOT/themes/$name/theme.json"
    const val START_MENU = "/ext/apps_assets/shared/animations/start_menu_31x16.anim"
}

/** Front-theme fonts (lv_theme_front.c): normal and small are regular_5, large is regular_7. */
val FONT_NORMAL = ApiFont.SMALL
val FONT_LARGE = ApiFont.NORMAL
const val WHITE = 0xFFFFFFFF.toInt()

/** Lazily created players that restart when their source changes. */
class Anims(private val loader: AnimLoader) {
    private val players = HashMap<String, AnimPlayer>()

    /** A looping player for [path]. */
    fun loop(path: String, nowMs: Long): AnimPlayer? =
        players[path] ?: loader.load(path)?.let { AnimPlayer(it, nowMs) }?.also { players[path] = it }

    /** A player that plays [path] once and holds the last frame (AnimFilePlayFlagNone). */
    fun once(path: String, nowMs: Long): AnimPlayer? = players[path] ?: loader.load(path)?.let { file ->
        AnimPlayer(file, nowMs).also { it.play("default", loop = false, afterCurrent = false, nowMs = nowMs); players[path] = it }
    }

    fun draw(g: Gfx, player: AnimPlayer?, x: Int, y: Int, nowMs: Long, blend: Blend = Blend.NORMAL) {
        player?.let { g.image(it.frame(nowMs), x, y, blend = blend) }
    }

    fun reset() = players.clear()
}

/**
 * widgets/timer_indicator.c with the busy_presets.c presets: a 41×16 indicator made of a looping
 * particle background, a progress animation offset by the elapsed fraction, a multiply mask and
 * a foreground image. The infinite work state uses one 72×16 animation instead.
 */
class TimerIndicator(private val anims: Anims, private val images: ImageLoader) {
    fun render(g: Gfx, state: TimerState, infinite: Boolean, progress: Float, nowMs: Long) {
        when {
            state == TimerState.WORK && infinite ->
                anims.draw(g, anims.loop(BusyAssets.anim("indicator_busy_72x16"), nowMs), 0, 0, nowMs)
            state == TimerState.WORK -> layered(g, "busy", "progress_busy_41x16", dx = (-38 + progress * 38).toInt(), dy = 0, nowMs)
            state == TimerState.REST -> layered(g, "rest", "progress_rest_41x22", dx = 0, dy = (-6 + progress * 22).toInt(), nowMs)
            else -> Unit
        }
    }

    private fun layered(g: Gfx, kind: String, progressAnim: String, dx: Int, dy: Int, nowMs: Long) {
        val area = g.withClip(0, 0, 41, 16)
        anims.draw(area, anims.loop(BusyAssets.anim("particles_${kind}_41x16"), nowMs), 0, 0, nowMs)
        anims.draw(area, anims.loop(BusyAssets.anim(progressAnim), nowMs), dx, dy, nowMs)
        images.load(BusyAssets.image("indicator_mask_41x16"))?.let { area.image(it, 0, 0, blend = Blend.MULTIPLY) }
        images.load(BusyAssets.image("indicator_${kind}_41x16"))?.let { area.image(it, 0, 0) }
    }
}

/**
 * widgets/timer_label.c: "MM:SS" (or "H:MM" with superscript seconds) over "LEFT", right-aligned
 * on the front display. The last 4 seconds turn the phase colour, the last 3 blink.
 */
class TimerLabel {
    fun render(g: Gfx, remainingS: Long, state: TimerState, background: Boolean, opacity: Float, nowMs: Long) {
        val h = remainingS / 3600
        val m = (remainingS % 3600) / 60
        val s = remainingS % 60
        val main = if (h > 0) "%d:%02d".format(h, m) else "%02d:%02d".format(m, s)
        val mainFont = if (h >= 10) ApiFont.CONDENSED else ApiFont.NORMAL
        val seconds = if (h > 0) "%02d".format(s) else null
        val rowW = g.textWidth(main, mainFont) + (seconds?.let { 1 + g.textWidth(it, ApiFont.SUPERSCRIPT) } ?: 0)
        val contentW = maxOf(rowW, g.textWidth("LEFT", FONT_NORMAL))
        val left = 72 - contentW
        if (background) {
            // A white→black multiply gradient over 10 px, then black behind the text.
            for (x in 0 until GRADIENT_PX) g.fill(left - GRADIENT_PX + x, 0, 1, 16, Gfx.mix(WHITE, 0xFF000000.toInt(), x / (GRADIENT_PX - 1f)), Blend.MULTIPLY)
            g.fill(left, 0, contentW, 16, 0xFF000000.toInt())
        }
        val color = withOpacity(countdownColor(remainingS, state, nowMs), opacity)
        g.text(main, mainFont, left, -1, color)
        seconds?.let { g.text(it, ApiFont.SUPERSCRIPT, left + g.textWidth(main, mainFont) + 1, -1, color) }
        g.text("LEFT", FONT_NORMAL, left, 8, color)
    }

    private fun countdownColor(remainingS: Long, state: TimerState, nowMs: Long): Int {
        val (base, blink) = if (state == TimerState.REST) REST_COLORS else WORK_COLORS
        return when {
            remainingS > BLINK_START_S + 1 -> WHITE
            remainingS == BLINK_START_S + 1 -> base
            else -> {
                val t = (nowMs % 1000).toInt()
                if (t < BLINK_MS) Gfx.mix(base, blink, t / BLINK_MS.toFloat()) else Gfx.mix(blink, base, (t - BLINK_MS) / (1000f - BLINK_MS))
            }
        }
    }

    private fun withOpacity(c: Int, opacity: Float) = (c and 0xFFFFFF) or ((255 * opacity).toInt().coerceIn(0, 255) shl 24)

    private companion object {
        const val BLINK_START_S = 3L
        const val BLINK_MS = 333
        const val GRADIENT_PX = 10
        val WORK_COLORS = Gfx.rgb(0xFF6077) to Gfx.rgb(0x8B2939)
        val REST_COLORS = Gfx.rgb(0x3EC287) to Gfx.rgb(0x1D6344)
    }
}

/**
 * widgets/pause_overlay.c: dims the front display and blinks "PAUSED" with a pause icon. The
 * firmware also blurs the background fully; without a blur, a stronger dim keeps the text legible.
 */
class PauseOverlay(private val images: ImageLoader) {
    fun render(g: Gfx, sinceMs: Long, nowMs: Long) {
        g.fill(0, 0, 72, 16, Gfx.rgb(0x262626), Blend.MULTIPLY)
        val elapsed = nowMs - sinceMs
        val blinkOn = elapsed < BLINK_DELAY_MS || ((elapsed - BLINK_DELAY_MS) / BLINK_PERIOD_MS) % 2 == 0L
        if (!blinkOn) return
        val text = "PAUSED"
        val w = 5 + 2 + g.textWidth(text, FONT_NORMAL)
        val x = (72 - w) / 2
        images.load(BusyAssets.image("pause_5x5"))?.let { g.image(it, x, 5) }
        g.text(text, FONT_NORMAL, x + 7, 8, WHITE, Align.MID_LEFT)
    }

    private companion object {
        const val BLINK_DELAY_MS = 667L
        const val BLINK_PERIOD_MS = 667L
    }
}

/**
 * widgets/progress_view.c: one 3 px bar per interval (work and rest alternate), sized to the
 * cycle count, bottom-aligned. Done intervals are coloured, the next one blinks while waiting.
 */
object ProgressView {
    private val WIDTHS = arrayOf(intArrayOf(72, 0), intArrayOf(31, 8), intArrayOf(18, 7), intArrayOf(12, 6), intArrayOf(8, 6), intArrayOf(7, 4), intArrayOf(6, 3))
    private val COLORS = arrayOf(intArrayOf(0xFF0002, 0x9B0000), intArrayOf(0x00E96B, 0x00833D))
    private val HIGHLIGHT = arrayOf(intArrayOf(0xFE567A, 0xE80919), intArrayOf(0x00FB91, 0x00FB91))
    private const val GREY = 0x656C6F
    private const val VISIBLE_CYCLES = 7

    fun render(g: Gfx, intervalIdx: Long, cycles: Long, waitNext: Boolean, nowMs: Long) {
        val count = (cycles * 2 - 1).toInt()
        val widthRow = WIDTHS[(minOf(cycles, VISIBLE_CYCLES.toLong()) - 1).toInt()]
        val bars = (0 until count).map { widthRow[it % 2] }
        val total = bars.sum() + (count - 1)
        // Scroll so the current interval stays in view when there are more than fit.
        var x = if (total <= 72) (72 - total) / 2 else -minOf(total - 72, bars.take(intervalIdx.toInt()).sumOf { it + 1 } - 36).coerceAtLeast(0)
        for (i in 0 until count) {
            val w = bars[i]
            val state = i % 2
            when {
                i <= intervalIdx -> {
                    var top = Gfx.rgb(COLORS[state][0])
                    var bottom = Gfx.rgb(COLORS[state][1])
                    if (waitNext) {
                        top = Gfx.darken(top)
                        bottom = Gfx.darken(bottom)
                    }
                    g.gradientV(x, 13, w, 3, top, bottom, radius = 1)
                }
                waitNext && i.toLong() == intervalIdx + 1 -> {
                    val lit = (nowMs / 500) % 2 == 0L
                    val c = if (lit) Gfx.rgb(HIGHLIGHT[state][0]) else Gfx.rgb(GREY)
                    g.gradientV(x, 13, w, 3, c, if (lit) Gfx.rgb(HIGHLIGHT[state][1]) else c, radius = 1)
                }
                else -> g.gradientV(x, 13, w, 3, Gfx.rgb(GREY), Gfx.rgb(GREY), radius = 1)
            }
            x += w + 1
        }
    }
}
