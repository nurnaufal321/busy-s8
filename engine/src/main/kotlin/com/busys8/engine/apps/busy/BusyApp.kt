package com.busys8.engine.apps.busy

import com.busys8.core.Align
import com.busys8.core.AnimLoader
import com.busys8.core.AnimPlayer
import com.busys8.core.ApiFont
import com.busys8.core.DeviceStorage
import com.busys8.core.Gfx
import com.busys8.core.ImageLoader
import com.busys8.engine.apps.BusyAppHost
import com.busys8.engine.apps.LoaderPriority
import com.busys8.engine.apps.NativeApp
import com.busys8.engine.input.InputEvent
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputType
import com.busys8.engine.timer.AppConfig
import com.busys8.engine.timer.BusyTimer
import com.busys8.engine.timer.ProfileSlot
import com.busys8.engine.timer.TimerEvent
import com.busys8.engine.timer.TimerMode
import com.busys8.engine.timer.TimerState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Port of the BUSY app (applications/main/busy): start menu, interval overview, the running
 * timer, progress between intervals, and the end-of-session screens. Screen transitions and the
 * on-device setup screens are not ported yet; profiles are edited through the API.
 *
 * One instance serves both the BUSY and CUSTOM switch positions; [slot] says which profile.
 */
class BusyApp(
    private val timer: BusyTimer,
    private val storage: DeviceStorage,
    private val images: ImageLoader,
    animLoader: AnimLoader,
    private val playSound: (String) -> Unit,
    private val clock: () -> Long,
) : NativeApp, BusyAppHost {
    var slot = ProfileSlot.BUSY
    /** Set by the desktop: leave the app when it was only shown for a remote timer. */
    var exitToDesktop: () -> Unit = {}

    override val app: NativeApp get() = this
    private val anims = Anims(animLoader)
    private val indicator = TimerIndicator(anims, images)
    private val label = TimerLabel()
    private val pause = PauseOverlay(images)

    private var scene: Scene = StartScene()
    private var showTimerRequested = false
    private var shownForTimer = false
    private var config: AppConfig = timer.profile(slot).app

    override val priority get() = scene.priority

    init {
        timer.subscribe { event -> scene.onTimer(event) }
    }

    override fun onEnter(nowMs: Long) {
        // A running session keeps the settings it was started with (possibly from a remote snapshot).
        config = if (timer.state != TimerState.IDLE) timer.appConfig else timer.profile(slot).app
        if (timer.state != TimerState.IDLE) {
            showTimerRequested = true
            go(TimerScene(), nowMs)
        } else {
            go(StartScene(), nowMs)
        }
    }

    override fun onExit() {
        scene.exit()
        shownForTimer = false
    }

    override fun showTimer(config: AppConfig) {
        this.config = config
        showTimerRequested = true
        shownForTimer = true
        go(TimerScene(), clock())
    }

    override fun requestExit() = exitToDesktop()

    override fun onInput(event: InputEvent, nowMs: Long): Boolean {
        if (event.type != InputType.SHORT) return false
        if (event.key == InputKey.BACK) {
            scene.back(nowMs)
            return true
        }
        return scene.input(event.key, nowMs)
    }

    override fun tick(nowMs: Long) = scene.tick(nowMs)

    override fun render(front: Gfx, back: Gfx, nowMs: Long) {
        if (!(config.showWorkOnly && scene.blankFront)) scene.renderFront(front, nowMs)
        scene.renderBack(back, nowMs)
    }

    private fun go(next: Scene, nowMs: Long) {
        scene.exit()
        anims.reset()
        scene = next
        next.enter(nowMs)
    }

    /** busy_return_to_start_scene, or leave the app if it was opened only to show a timer. */
    private fun returnToStart(nowMs: Long) {
        if (shownForTimer) {
            shownForTimer = false
            exitToDesktop()
        } else {
            go(StartScene(), nowMs)
        }
    }

    // ---- scenes ---------------------------------------------------------------------------

    private abstract inner class Scene {
        open val priority get() = LoaderPriority.PASSTHROUGH
        open val blankFront get() = false
        open fun enter(nowMs: Long) {}
        open fun exit() {}
        open fun input(key: InputKey, nowMs: Long) = false
        open fun back(nowMs: Long) = returnToStart(nowMs)
        open fun onTimer(event: TimerEvent) {}
        open fun tick(nowMs: Long) {}
        abstract fun renderFront(g: Gfx, nowMs: Long)

        /** The back display's mirror card: profile title and, when running, the time. */
        open fun renderBack(g: Gfx, nowMs: Long) {
            g.text(timer.profile(slot).title, ApiFont.LARGE, 74, 40, WHITE, Align.CENTER)
        }
    }

    /** busy_scene_start: logo plus a two-item menu (Start / Setup) driven by the encoder. */
    private inner class StartScene : Scene() {
        private var item = 0
        private var menu: AnimPlayer? = null

        override fun enter(nowMs: Long) {
            config = timer.profile(slot).app
            menu = anims.loop(BusyAssets.START_MENU, nowMs)?.also { it.play("item-0", loop = true, afterCurrent = false, nowMs) }
        }

        override fun input(key: InputKey, nowMs: Long): Boolean {
            when (key) {
                InputKey.UP, InputKey.DOWN -> {
                    val next = 1 - item
                    menu?.play("transition-$item-to-$next", loop = false, afterCurrent = false, nowMs)
                    menu?.play("item-$next", loop = true, afterCurrent = true, nowMs)
                    item = next
                }
                InputKey.OK, InputKey.START -> if (key == InputKey.START || item == 0) start(nowMs) else go(SetupScene(), nowMs)
                else -> return false
            }
            return true
        }

        override fun back(nowMs: Long) = Unit

        private fun start(nowMs: Long) {
            config = timer.profile(slot).app
            showTimerRequested = false
            go(if (timer.profile(slot).timer.mode == TimerMode.INTERVAL) OverviewScene() else TimerScene(), nowMs)
        }

        override fun renderFront(g: Gfx, nowMs: Long) {
            val logo = if (slot == ProfileSlot.BUSY) "start_logo_busy_41x16" else "start_logo_custom_41x16"
            anims.draw(g, anims.once(BusyAssets.anim(logo), nowMs), 0, 0, nowMs)
            anims.draw(g, menu, 41, 0, nowMs)
        }

        override fun renderBack(g: Gfx, nowMs: Long) {
            listOf("Start", "Setup").forEachIndexed { i, s ->
                val y = 24 + i * 18
                if (i == item) g.fill(8, y - 7, 132, 15, 0xFF303030.toInt())
                g.text(s, ApiFont.LARGE, 16, y, WHITE, Align.MID_LEFT)
            }
        }
    }

    /** Placeholder for the setup scenes: profiles are edited via the API / web UI for now. */
    private inner class SetupScene : Scene() {
        override fun input(key: InputKey, nowMs: Long): Boolean {
            if (key == InputKey.OK || key == InputKey.START) go(StartScene(), nowMs)
            return true
        }

        override fun renderFront(g: Gfx, nowMs: Long) {
            g.text("SETUP", FONT_LARGE, 36, 4, WHITE, Align.CENTER)
            g.text("use the web UI", FONT_NORMAL, 36, 12, 0xFF909090.toInt(), Align.CENTER)
        }
    }

    /** busy_scene_overview: work/rest lengths over an animation, then the timer after 2.25 s. */
    private inner class OverviewScene : Scene() {
        private var untilMs = 0L

        override fun enter(nowMs: Long) {
            untilMs = nowMs + 2250
        }

        override fun tick(nowMs: Long) {
            if (nowMs >= untilMs) go(TimerScene(), nowMs)
        }

        override fun input(key: InputKey, nowMs: Long): Boolean {
            if (key != InputKey.OK && key != InputKey.START) return false
            go(TimerScene(), nowMs)
            return true
        }

        override fun renderFront(g: Gfx, nowMs: Long) {
            anims.draw(g, anims.once(BusyAssets.anim("overview_72x16"), nowMs), 0, 0, nowMs)
            val interval = timer.profile(slot).timer.interval
            column(g, 22, "WORK", Gfx.rgb(0xFF0000), minutes(interval.workMs))
            column(g, 50, "REST", Gfx.rgb(0x13F562), minutes(interval.restMs))
        }

        /** A tiny label (6 px line) over a large value (11 px line), squeezed into 16 px. */
        private fun column(g: Gfx, x: Int, top: String, color: Int, value: String) {
            g.text(top, ApiFont.TINY, x, 0, color, Align.TOP_MID)
            g.text(value, FONT_LARGE, x, 5, WHITE, Align.TOP_MID)
        }

        /** overview_label.c: "20m", "1h", "1:30". */
        private fun minutes(ms: Long): String {
            val total = ms / 60_000
            val h = total / 60
            val m = total % 60
            return when {
                h > 0 && m > 0 -> "%d:%02d".format(h, m)
                h > 0 -> "${h}h"
                else -> "${m}m"
            }
        }
    }

    /** busy_scene_timer: the running session. */
    private inner class TimerScene : Scene() {
        private var mode: TimerMode? = null
        private var state = TimerState.IDLE
        private var elapsedS = 0L
        private var remainingS = 0L
        private var paused = false
        private var pausedSinceMs = 0L
        private var labelCycleStartMs = 0L
        private var themeBg: String? = null

        override val priority get() = if (state != TimerState.IDLE) LoaderPriority.BLOCKING else LoaderPriority.PASSTHROUGH
        override val blankFront get() = state != TimerState.WORK || paused

        override fun enter(nowMs: Long) {
            themeBg = themeBackground(config.theme)
            labelCycleStartMs = nowMs
            if (showTimerRequested) timer.notifyInitialState() else timer.start(slot)
        }

        override fun exit() {
            showTimerRequested = false
        }

        override fun onTimer(event: TimerEvent) {
            val now = clock()
            when (event) {
                is TimerEvent.Tick -> onTick(event)
                is TimerEvent.ModeChanged -> mode = event.mode
                is TimerEvent.StateChanged -> {
                    state = event.state
                    labelCycleStartMs = now
                }
                is TimerEvent.Paused -> {
                    if (event.paused && !paused) pausedSinceMs = now
                    paused = event.paused
                }
                is TimerEvent.IntervalEnded -> {
                    val next = when {
                        mode != TimerMode.INTERVAL -> FinishScene()
                        timer.state == TimerState.IDLE -> EndingScene()
                        else -> ProgressScene()
                    }
                    go(next, now)
                }
                else -> Unit
            }
        }

        private fun onTick(t: TimerEvent.Tick) {
            elapsedS = t.elapsedS
            remainingS = t.remainingS
            if (mode != TimerMode.INFINITE) {
                when {
                    remainingS == 0L -> playSound(BusyAssets.sound("countdown_finish"))
                    remainingS <= 3 -> playSound(BusyAssets.sound("countdown_tick"))
                }
            }
        }

        override fun input(key: InputKey, nowMs: Long): Boolean {
            when (key) {
                InputKey.UP -> timer.addTime(5)
                InputKey.DOWN -> timer.addTime(-5)
                InputKey.OK -> if (mode == TimerMode.INTERVAL && !paused) timer.skip()
                InputKey.START -> timer.toggle()
                else -> return false
            }
            return true
        }

        override fun back(nowMs: Long) {
            if (paused) {
                timer.toggle()
            } else {
                timer.stop()
                returnToStart(nowMs)
            }
        }

        override fun renderFront(g: Gfx, nowMs: Long) {
            val infinite = mode == TimerMode.INFINITE
            val total = elapsedS + remainingS
            val progress = if (total == 0L) 0f else elapsedS.toFloat() / total
            val bg = themeBg
            val custom = bg != null && state == TimerState.WORK
            if (custom) {
                anims.draw(g, anims.loop(bg!!, nowMs), 0, 0, nowMs)
            } else {
                indicator.render(g, state, infinite, progress, nowMs)
            }
            if (!infinite && state != TimerState.IDLE) {
                if (custom) {
                    // Custom themes hide the label: 15 s hidden, 5 s shown; always shown near the end.
                    val phase = (nowMs - labelCycleStartMs) % (LABEL_HIDDEN_MS + LABEL_SHOWN_MS)
                    if (phase >= LABEL_HIDDEN_MS || remainingS <= 3) label.render(g, remainingS, state, background = true, opacity = 1f, nowMs = nowMs)
                } else {
                    label.render(g, remainingS, state, background = false, opacity = 1f, nowMs = nowMs)
                }
            }
            if (paused) pause.render(g, pausedSinceMs, nowMs)
        }

        override fun renderBack(g: Gfx, nowMs: Long) {
            val title = timer.profile(slot).title
            g.text(if (paused) "PAUSED" else title, ApiFont.LARGE, 74, 24, WHITE, Align.CENTER)
            if (mode != TimerMode.INFINITE) {
                val h = remainingS / 3600
                val m = (remainingS % 3600) / 60
                val s = remainingS % 60
                val text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
                g.text(text, ApiFont.EXTRA_LARGE, 74, 50, if (state == TimerState.REST) Gfx.rgb(0x3EC287) else Gfx.rgb(0xFF6077), Align.CENTER)
            }
        }
    }

    /** busy_scene_progress: "done/total" and the interval bars, then the next prompt after 2 s. */
    private inner class ProgressScene : Scene() {
        private var untilMs = 0L
        override val blankFront get() = true

        override fun enter(nowMs: Long) {
            untilMs = nowMs + 2000
        }

        override fun tick(nowMs: Long) {
            if (nowMs >= untilMs) go(NextScene(), nowMs)
        }

        override fun input(key: InputKey, nowMs: Long): Boolean {
            if (key != InputKey.START) return false
            go(NextScene(), nowMs)
            return true
        }

        override fun renderFront(g: Gfx, nowMs: Long) {
            val info = timer.runInfo()
            val cycles = info.config?.interval?.cycles ?: return
            val (done, total) = if (info.state == TimerState.WORK) info.intervalIndex / 2 to cycles - 1
            else (info.intervalIndex + 1) / 2 to cycles
            g.text("$done/$total", FONT_NORMAL, 36, 3, WHITE, Align.TOP_MID)
            ProgressView.render(g, info.intervalIndex - 1, cycles, waitNext = false, nowMs = nowMs)
        }
    }

    /** busy_scene_next: "Start ▸ BUSY/REST" until START resumes the timer. */
    private inner class NextScene : Scene() {
        override val blankFront get() = true

        override fun input(key: InputKey, nowMs: Long): Boolean {
            if (key != InputKey.START) return false
            showTimerRequested = false
            go(TimerScene(), nowMs)
            return true
        }

        override fun back(nowMs: Long) {
            timer.stop()
            returnToStart(nowMs)
        }

        override fun renderFront(g: Gfx, nowMs: Long) {
            val info = timer.runInfo()
            val cycles = info.config?.interval?.cycles ?: return
            val work = info.state == TimerState.WORK
            val message = if (work) "BUSY" else "REST"
            val color = if (work) Gfx.rgb(0xFF3C4A) else Gfx.rgb(0x0AE974)
            val arrow = if (work) "arrow_red_5x5" else "arrow_green_5x5"
            val w = g.textWidth("Start", FONT_NORMAL) + 2 + 5 + 2 + g.textWidth(message, FONT_NORMAL)
            var x = (72 - w) / 2
            g.text("Start", FONT_NORMAL, x, 3, WHITE)
            x += g.textWidth("Start", FONT_NORMAL) + 2
            anims.draw(g, anims.loop(BusyAssets.anim(arrow), nowMs), x, 4, nowMs)
            g.text(message, FONT_NORMAL, x + 7, 3, color)
            ProgressView.render(g, info.intervalIndex - 1, cycles, waitNext = true, nowMs = nowMs)
        }
    }

    /** busy_scene_ending: the session summary over particles, then the finish screen. */
    private inner class EndingScene : Scene() {
        private var finale: AnimPlayer? = null
        private var startMs = 0L
        override val blankFront get() = true

        override fun enter(nowMs: Long) {
            startMs = nowMs
        }

        override fun tick(nowMs: Long) {
            if (finale == null && nowMs - startMs >= SUMMARY_MS) finale = anims.once(BusyAssets.anim("ending_progress_72x16"), nowMs)
            if (finale?.isFinished(nowMs) == true) go(FinishScene(), nowMs)
        }

        override fun input(key: InputKey, nowMs: Long): Boolean {
            if (key != InputKey.START) return false
            go(FinishScene(), nowMs)
            return true
        }

        override fun renderFront(g: Gfx, nowMs: Long) {
            anims.draw(g, anims.loop(BusyAssets.anim("ending_particles_72x16"), nowMs), 0, 0, nowMs)
            val cycles = timer.runInfo().config?.interval?.cycles ?: 0
            // summary_view.c: one red bar per completed cycle.
            val barW = minOf(8, (60 - (cycles - 1).toInt()) / maxOf(1, cycles.toInt()))
            var x = (72 - (barW * cycles.toInt() + (cycles.toInt() - 1))) / 2
            repeat(cycles.toInt()) {
                g.gradientV(x, 7, barW, 3, Gfx.rgb(0xF4001D), Gfx.rgb(0x580000), radius = 1)
                x += barW + 1
            }
            finale?.let { g.image(it.frame(nowMs), 0, 0) }
        }
    }

    /** busy_scene_finish: confetti and "Well done!"; START or BACK returns to the start menu. */
    private inner class FinishScene : Scene() {
        private var startMs = 0L

        override fun enter(nowMs: Long) {
            startMs = nowMs
            playSound(BusyAssets.sound("session_completed"))
        }

        override fun input(key: InputKey, nowMs: Long): Boolean {
            if (key != InputKey.START) return false
            back(nowMs)
            return true
        }

        override fun back(nowMs: Long) {
            timer.finalize()
            returnToStart(nowMs)
        }

        override fun renderFront(g: Gfx, nowMs: Long) {
            anims.draw(g, anims.loop(BusyAssets.anim("finished_confetti_72x16"), nowMs), 0, 0, nowMs)
            val interval = timer.runInfo().config?.takeIf { it.mode == TimerMode.INTERVAL }?.interval
            // summary_label.c alternates the message with "cycles/cycles" for interval sessions.
            val showCycles = interval != null && ((nowMs - startMs) / 2000) % 2 == 1L
            val text = if (showCycles) "${interval!!.cycles}/${interval.cycles}" else "Well done!"
            g.text(text, FONT_LARGE, 36, 4, WHITE, Align.TOP_MID)
        }
    }

    /** A theme other than the default replaces the indicator with its background animation. */
    private fun themeBackground(name: String): String? {
        if (name == AppConfig.THEME_DEFAULT || name.isEmpty()) return null
        val json = storage.read(BusyAssets.theme(name))?.decodeToString() ?: return null
        val path = runCatching { Json.parseToJsonElement(json).jsonObject["bg_path"]?.jsonPrimitive?.content }.getOrNull()
        return path?.takeIf { it.endsWith(".anim") }
    }

    private companion object {
        const val LABEL_HIDDEN_MS = 15_000L
        const val LABEL_SHOWN_MS = 5_000L
        const val SUMMARY_MS = 3_000L
    }
}
