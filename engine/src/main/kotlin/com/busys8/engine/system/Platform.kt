package com.busys8.engine.system

/**
 * What the emulated bar needs from the host: battery, network, identifiers and the knobs the
 * API turns. Android implements it with system services; tests use [FakePlatform].
 */
interface Platform {
    fun power(): PowerInfo

    /** null when Wi-Fi is off or unknown. */
    fun wifi(): WifiInfo?

    /** Stable per-device identifiers (serial, MACs). */
    fun ids(): DeviceIds

    /** Milliseconds since boot, and boot time as a Unix timestamp in seconds. */
    fun uptimeMs(): Long
    fun bootTimeS(): Long

    /** Sets the system clock; false when the host does not allow it. */
    fun setTime(epochMs: Long): Boolean

    /** Sets the system time zone (IANA id); false when the host does not allow it. */
    fun setTimeZone(id: String): Boolean
    fun timeZone(): String

    /** Applies a brightness of 0–100, or null for automatic. */
    fun applyBrightness(percent: Int?)

    /** Current panel brightness 0–100 (what "auto" resolved to). */
    fun actualBrightness(): Int

    /** Media volume 0–100. */
    fun volume(): Int
    fun setVolume(percent: Int)

    /** Plays a sound file (WAV bytes) once; stops any sound playing. */
    fun playSound(bytes: ByteArray)
    fun stopSound(): Boolean
}

enum class BatteryState(val api: String) { DISCHARGING("discharging"), CHARGING("charging"), CHARGED("charged") }

data class PowerInfo(
    val state: BatteryState,
    val chargePercent: Int,
    val voltageMv: Int,
    val currentMa: Int,
    val usbVoltageMv: Int,
    /** Battery temperature in °C; an S8 extra the firmware does not report. */
    val temperatureC: Double?,
)

data class WifiInfo(
    val ssid: String,
    val bssid: String,
    val channel: Int,
    val rssi: Int,
    val ipv4: String?,
    val gateway: String?,
    val netmask: String?,
)

data class DeviceIds(val serial: String, val usbMac: String, val wifiMac: String)

/** Deterministic stand-in for tests and the desktop preview. */
open class FakePlatform(private val clock: () -> Long = System::currentTimeMillis) : Platform {
    var brightness: Int? = null
    var vol = 50
    var zone = "UTC"
    val played = ArrayList<ByteArray>()

    override fun power() = PowerInfo(BatteryState.CHARGED, 100, 4200, 0, 5000, 30.0)
    override fun wifi() = WifiInfo("home", "aa:bb:cc:dd:ee:ff", 6, -50, "192.168.0.2", "192.168.0.1", "255.255.255.0")
    override fun ids() = DeviceIds("S8000000", "02:00:00:00:00:01", "02:00:00:00:00:02")
    override fun uptimeMs() = 3_600_000L
    override fun bootTimeS() = clock() / 1000 - 3600
    override fun setTime(epochMs: Long) = false
    override fun setTimeZone(id: String): Boolean {
        zone = id
        return true
    }
    override fun timeZone() = zone
    override fun applyBrightness(percent: Int?) {
        brightness = percent
    }
    override fun actualBrightness() = brightness ?: 60
    override fun volume() = vol
    override fun setVolume(percent: Int) {
        vol = percent
    }
    override fun playSound(bytes: ByteArray) {
        played += bytes
    }
    override fun stopSound() = played.isNotEmpty()
}
