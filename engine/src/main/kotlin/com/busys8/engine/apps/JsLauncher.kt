package com.busys8.engine.apps

import com.busys8.core.Align
import com.busys8.core.ApiFont
import com.busys8.core.Canvas
import com.busys8.core.Gfx
import com.busys8.engine.input.InputEvent
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputType
import com.busys8.engine.jsapps.JsApp
import com.busys8.engine.jsapps.JsApps
import com.busys8.engine.jsapps.JsHost
import com.busys8.engine.jsapps.JsRun
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * js_app_launcher in resume mode: runs one JS app in front, shows "Loading..." under whatever
 * the app draws, and an error screen if the script fails. A long BACK force-quits; the app
 * otherwise receives input ahead of the API canvas, as js_input sits on the system GUI layer.
 */
class JsLauncher(
    private val canvas: Canvas,
    /** `/ext/apps_data/jsrunner`, where each app's localStorage lives. */
    private val storageDir: File,
    private val host: JsHost,
) : NativeApp {
    override val priority = LoaderPriority.DEFAULT

    /** Called on the device thread when the launcher is done and the desktop should take over. */
    var onLeave: () -> Unit = {}

    @Volatile private var run: JsRun? = null
    private var app: JsApp? = null
    private var error: String? = null
    private val finished = AtomicReference<Pair<JsRun, String?>?>()
    private var clearOnBack = false

    /** The app in front (js_app_launcher_get_running_app_id); safe from any thread. */
    val runningAppId: String? get() = run?.appId

    /** Starts [app], replacing any running one. Device thread. */
    fun start(app: JsApp) {
        run?.let { stopRun(it) }
        this.app = app
        error = null
        clearOnBack = canvas.clearOnBack
        canvas.clearOnBack = false
        val r = JsRun(app, File(storageDir, "${app.manifest.id}.localstorage.json"), host) { done, err -> finished.set(done to err) }
        run = r
        r.start()
    }

    /** Force-quits the running app; false if none runs. Device thread. */
    fun quit(): Boolean {
        val r = run ?: return false
        stopRun(r)
        onLeave()
        return true
    }

    override fun onExit() {
        run?.let { stopRun(it) }
        error = null
    }

    override fun tick(nowMs: Long) {
        val (r, err) = finished.getAndSet(null) ?: return
        if (r !== run) return
        stopRun(r)
        if (err == null) onLeave() else error = err
    }

    override fun onSystemInput(event: InputEvent, nowMs: Long): Boolean {
        val r = run ?: return false
        if (event.key == InputKey.BACK && event.type == InputType.LONG) {
            quit()
            return true
        }
        return r.deliver(event)
    }

    override fun onInput(event: InputEvent, nowMs: Long): Boolean {
        if (error == null || event.type != InputType.SHORT) return false
        if (event.key != InputKey.BACK && event.key != InputKey.OK && event.key != InputKey.START) return false
        error = null
        onLeave()
        return true
    }

    override fun render(front: Gfx, back: Gfx, nowMs: Long) {
        val name = app?.manifest?.name.orEmpty()
        val err = error
        if (err != null) {
            front.text("Error", ApiFont.NORMAL, 36, 8, ERROR_COLOR, Align.CENTER)
            back.text(name, ApiFont.NORMAL, 74, 14, WHITE, Align.CENTER)
            wrap(err, 34).take(6).forEachIndexed { i, line -> back.text(line, ApiFont.SMALL, 74, 28 + i * 8, GRAY, Align.CENTER) }
        } else if (run != null) {
            front.text("Loading...", ApiFont.NORMAL, 36, 8, WHITE, Align.CENTER)
            back.text(name, ApiFont.LARGE, 74, 30, WHITE, Align.CENTER)
            back.text("Loading...", ApiFont.SMALL, 74, 48, GRAY, Align.CENTER)
        }
    }

    /** Stops [r] and removes what it drew (js_app_launcher_scene_run_clear_canvas). */
    private fun stopRun(r: JsRun) {
        r.stop()
        if (run === r) run = null
        canvas.clear(r.appId, null)
        canvas.clearOnBack = clearOnBack
    }

    private fun wrap(text: String, width: Int): List<String> {
        val lines = ArrayList<String>()
        for (para in text.lines()) {
            var line = ""
            for (word in para.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (next.length > width && line.isNotEmpty()) {
                    lines += line
                    line = word
                } else {
                    line = next
                }
            }
            if (line.isNotEmpty()) lines += line
        }
        return lines
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GRAY = 0xFF808080.toInt()
        const val ERROR_COLOR = 0xFFFF3B30.toInt()
    }
}

/** apps_menu: the APPS position lists the installed JS apps; selecting one launches it. */
class AppsMenuApp(private val apps: JsApps, private val launch: (JsApp) -> Unit) : NativeApp {
    override val priority = LoaderPriority.DEFAULT
    private var installed: List<JsApp> = emptyList()
    private val menu = ListMenu("Apps") { installed.map { app -> ListMenu.Action(app.manifest.name) { launch(app) } } }

    override fun onEnter(nowMs: Long) {
        installed = apps.list().sortedBy { it.manifest.name.lowercase() }
        menu.flashTitle()
    }

    override fun onInput(event: InputEvent, nowMs: Long): Boolean = event.type == InputType.SHORT && menu.input(event.key)

    override fun render(front: Gfx, back: Gfx, nowMs: Long) {
        if (installed.isEmpty()) {
            front.text("No apps", ApiFont.NORMAL, 36, 8, 0xFFFFFFFF.toInt(), Align.CENTER)
            back.text("No apps", ApiFont.LARGE, 74, 30, 0xFFFFFFFF.toInt(), Align.CENTER)
            back.text("Install one with busy-cli", ApiFont.SMALL, 74, 48, 0xFF808080.toInt(), Align.CENTER)
            return
        }
        menu.renderFront(front, nowMs)
        menu.renderBack(back)
    }
}
