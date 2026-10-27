package com.busys8.engine

import com.busys8.core.BusyDevice
import com.busys8.core.FileAssetSource
import com.busys8.core.ImageDecoder
import com.busys8.core.RawImage
import com.busys8.engine.apps.LoaderPriority
import com.busys8.engine.input.InputKey
import com.busys8.engine.timer.TimerState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

class BusybarTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_761_582_532_251L
    private val decoder = ImageDecoder { bytes ->
        ImageIO.read(ByteArrayInputStream(bytes))?.let { RawImage(it.width, it.height, it.getRGB(0, 0, it.width, it.height, null, 0, it.width)) }
    }
    private val sounds = ArrayList<String>()
    private val bar by lazy {
        val device = BusyDevice(FileAssetSource(File(System.getProperty("busys8.vendor"))), tmp.newFolder("ext"), decoder) { now }
        Busybar(device, File(tmp.root, "data"), { sounds += it.substringAfterLast('/') }, clock = { now })
    }
    private val previews = File("build/previews").apply { mkdirs() }

    /** Runs the device loop for [ms] at 30 fps with the fake clock, one completed frame per step. */
    private fun run(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 33L)
            now += step
            left -= step
            runBlocking { bar.device.post(this) {} }
        }
    }

    private fun press(key: InputKey) = runBlocking { bar.device.post(this) { bar.input.toggle(key) } }

    private fun snap(name: String) {
        val f = bar.device.frames.value.front
        val scale = 10
        val img = BufferedImage(72 * scale, 16 * scale, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 16 * scale) for (x in 0 until 72 * scale) img.setRGB(x, y, f[(y / scale) * 72 + x / scale])
        ImageIO.write(img, "png", File(previews, "$name.png"))
    }

    @Test
    fun `a default interval session runs from the buttons alone`() {
        run(1500)
        snap("1-start")
        assertEquals(LoaderPriority.PASSTHROUGH, bar.desktop.loaderPriority)

        press(InputKey.START)
        run(500)
        snap("2-overview")
        run(2500)
        assertEquals(TimerState.WORK, bar.timer.state)
        assertEquals(LoaderPriority.BLOCKING, bar.desktop.loaderPriority)
        run(60_000)
        snap("3-work")

        press(InputKey.START)
        run(1500)
        snap("4-paused")
        press(InputKey.START)

        press(InputKey.UP) // ~18:59 → 20:00
        run(100)
        // Run the rest of the first work interval; at 00:00 the next second rolls it over.
        run(20 * 60_000L + 2000)
        assertEquals(TimerState.REST, bar.timer.state)
        assertTrue(!bar.timer.isRunning)
        snap("5-progress")
        run(2500)
        snap("6-next")

        press(InputKey.START)
        run(60_000)
        snap("7-rest")
        assertTrue(bar.timer.isRunning)

        // Skip intervals 2, 3, 4 and the final one with OK (forced transitions start immediately).
        repeat(4) {
            press(InputKey.OK)
            run(1000)
        }
        assertEquals(TimerState.IDLE, bar.timer.state)
        run(1000)
        snap("8-ending")
        run(8000)
        snap("9-finish")
        assertTrue("session_completed.snd" in sounds)

        press(InputKey.START)
        run(1000)
        snap("10-back-to-start")
        assertEquals(LoaderPriority.PASSTHROUGH, bar.desktop.loaderPriority)
    }

    @Test
    fun `switch positions change app and a remote snapshot brings the timer forward`() {
        run(500)
        press(InputKey.OFF)
        run(500)
        assertTrue(bar.device.frames.value.front.all { it and 0xFFFFFF == 0 })

        val body = """{"snapshot":{"type":"SIMPLE","card_id":"12345678-1234-1234-1234-123456789abc","is_paused":false,"time_left_ms":240000,"busy_bar_settings":{"theme":"on_air","show_work_phase_only":false,"trigger_smart_home":true}},"snapshot_timestamp_ms":$now}"""
        runBlocking(bar.device.dispatcher) {
            val s = com.busys8.engine.timer.TimerJson.parseSnapshot(Json.parseToJsonElement(body), now)!!
            bar.timer.setSnapshot(s)
        }
        run(1000)
        assertEquals(LoaderPriority.BLOCKING, bar.desktop.loaderPriority)
        snap("theme-on-air")

        press(InputKey.BACK) // stop → leaves the timer, back to the OFF app
        run(500)
        assertEquals(TimerState.IDLE, bar.timer.state)
        assertEquals(0, bar.desktop.loaderPriority)
    }

    @Test
    fun `a paused session stays paused across a restart`() {
        run(500)
        press(InputKey.START)
        run(3000)
        press(InputKey.START)
        run(500)
        assertTrue(bar.timer.lastKnownSnapshot.isPaused)
        val device = BusyDevice(FileAssetSource(File(System.getProperty("busys8.vendor"))), tmp.newFolder("ext2"), decoder) { now }
        val restarted = Busybar(device, File(tmp.root, "data"), clock = { now })
        repeat(10) {
            now += 33
            runBlocking { device.post(this) {} }
        }
        assertTrue(restarted.timer.lastKnownSnapshot.isPaused)
        assertTrue(!restarted.timer.isRunning)
        assertEquals(LoaderPriority.BLOCKING, restarted.desktop.loaderPriority)
    }

    @Test
    fun `api draw is refused while a session runs`() {
        run(500)
        press(InputKey.START)
        run(3000)
        val text = """{"application_name":"x","priority":100,"elements":[{"id":"0","type":"text","text":"hi","font":"normal"}]}"""
        val result = runBlocking { withContext(bar.device.dispatcher) { bar.device.draw(Json.parseToJsonElement(text)) } }
        assertEquals(409, result.first)
    }
}
