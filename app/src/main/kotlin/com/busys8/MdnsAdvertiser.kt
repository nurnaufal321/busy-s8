package com.busys8

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Announces the bar on the LAN like the firmware (web_server.c + services/discovery):
 *
 * - hostname `<device name, alphanumerics lowercased>.local` (`busybar.local` by default),
 *   so a browser can reach `http://busybar.local:8080` without knowing the IP;
 * - an `_http._tcp` service named `busybar-<usb mac hex>` with TXT `path=/` and `name=<name>`,
 *   which busy-hacs and other tools discover.
 *
 * Uses JmDNS because Android's NsdManager cannot publish a custom hostname.
 */
class MdnsAdvertiser(context: Context, private val port: Int) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val lock = wifi.createMulticastLock("busy-s8-mdns").apply { setReferenceCounted(false) }
    private val executor = Executors.newSingleThreadExecutor()
    private var jmdns: JmDNS? = null
    private var announced: Triple<String, String, String>? = null

    /** (Re)announces when the address, name or MAC changed; cheap to call repeatedly. */
    fun advertise(address: String?, usbMac: String, deviceName: String) = executor.execute {
        val key = Triple(address.orEmpty(), usbMac, deviceName)
        if (key == announced) return@execute
        close()
        val ip = address?.let { runCatching { InetAddress.getByName(it) }.getOrNull() } as? Inet4Address ?: return@execute
        runCatching {
            lock.acquire()
            val mdns = JmDNS.create(ip, hostname(deviceName))
            val service = ServiceInfo.create(
                "_http._tcp.local.",
                "busybar-" + usbMac.replace(":", "").lowercase(),
                port,
                0, 0,
                mapOf("path" to "/", "name" to deviceName),
            )
            mdns.registerService(service)
            jmdns = mdns
            announced = key
            Log.i(TAG, "announced ${hostname(deviceName)}.local and ${service.name} on $ip")
        }.onFailure { Log.w(TAG, "mDNS announce failed", it) }
    }

    fun stop() = executor.execute { close() }

    private fun close() {
        jmdns?.let { runCatching { it.unregisterAllServices(); it.close() } }
        jmdns = null
        announced = null
        if (lock.isHeld) lock.release()
    }

    companion object {
        private const val TAG = "Mdns"

        /** discovery_device_name_to_hostname: alphanumerics only, lowercased. */
        fun hostname(deviceName: String) = deviceName.filter { it.isLetterOrDigit() && it.code < 128 }.lowercase().ifEmpty { "busybar" }
    }
}
