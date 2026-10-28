package com.busys8.engine.apps

import com.busys8.core.Align
import com.busys8.core.ApiFont
import com.busys8.core.Canvas
import com.busys8.core.Gfx
import com.busys8.core.SystemUi
import com.busys8.engine.input.InputEvent
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputService
import com.busys8.engine.input.InputType
import com.busys8.engine.timer.AppConfig
import com.busys8.engine.timer.BusyTimer
import com.busys8.engine.timer.TimerAppHooks

/** A built-in app (applications/main/...), painted under the API canvas. */
interface NativeApp {
    /** Loader priority while this app is in front (see loader.h). */
    val priority: Int

    fun onEnter(nowMs: Long) {}

    fun onExit() {}

    /** Returns true if consumed. */
    fun onInput(event: InputEvent, nowMs: Long): Boolean = false

    /** Input seen before the API canvas (GuiLayerIdSystem); returns true if consumed. */
    fun onSystemInput(event: InputEvent, nowMs: Long): Boolean = false

    fun tick(nowMs: Long) {}

    fun render(front: Gfx, back: Gfx, nowMs: Long)
}

object LoaderPriority {
    const val STUB = 0
    const val PASSTHROUGH = 9
    const val DEFAULT = 10
    const val BLOCKING = 101
}

/**
 * The desktop service: maps the mode switch to an app, routes input, and lets the timer bring
 * the BUSY app forward when a session starts remotely (busy_timer_util.c).
 *
 * Input order follows the GUI layers: while the API canvas is open it consumes every key
 * (canvas_srv_input_callback), but the switch position is tracked separately, so flipping the
 * switch both closes the canvas and changes app.
 */
class Desktop(
    private val input: InputService,
    private val timer: BusyTimer,
    private val canvas: Canvas,
    private val apps: (InputKey) -> NativeApp,
    private val busyApp: BusyAppHost,
    private val clock: () -> Long,
    initialPosition: InputKey = InputKey.BUSY,
) : SystemUi, TimerAppHooks {
    var switchPosition = initialPosition
        private set

    /** Notified when the switch moves, so the host can persist it like a physical switch. */
    var onSwitchMoved: (InputKey) -> Unit = {}
    private var current: NativeApp = apps(switchPosition)
    private var started = false

    override val loaderPriority get() = current.priority

    init {
        input.subscribe(::onInput)
    }

    fun setSwitch(position: InputKey) {
        require(position.isSwitch)
        switchPosition = position
        onSwitchMoved(position)
        canvas.exit()
        // BUSY and CUSTOM share one app instance with different profiles, so always re-enter.
        switchTo(apps(position), reenter = true)
    }

    override fun showTimer(app: AppConfig) {
        busyApp.showTimer(app)
        switchTo(busyApp.app, reenter = false)
    }

    override fun exitTimer() {
        if (current === busyApp.app) busyApp.requestExit()
    }

    /** Called by the BUSY app when it leaves a timer it was brought forward for. */
    fun returnToSwitchApp() = switchTo(apps(switchPosition), reenter = true)

    /** desktop_replace_current_app: puts [app] in front until it hands back. */
    fun replaceCurrentApp(app: NativeApp) = switchTo(app, reenter = true)

    override fun tick(nowMs: Long) {
        if (!started) {
            started = true
            current.onEnter(nowMs)
        }
        input.tick()
        timer.poll()
        current.tick(nowMs)
    }

    override fun render(front: Gfx, back: Gfx, nowMs: Long) {
        current.render(front, back, nowMs)
        drawStatusBar(back)
    }

    private fun switchTo(app: NativeApp, reenter: Boolean) {
        if (app === current && !reenter) return
        current.onExit()
        current = app
        if (started) current.onEnter(clock())
    }

    private fun onInput(event: InputEvent) {
        if (event.key.isSwitch) {
            if (event.type == InputType.SHORT) setSwitch(event.key)
            return
        }
        if (current.onSystemInput(event, clock())) return
        if (canvas.isOpen) {
            if (event.key == InputKey.BACK && event.type == InputType.SHORT) canvas.backPressed()
            return
        }
        current.onInput(event, clock())
    }

    /** The back display's 12 px status column: just the switch position's initial for now. */
    private fun drawStatusBar(back: Gfx) {
        val label = switchPosition.apiName.first().uppercase()
        back.text(label, ApiFont.SMALL, 154, 2, STATUS_COLOR, Align.TOP_MID)
    }

    private companion object {
        const val STATUS_COLOR = 0xFF808080.toInt()
    }
}

/** How the desktop drives the BUSY app (busy.h: busy_show_timer / busy_request_exit). */
interface BusyAppHost {
    val app: NativeApp
    fun showTimer(config: AppConfig)
    fun requestExit()
}

/** soft_off: the OFF position. Everything dark, so the AMOLED pixels are off. */
class SoftOffApp : NativeApp {
    override val priority = LoaderPriority.STUB
    override fun render(front: Gfx, back: Gfx, nowMs: Long) = Unit
}
