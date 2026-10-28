package com.busys8.engine

import com.busys8.core.BusyDevice
import com.busys8.engine.apps.Desktop
import com.busys8.engine.apps.SettingsApp
import com.busys8.engine.apps.AppsMenuApp
import com.busys8.engine.apps.JsLauncher
import com.busys8.engine.apps.LoaderPriority
import com.busys8.engine.apps.SoftOffApp
import com.busys8.engine.apps.busy.BusyApp
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputService
import com.busys8.engine.jsapps.JsApps
import com.busys8.engine.jsapps.JsHost
import com.busys8.engine.system.ApiTokens
import com.busys8.engine.system.AutoOnCall
import com.busys8.engine.system.Presence
import com.busys8.engine.system.SmartHomeSwitch
import com.busys8.engine.system.DeviceSettings
import com.busys8.engine.system.SettingsChange
import com.busys8.engine.system.FakePlatform
import com.busys8.engine.system.Platform
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
    switchPosition: InputKey = InputKey.BUSY,
    val platform: Platform = FakePlatform(clock),
) {
    val settings = DeviceSettings(File(dataDir, "settings.json"))
    val tokens = ApiTokens(File(dataDir, "tokens.json"), clock)
    val jsApps = JsApps(File(device.storage.extRoot, "user_assets"), dataDir, API_VERSION)

    private val hooks = LateHooks()
    val input = InputService(clock)
    val timer = BusyTimer(clock, FileTimerStore(File(dataDir, "busy_timer")), hooks)
    /** The Bluetooth bridge; the host replaces this with its GATT server. */
    @Volatile var ble: com.busys8.engine.system.BleControl = com.busys8.engine.system.NoBle

    /** The smart-home switch (busy_timer_smart_home.c) and the Matter device carrying it. */
    val smartHome = SmartHomeSwitch(timer)
    @Volatile var matter: com.busys8.engine.system.MatterLink = com.busys8.engine.system.NoMatter

    /** Phone extras, driven by the host's camera and microphone (see [applySensorSettings]). */
    val presence = Presence(clock)
    val autoOnCall = AutoOnCall(timer, clock)
    private val busyApp = BusyApp(timer, device.storage, device.images, device.anims, playSound, clock)
    private val softOff = SoftOffApp()

    /** Origin of this bar's HTTP API, which `http://localhost` means inside JS apps. */
    @Volatile var localOrigin = "http://127.0.0.1:8080"

    /** Where JS apps' console output goes (app id, level, text). */
    @Volatile var jsConsole: (String, String, String) -> Unit = { id, level, text -> println("[$id] $level: $text") }

    private val jsHost = object : JsHost {
        override val localOrigin get() = this@Busybar.localOrigin
        override fun console(appId: String, level: String, text: String) = jsConsole(appId, level, text)
    }
    val launcher = JsLauncher(device.canvas, File(device.storage.extRoot, "apps_data/jsrunner"), jsHost)
    private val appsMenu = AppsMenuApp(jsApps) { launchApp(it.manifest.id) }
    /** What the SETTINGS position shows (device name and addresses), set by the host. */
    @Volatile var infoLines: List<String> = emptyList()
    private val settingsApp = SettingsApp(
        settings, platform, FIRMWARE_VERSION, { infoLines }, device.images, device.anims,
        ble = { ble }, matter = { matter },
    )

    val desktop = Desktop(
        input, timer, device.canvas,
        apps = { position ->
            when (position) {
                InputKey.BUSY -> busyApp.also { it.slot = ProfileSlot.BUSY }
                InputKey.CUSTOM -> busyApp.also { it.slot = ProfileSlot.CUSTOM }
                InputKey.OFF -> softOff
                InputKey.APPS -> appsMenu
                else -> settingsApp
            }
        },
        busyApp = busyApp,
        clock = clock,
        initialPosition = switchPosition,
    )

    init {
        platform.applyBrightness(settings.data.brightness)
        applySensorSettings()
        smartHome.output = { matter.send(it) }
        settings.subscribe { if (it == SettingsChange.OTHER) device.post(sensorScope) { applySensorSettings() } }
        busyApp.exitToDesktop = desktop::returnToSwitchApp
        launcher.onLeave = desktop::returnToSwitchApp
        hooks.bind(desktop)
        device.systemUi = desktop
    }

    private val sensorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

    /** Device thread. */
    private fun applySensorSettings() {
        val d = settings.data
        presence.enabled = d.presence
        presence.timeoutMs = d.awayAfterMin * 60_000L
        autoOnCall.enabled = d.autoOnCall
    }

    enum class LaunchResult { OK, NOT_FOUND, API_MISMATCH, LOW_PRIORITY }

    /** js_app_launcher_start in resume mode (/api/apps/launch, the APPS menu). Device thread. */
    fun launchApp(id: String): LaunchResult {
        val app = jsApps.get(id) ?: return LaunchResult.NOT_FOUND
        if (!jsApps.isCompatible(app.manifest.apiVersion)) return LaunchResult.API_MISMATCH
        if (desktop.loaderPriority > LoaderPriority.DEFAULT) return LaunchResult.LOW_PRIORITY
        desktop.replaceCurrentApp(launcher)
        launcher.start(app)
        return LaunchResult.OK
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

    companion object {
        /** The BUSY Bar HTTP API version this build implements (vendor/openapi.yaml). */
        const val API_VERSION = "27.14.0"

        /** Reported as the firmware version (/api/status/firmware, About). */
        const val FIRMWARE_VERSION = "busy-s8-0.6.0"
    }
}
