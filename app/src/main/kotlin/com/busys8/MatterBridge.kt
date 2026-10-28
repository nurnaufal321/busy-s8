package com.busys8

import android.content.Context
import android.util.Log
import com.busys8.engine.Busybar
import com.busys8.engine.system.MatterLink
import kotlinx.coroutines.CoroutineScope
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

/**
 * The bar's Matter device: runs busy-matter (matter/, rs-matter), shipped in the APK as
 * `libbusymatter.so` so Android extracts it with the native libraries, and talks to it in lines
 * over stdin/stdout (see matter/src/main.rs). Restarts it if it exits.
 *
 * Pairings live in `files/matter`; [factoryReset] deletes them, like matter_factory_reset.
 */
class MatterBridge(private val context: Context, private val bar: Busybar, private val scope: CoroutineScope) : MatterLink {
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libbusymatter.so")
    private val dataDir = File(context.filesDir, "matter")

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var stopping = false

    @Volatile override var fabricCount = 0
        private set
    @Volatile override var manualCode: String? = null
        private set
    @Volatile override var qrCode: String? = null
        private set
    @Volatile override var pairingStatus = "never_started"
        private set
    @Volatile override var pairingStatusAtMs = 0L
        private set
    @Volatile var windowOpen = false
        private set
    private var fabricsWhenOpened = 0

    override val available get() = process?.isAlive == true

    fun start() {
        stopping = false
        if (!binary.canExecute()) {
            Log.w(TAG, "no Matter binary for this ABI ($binary)")
            return
        }
        Thread(::supervise, "matter-supervisor").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopping = true
        writer?.let { runCatching { it.close() } } // EOF on stdin ends busy-matter
        process?.destroy()
    }

    private fun supervise() {
        while (!stopping) {
            val p = runCatching {
                dataDir.mkdirs()
                ProcessBuilder(binary.path, dataDir.path, bar.platform.ids().serial)
                    .apply { environment()["RUST_LOG"] = "info" }
                    .start()
            }.getOrElse {
                Log.w(TAG, "cannot start busy-matter", it)
                return
            }
            process = p
            writer = p.outputStream.bufferedWriter()
            Thread({ p.errorStream.bufferedReader().forEachLine(::onLog) }, "matter-log").apply { isDaemon = true }.start()
            // Re-send the bar's view, in case the switch changed while it was down.
            bar.smartHome.state?.let { send(it) }
            runCatching { p.inputStream.bufferedReader().forEachLine(::onLine) }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            process = null
            writer = null
            if (!stopping) {
                Log.w(TAG, "busy-matter exited ($code); restarting")
                Thread.sleep(RESTART_DELAY_MS)
            }
        }
    }

    private fun onLine(line: String) {
        val (key, value) = line.trim().split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        when (key) {
            "switch" -> {
                val on = value == "on"
                bar.device.post(scope) { bar.smartHome.handleSwitch(on) }
            }
            "pairing" -> manualCode = value
            "fabrics" -> {
                val n = value.toIntOrNull() ?: return
                if (n > fabricCount && pairingStatus == "started") status("completed_successfully")
                fabricCount = n
            }
            "window" -> {
                val open = value == "open"
                if (open && !windowOpen) {
                    fabricsWhenOpened = fabricCount
                    status("started")
                } else if (!open && windowOpen && pairingStatus == "started" && fabricCount <= fabricsWhenOpened) {
                    status("failed")
                }
                windowOpen = open
            }
            else -> Log.d(TAG, "unknown line: $line")
        }
    }

    private fun status(s: String) {
        pairingStatus = s
        pairingStatusAtMs = System.currentTimeMillis()
    }

    private fun onLog(line: String) {
        QR.find(line)?.let { qrCode = it.groupValues[1] }
        Log.i(TAG, line.substringAfter("Z ").trim())
    }

    private fun command(line: String): Boolean = synchronized(this) {
        val w = writer ?: return false
        try {
            w.write(line)
            w.newLine()
            w.flush()
            true
        } catch (e: IOException) {
            false
        }
    }

    override fun send(on: Boolean) {
        command(if (on) "on" else "off")
    }

    override fun openWindow(): Int? = if (command("open")) WINDOW_SECONDS else null

    override fun factoryReset(): Boolean {
        val p = process ?: return false
        Log.i(TAG, "forgetting all pairings")
        writer?.let { runCatching { it.close() } }
        p.destroy()
        runCatching { p.waitFor() }
        // Keep `pairing` (the passcode), drop fabrics, sessions and attribute state.
        File(dataDir, "kv").deleteRecursively()
        fabricCount = 0
        status("never_started")
        return true
    }

    private companion object {
        const val TAG = "Matter"
        const val RESTART_DELAY_MS = 3000L
        /** MAX_COMM_WINDOW_TIMEOUT_SECS. */
        const val WINDOW_SECONDS = 900
        val QR = Regex("SetupQRCode: \\[(MT:[^\\]]+)]")
    }
}
