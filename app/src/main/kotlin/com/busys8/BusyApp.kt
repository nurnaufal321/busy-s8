package com.busys8

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import com.busys8.core.AssetSource
import com.busys8.core.BusyDevice
import com.busys8.core.ImageDecoder
import com.busys8.core.RawImage
import java.io.File
import java.io.FileNotFoundException
import java.net.Inet4Address
import java.net.NetworkInterface

/** Process-wide singletons: one emulated device per process, shared by the service and UI. */
class BusyApp : Application() {
    lateinit var assetSource: AssetSource
        private set
    lateinit var device: BusyDevice
        private set

    override fun onCreate() {
        super.onCreate()
        assetSource = AndroidAssetSource(assets)
        device = BusyDevice(assetSource, File(filesDir, "ext").apply { mkdirs() }, BitmapDecoder)
    }

    companion object {
        fun of(context: Context) = context.applicationContext as BusyApp
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

/** Non-loopback IPv4 addresses, for the idle screen. */
fun localIpv4(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>()
    .map { it.hostAddress }
