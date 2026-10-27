package com.busys8.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DrawTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private val device by lazy { BusyDevice(vendorAssets, tmp.root, imageIoDecoder) { now } }

    private fun draw(json: String) = runBlocking { device.draw(Json.parseToJsonElement(json)) }

    private fun front(): Framebuffer =
        Framebuffer(72, 16).also { device.frames.value.front.copyInto(it.pixels) }

    @Test
    fun `text draws at the anchor with LVGL metrics`() {
        val (code, err) = draw("""{"application_name":"t","elements":[{"id":"0","type":"text","text":"A","font":"normal","x":0,"y":0}]}""")
        assertEquals(null, err)
        assertEquals(200, code)
        // Line height 11, baseline 2: a 7 px cap glyph occupies rows 2..8.
        assertEquals(
            listOf("..#..", "..#..", ".#.#.", ".#.#.", ".###.", "#...#", "#...#").joinToString("\n"),
            front().ascii(0, 2, 5, 7),
        )
        assertEquals(".....", front().ascii(0, 1, 5, 1))
    }

    @Test
    fun `center align positions the label box around the anchor`() {
        draw("""{"application_name":"t","elements":[{"id":"0","type":"text","text":"A","font":"normal","x":36,"y":8,"align":"center"}]}""")
        // Box is 6x11 → top-left (33, 3); the glyph's first lit pixel is at (35, 5).
        val fb = front()
        assertTrue(fb[35, 5] and 0xFFFFFF != 0)
        assertEquals(0, fb[34, 5] and 0xFFFFFF)
    }

    @Test
    fun `parser errors match the firmware strings`() {
        assertEquals(400 to "Missing application_name", draw("""{"elements":[]}"""))
        assertEquals(400 to "Elements array is empty", draw("""{"application_name":"t","elements":[]}"""))
        assertEquals(400 to "Priority must be <= 100", draw("""{"application_name":"t","priority":101,"elements":[]}"""))
        assertEquals(
            400 to "elements[0].font value is invalid",
            draw("""{"application_name":"t","elements":[{"id":"0","type":"text","text":"x","font":"huge"}]}"""),
        )
        assertEquals(
            400 to "elements[0].{path,stock_path}: exactly one of these must be present",
            draw("""{"application_name":"t","elements":[{"id":"0","type":"image"}]}"""),
        )
    }

    @Test
    fun `stock image resolves to the bundled PNG`() {
        val (code, err) = draw("""{"application_name":"t","elements":[{"id":"i","type":"image","stock_path":"shared/checkmark_front_8x8.image"}]}""")
        assertEquals(null, err)
        assertEquals(200, code)
    }

    @Test
    fun `other apps need a strictly higher priority, else they wait then get 409`() {
        val text = """{"id":"0","type":"text","text":"x","font":"normal"}"""
        assertEquals(200, draw("""{"application_name":"a","priority":60,"elements":[$text]}""").first)
        // The same app may redraw at equal priority; a different app must beat it (canvas_draw_rejected).
        assertEquals(200, draw("""{"application_name":"a","priority":60,"elements":[$text]}""").first)
        assertEquals(200, draw("""{"application_name":"b","priority":61,"elements":[$text]}""").first)
        val canvas = device.canvas
        var result: CanvasResult? = null
        runBlocking(device.dispatcher) {
            val req = DrawRequestParser(device.images).parse(Json.parseToJsonElement("""{"application_name":"c","priority":61,"elements":[$text]}"""))
            canvas.show(req) { result = it }
            assertEquals(null, result)
            now += Canvas.DEFERRED_TIMEOUT_MS
            canvas.tick()
        }
        assertEquals(CanvasResult.LOW_PRIORITY, result)
        assertEquals("b", canvas.appId)
    }

    @Test
    fun `deferred draw goes through once the canvas is cleared`() {
        val text = """{"id":"0","type":"text","text":"x","font":"normal"}"""
        draw("""{"application_name":"a","priority":60,"elements":[$text]}""")
        var result: CanvasResult? = null
        runBlocking(device.dispatcher) {
            val req = DrawRequestParser(device.images).parse(Json.parseToJsonElement("""{"application_name":"b","priority":60,"elements":[$text]}"""))
            device.canvas.show(req) { result = it }
            device.canvas.clear("a", null)
            device.canvas.tick()
        }
        assertEquals(CanvasResult.OK, result)
        assertEquals("b", device.canvas.appId)
    }

    @Test
    fun `timeout removes the element and closes the canvas`() {
        draw("""{"application_name":"t","elements":[{"id":"0","type":"text","text":"x","font":"normal","timeout":2}]}""")
        assertTrue(device.canvas.isOpen)
        now += 2000
        runBlocking(device.dispatcher) { device.canvas.tick() }
        assertTrue(!device.canvas.isOpen)
    }

    @Test
    fun `rectangle with border and solid fill`() {
        draw("""{"application_name":"t","elements":[{"id":"r","type":"rectangle","width":4,"height":3,"fill":"solid","fill_colors":["#FF0000FF"],"border_color":"#00FF00FF"}]}""")
        val fb = front()
        assertEquals(0xFF00FF00.toInt(), fb[0, 0])
        assertEquals(0xFFFF0000.toInt(), fb[1, 1])
        assertEquals(0xFF00FF00.toInt(), fb[3, 2])
    }

    @Test
    fun `xpm bitmap decodes and draws`() {
        val xpm = "! XPM2\\n2 1 2 1\\n. c #000000\\n# c #FFFFFF\\n#."
        assertEquals(200, draw("""{"application_name":"t","elements":[{"id":"x","type":"xpmbitmap","data":"$xpm"}]}""").first)
        val fb = front()
        assertEquals(0xFFFFFFFF.toInt(), fb[0, 0])
        assertEquals(Framebuffer.BLACK, fb[1, 0])
    }

    @Test
    fun `countdown formats like countdown_c`() {
        val c = Element.Countdown(timestamp = 1000 + 3725, color = -1, timeSince = false, alwaysHours = false)
        assertEquals("01:02:05", CanvasRenderer.countdownText(c, 1000))
        assertEquals("00:05", CanvasRenderer.countdownText(c.copy(timestamp = 1005), 1000))
        assertEquals("00:00:00", CanvasRenderer.countdownText(c.copy(alwaysHours = true), 99999))
    }
}
