package com.busys8

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import com.busys8.core.AssetSource
import com.busys8.core.BusyDevice
import com.busys8.core.ImageDecoder
import com.busys8.core.RawImage
import com.busys8.engine.Busybar
import com.busys8.engine.input.InputKey
import java.io.File
import java.io.FileNotFoundException
import java.net.Inet4Address
import java.net.NetworkInterface

/** Process-wide singletons: one emulated device per process, shared by the service and UI. */
class BusyS8App : Application() {
    lateinit var assetSource: AssetSource
        private set
    lateinit var device: BusyDevice
        private set
    lateinit var bar: Busybar
        private set
    private lateinit var sounds: SoundPlayer

    override fun onCreate() {
        super.onCreate()
        assetSource = AndroidAssetSource(assets)
        device = BusyDevice(assetSource, File(filesDir, "ext").apply { mkdirs() }, BitmapDecoder)
        sounds = SoundPlayer(assets, device.storage)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val switch = InputKey.fromApi(prefs.getString(KEY_SWITCH, null).orEmpty())?.takeIf { it.isSwitch } ?: InputKey.BUSY
        bar = Busybar(device, File(filesDir, "data"), sounds::play, switchPosition = switch)
        bar.desktop.onSwitchMoved = { prefs.edit().putString(KEY_SWITCH, it.apiName).apply() }
    }

    companion object {
        fun of(context: Context) = context.applicationContext as BusyS8App
        private const val PREFS = "busy_s8"
        private const val KEY_SWITCH = "switch_position"
    }
}

class AndroidAssetSource(private val assets: AssetManager) : AssetSource {
    override fun open(path: String) = try {
        assets.open(path)
    } catch (_: FileNotFoundException) {
        null
    }
}

object BitmapDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): RawImage? {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return RawImage(bmp.width, bmp.height, px).also { bmp.recycle() }
    }
}

/** Non-loopback IPv4 addresses, shown in the SETTINGS position. */
fun localIpv4(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>()
    .map { it.hostAddress }
