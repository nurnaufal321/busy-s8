package com.busys8.engine.system

import java.awt.image.BufferedImage
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.imageio.ImageIO
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class ClockStripTest {
    private val previews = File("build/previews").apply { mkdirs() }

    // Thu 29 Oct 2026, 18:56:00 in Kuala Lumpur (UTC+8), an even second.
    private val at = ZonedDateTime.of(2026, 10, 29, 18, 56, 0, 0, ZoneId.of("Asia/Kuala_Lumpur")).toInstant().toEpochMilli()

    private fun lit(s: IntArray, x: Int, y: Int) = s[y * ClockStrip.WIDTH + x] != 0

    @Test
    fun colonBlinksEachSecond() {
        val on = ClockStrip.render(at, "Asia/Kuala_Lumpur", false)
        val off = ClockStrip.render(at + 1000, "Asia/Kuala_Lumpur", false)
        assertTrue(lit(on, 18, 3))
        assertTrue(!lit(off, 18, 3))
        // Only the colon differs.
        assertEquals(8, on.indices.count { on[it] != off[it] })
    }

    @Test
    fun followsTimeZoneAndFormat() {
        val kl = ClockStrip.render(at, "Asia/Kuala_Lumpur", false)
        val utc = ClockStrip.render(at, "UTC", false)
        assertNotEquals(kl.toList(), utc.toList())
        // 12 h: 6:56 has no leading digit; 24 h draws a 1 in the first cell.
        val h12 = ClockStrip.render(at, "Asia/Kuala_Lumpur", true)
        assertTrue((0 until 12).none { y -> (1..7).any { x -> lit(h12, x, y) } })
        assertTrue((0 until 12).any { y -> (1..7).any { x -> lit(kl, x, y) } })
    }

    @Test
    fun previews() {
        snap("clock-24h", ClockStrip.render(at, "Asia/Kuala_Lumpur", false))
        snap("clock-24h-colon-off", ClockStrip.render(at + 1000, "Asia/Kuala_Lumpur", false))
        snap("clock-12h", ClockStrip.render(at, "Asia/Kuala_Lumpur", true))
    }

    private fun snap(name: String, s: IntArray) {
        val scale = 10
        val img = BufferedImage(ClockStrip.WIDTH * scale, ClockStrip.HEIGHT * scale, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, s[(y / scale) * ClockStrip.WIDTH + x / scale])
        ImageIO.write(img, "png", File(previews, "$name.png"))
    }
}
