package com.busys8.engine.apps

import com.busys8.core.Gfx
import com.busys8.engine.input.InputEvent
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputType
import com.busys8.engine.system.BleControl
import com.busys8.engine.system.BleStatus
import com.busys8.engine.system.DeviceSettings
import com.busys8.engine.system.NoBle
import com.busys8.engine.system.Platform
import com.busys8.engine.system.SettingsChange
import java.time.ZoneId

/**
 * The SETTINGS position (main/settings_menu and the apps under applications/settings), limited to what the
 * phone can do: brightness, sound, time zone, network info and about. Wi-Fi, BLE, Matter,
 * account and firmware pages are left out. Controls match the BUSY app's setup screens.
 */
class SettingsApp(
    private val settings: DeviceSettings,
    private val platform: Platform,
    private val firmwareVersion: String,
    private val networkLines: () -> List<String>,
    private val ble: () -> BleControl = { NoBle },
) : NativeApp {
    override val priority = LoaderPriority.DEFAULT

    private val topPage: ListMenu = ListMenu("Settings") {
        listOf(
            ListMenu.Action("Brightness") { page = brightness() },
            ListMenu.Action("Sound") { page = sound() },
            ListMenu.Action("Sensors") { page = sensors() },
            ListMenu.Action("Time") { page = time() },
            ListMenu.Action("Network") { page = network() },
            ListMenu.Action("Bluetooth") { page = bluetooth() },
            ListMenu.Action("About") { page = about() },
        )
    }
    private var page: ListMenu = topPage

    override fun onEnter(nowMs: Long) {
        page = topPage
        topPage.flashTitle()
    }

    override fun onInput(event: InputEvent, nowMs: Long): Boolean {
        if (event.type != InputType.SHORT) return false
        if (page.input(event.key)) return true
        if (event.key == InputKey.BACK && page !== topPage) {
            page = topPage
            topPage.flashTitle()
            return true
        }
        return false
    }

    override fun render(front: Gfx, back: Gfx, nowMs: Long) {
        page.renderFront(front, nowMs)
        page.renderBack(back)
    }

    private fun brightness(): ListMenu = ListMenu("Brightness") {
        val level = settings.data.brightness
        listOf(
            ListMenu.Choice("Mode", listOf("Auto", "Manual"), { if (level == null) 0 else 1 }, set = { i ->
                setBrightness(if (i == 0) null else platform.actualBrightness().coerceIn(0, 100))
            }),
            ListMenu.Number("Level", 0, 100, 10, { level ?: platform.actualBrightness() }, { setBrightness(it) }, { "$it%" }, visible = level != null),
        )
    }

    private fun setBrightness(level: Int?) {
        settings.update(SettingsChange.BRIGHTNESS) { it.copy(brightness = level) }
        platform.applyBrightness(level)
    }

    /** Phone-only extras: the camera and microphone, each off until switched on here. */
    private fun sensors(): ListMenu = ListMenu("Sensors") {
        val d = settings.data
        listOf(
            ListMenu.Switch("Presence", { d.presence }, { on -> settings.update(SettingsChange.OTHER) { it.copy(presence = on) } }),
            ListMenu.Number("Away after", 1, 60, 1, { d.awayAfterMin }, { m ->
                settings.update(SettingsChange.OTHER) { it.copy(awayAfterMin = m) }
            }, { "$it min" }, visible = d.presence),
            ListMenu.Switch("Auto ON CALL", { d.autoOnCall }, { on -> settings.update(SettingsChange.OTHER) { it.copy(autoOnCall = on) } }),
        )
    }

    private fun sound(): ListMenu = ListMenu("Sound") {
        listOf(
            ListMenu.Number("Volume", 0, 100, 10, { platform.volume() }, { v ->
                platform.setVolume(v)
                settings.notifyChanged(SettingsChange.VOLUME)
            }, { "$it%" }),
        )
    }

    private val zones by lazy {
        ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted()
    }

    private fun time(): ListMenu = ListMenu("Time") {
        listOf(
            ListMenu.Choice("Time zone", zones.map { it.substringAfterLast('/').replace('_', ' ') }, { zones.indexOf(platform.timeZone()).coerceAtLeast(0) }, set = { i ->
                if (platform.setTimeZone(zones[i])) settings.notifyChanged(SettingsChange.TIMEZONE)
            }),
            ListMenu.Choice("Time format", listOf("24 h", "12 h"), { if (settings.data.clock12h) 1 else 0 }, set = { i ->
                settings.update(SettingsChange.OTHER) { it.copy(clock12h = i == 1) }
            }),
        )
    }

    /** ble_settings: on/off, the link's state, and forgetting the paired phone. */
    private fun bluetooth(): ListMenu = ListMenu("Bluetooth") {
        val b = ble()
        listOf(
            ListMenu.Switch("Bluetooth", { b.status != BleStatus.READY }, { on ->
                if (if (on) b.enable() else b.disable()) settings.update(SettingsChange.BLE) { it.copy(bleEnabled = on) }
            }),
            ListMenu.Action("Status", { b.status.api }) {},
            ListMenu.Action("Forget pairing") { b.forget() },
        )
    }

    private fun network(): ListMenu = ListMenu("Network") {
        val lines = networkLines()
        val wifi = platform.wifi()
        listOfNotNull(
            ListMenu.Action("Name", { lines.getOrNull(1) ?: "none" }) {},
            ListMenu.Action("Address", { lines.getOrNull(2) ?: "none" }) {},
            wifi?.let { w -> ListMenu.Action("Wi-Fi", { w.ssid }) {} },
            wifi?.let { w -> ListMenu.Action("Signal", { "${w.rssi} dBm" }) {} },
        )
    }

    private fun about(): ListMenu = ListMenu("About") {
        val p = platform.power()
        val upS = platform.uptimeMs() / 1000
        listOf(
            ListMenu.Action("Name", { settings.data.name }) {},
            ListMenu.Action("Version", { firmwareVersion }) {},
            ListMenu.Action("Battery", { "${p.chargePercent}% ${p.state.api}" }) {},
            ListMenu.Action("Uptime", { "%dd %02dh %02dm".format(upS / 86400, upS / 3600 % 24, upS / 60 % 60) }) {},
            ListMenu.Action("Serial", { platform.ids().serial }) {},
        )
    }
}
