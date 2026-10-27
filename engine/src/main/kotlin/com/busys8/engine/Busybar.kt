package com.busys8.engine

import com.busys8.core.BusyDevice
import com.busys8.engine.apps.Desktop
import com.busys8.engine.apps.PlaceholderApp
import com.busys8.engine.apps.SoftOffApp
import com.busys8.engine.apps.busy.BusyApp
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputService
import com.busys8.engine.timer.AppConfig
import com.busys8.engine.timer.BusyTimer
import com.busys8.engine.timer.FileTimerStore
import com.busys8.engine.timer.ProfileSlot
import com.busys8.engine.timer.TimerAppHooks
import java.io.File

/**
 * The firmware's services and built-in apps on top of a [BusyDevice]. Everything here runs on
 * the device thread: use [BusyDevice.post] (or the server's withContext) to reach it.
 *
 * @param dataDir app data (APP_DATA_PATH): timer settings and the saved snapshot live here.
 * @param playSound plays a device sound path such as `/ext/apps_assets/busy/sounds/x.snd`.
 */
class Busybar(
    val device: BusyDevice,
    dataDir: File,
    playSound: (String) -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
) {
    private val hooks = LateHooks()
    val input = InputService(clock)
    val timer = BusyTimer(clock, FileTimerStore(File(dataDir, "busy_timer")), hooks)
    private val busyApp = BusyApp(timer, device.storage, device.images, device.anims, playSound, clock)
    private val softOff = SoftOffApp()
    private val appsMenu = PlaceholderApp("APPS", "JS apps arrive in phase 4")
    private val settings = PlaceholderApp("SETTINGS", "use the web UI for now")

    val desktop = Desktop(
        input, timer, device.canvas,
        apps = { position ->
            when (position) {
                InputKey.BUSY -> busyApp.also { it.slot = ProfileSlot.BUSY }
                InputKey.CUSTOM -> busyApp.also { it.slot = ProfileSlot.CUSTOM }
                InputKey.OFF -> softOff
                InputKey.APPS -> appsMenu
                else -> settings
            }
        },
        busyApp = busyApp,
        clock = clock,
    )

    init {
        busyApp.exitToDesktop = desktop::returnToSwitchApp
        hooks.bind(desktop)
        device.systemUi = desktop
    }

    /** Binds timer → app hooks once the desktop exists; a restored session may call them earlier. */
    private class LateHooks : TimerAppHooks {
        private var target: TimerAppHooks? = null
        private var pending: AppConfig? = null

        fun bind(t: TimerAppHooks) {
            target = t
            pending?.let(t::showTimer)
            pending = null
        }

        override fun showTimer(app: AppConfig) {
            target?.showTimer(app) ?: run { pending = app }
        }

        override fun exitTimer() {
            target?.exitTimer() ?: run { pending = null }
        }
    }
}
