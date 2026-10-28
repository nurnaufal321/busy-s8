package com.busys8

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.busys8.engine.system.BatteryState
import com.busys8.engine.system.DeviceIds
import com.busys8.engine.system.Platform
import com.busys8.engine.system.PowerInfo
import com.busys8.engine.system.WifiInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.NetworkInterface
import java.util.TimeZone

/**
 * [Platform] on the phone. Clock and time zone changes need device owner (DevicePolicyManager
 * on API 28); brightness is a window override the activity applies, plus the system's own
 * auto-brightness for "auto".
 */
class AndroidPlatform(private val context: Context) : Platform {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private var player: MediaPlayer? = null

    private val _brightness = MutableStateFlow<Int?>(null)

    /** Manual brightness 0–100 for the activity's window, or null to follow the system (auto). */
    val brightness: StateFlow<Int?> = _brightness

    private val isOwner get() = dpm.isDeviceOwnerApp(context.packageName)

    override fun power(): PowerInfo {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val state = when {
            status == BatteryManager.BATTERY_STATUS_FULL -> BatteryState.CHARGED
            status == BatteryManager.BATTERY_STATUS_CHARGING -> BatteryState.CHARGING
            else -> BatteryState.DISCHARGING
        }
        val currentUa = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return PowerInfo(
            state = state,
            chargePercent = if (level >= 0) level * 100 / scale else 0,
            voltageMv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            // Samsung reports µA; a value this small is already mA.
            currentMa = if (kotlin.math.abs(currentUa) > 10_000) currentUa / 1000 else currentUa,
            usbVoltageMv = if (plugged) USB_MV else 0,
            temperatureC = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 },
        )
    }

    @Suppress("DEPRECATION") // ConnectionInfo is the API-28 way to read the current network.
    override fun wifi(): WifiInfo? {
        if (!wifi.isWifiEnabled) return null
        val info = wifi.connectionInfo ?: return null
        if (info.networkId == -1) return null
        val dhcp = wifi.dhcpInfo
        return WifiInfo(
            ssid = info.ssid.removeSurrounding("\"").takeUnless { it == "<unknown ssid>" } ?: "unknown",
            bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" } ?: "00:00:00:00:00:00",
            channel = frequencyToChannel(info.frequency),
            rssi = info.rssi,
            ipv4 = ipString(info.ipAddress).takeIf { info.ipAddress != 0 },
            gateway = dhcp?.gateway?.let(::ipString),
            netmask = dhcp?.netmask?.takeIf { it != 0 }?.let(::ipString),
        )
    }

    override fun ids(): DeviceIds {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty().padEnd(16, '0')
        val wlanMac = runCatching {
            NetworkInterface.getByName("wlan0")?.hardwareAddress?.joinToString(":") { "%02x".format(it) }
        }.getOrNull()
        // A stable, locally administered MAC derived from ANDROID_ID stands in for the bar's USB MAC.
        val usbMac = "02:" + androidId.takeLast(10).chunked(2).joinToString(":")
        return DeviceIds(serial = androidId.take(8).uppercase(), usbMac = usbMac, wifiMac = wlanMac ?: "02:00:00:00:00:00")
    }

    override fun uptimeMs() = SystemClock.elapsedRealtime()

    override fun bootTimeS() = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000

    override fun setTime(epochMs: Long): Boolean = owner("setTime") {
        dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "0")
        dpm.setTime(admin, epochMs)
    }

    override fun setTimeZone(id: String): Boolean = owner("setTimeZone") {
        dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME_ZONE, "0")
        dpm.setTimeZone(admin, id)
    }

    override fun timeZone(): String = TimeZone.getDefault().id

    override fun applyBrightness(percent: Int?) {
        _brightness.value = percent
        owner("brightness") {
            dpm.setSystemSetting(
                admin,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                (if (percent == null) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL).toString(),
            )
            true
        }
    }

    override fun actualBrightness(): Int = _brightness.value
        ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255)

    override fun volume(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    override fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max + 50) / 100, 0)
    }

    @Synchronized
    override fun playSound(bytes: ByteArray) {
        stopSound()
        val file = File(context.cacheDir, "api_sound.wav").apply { writeBytes(bytes) }
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { it.release(); synchronized(this@AndroidPlatform) { if (player === it) player = null } }
            prepare()
            start()
        }
    }

    @Synchronized
    override fun stopSound(): Boolean {
        val p = player ?: return false
        player = null
        runCatching { p.stop() }
        p.release()
        return true
    }

    private fun owner(what: String, block: () -> Boolean): Boolean {
        if (!isOwner) return false
        return runCatching(block).onFailure { Log.w(TAG, "$what failed", it) }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "AndroidPlatform"
        const val USB_MV = 5000

        fun ipString(ip: Int) = "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"

        fun frequencyToChannel(mhz: Int) = when {
            mhz == 2484 -> 14
            mhz in 2412..2472 -> (mhz - 2407) / 5
            mhz in 5000..5900 -> (mhz - 5000) / 5
            else -> 0
        }
    }
}
