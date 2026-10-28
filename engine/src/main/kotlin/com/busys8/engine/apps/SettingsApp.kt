package com.busys8.engine.apps

import com.busys8.core.AnimLoader
import com.busys8.core.Gfx
import com.busys8.core.ImageLoader
import com.busys8.engine.input.InputEvent
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputType
import com.busys8.engine.system.BleControl
import com.busys8.engine.system.DeviceSettings
import com.busys8.engine.system.MatterLink
import com.busys8.engine.system.NoBle
import com.busys8.engine.system.NoMatter
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
    images: ImageLoader,
    anims: AnimLoader,
    private val ble: () -> BleControl = { NoBle },
    private val matter: () -> MatterLink = { NoMatter },
) : NativeApp {
    override val priority = LoaderPriority.DEFAULT

    private val topPage: ListMenu = ListMenu("Settings") {
        listOf(
            ListMenu.Action("Brightness") { page = brightness() },
            ListMenu.Action("Sound") { page = sound() },
            ListMenu.Action("Sensors") { page = sensors() },
            ListMenu.Action("Time") { page = time() },
            ListMenu.Action("Network") { page = network() },
            ListMenu.Action("Bluetooth") { openBluetooth = true },
            ListMenu.Action("Smart home") { page = smartHome() },
            ListMenu.Action("About") { page = about() },
        )
    }
    private var page: ListMenu = topPage

    private val bluetooth = BluetoothScreen(ble, { settings.data.name }, images, anims) { on ->
        settings.update(SettingsChange.BLE) { it.copy(bleEnabled = on) }
    }

    /** The Bluetooth screen while it is open, in place of the list. */
    private var screen: BluetoothScreen? = null
    private var openBluetooth = false

    override fun onEnter(nowMs: Long) {
        page = topPage
        topPage.flashTitle()
    }

    override fun onExit() {
        screen?.exit()
        screen = null
    }

    override fun tick(nowMs: Long) {
        val s = screen ?: return
        s.tick(nowMs)
        if (s.finished) closeScreen()
    }

    override fun onInput(event: InputEvent, nowMs: Long): Boolean {
        if (event.type != InputType.SHORT) return false
        screen?.let { s ->
            val used = s.input(event.key, nowMs)
            if (s.finished) closeScreen()
            return used
        }
        if (page.input(event.key)) {
            if (openBluetooth) {
                openBluetooth = false
                screen = bluetooth.also { it.enter(nowMs) }
            }
            return true
        }
        if (event.key == InputKey.BACK && page !== topPage) {
            page = topPage
            topPage.flashTitle()
            return true
        }
        return false
    }

    private fun closeScreen() {
        screen = null
        topPage.flashTitle()
    }

    override fun render(front: Gfx, back: Gfx, nowMs: Long) {
        screen?.let { return it.render(front, back, nowMs) }
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

    /** Matter: the code to pair with, how many ecosystems have, and a fresh pairing window. */
    private fun smartHome(): ListMenu = ListMenu("Smart home") {
        val m = matter()
        if (!m.available) return@ListMenu listOf(ListMenu.Action("Status", { "unavailable" }) {})
        val code = m.manualCode?.let { "${it.take(4)}-${it.drop(4).take(3)}-${it.drop(7)}" } ?: "..."
        listOf(
            ListMenu.Action("Pair", { code }) { m.openWindow() },
            ListMenu.Action("Paired with", { "${m.fabricCount}" }) {},
            ListMenu.Action("Forget all", { if (m.fabricCount == 0) "" else "${m.fabricCount}" }) { m.factoryReset() },
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
