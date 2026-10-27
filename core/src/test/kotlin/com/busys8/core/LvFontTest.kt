package com.busys8.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LvFontTest {
    private val fonts = FontRegistry(vendorAssets)

    @Test
    fun `every API font loads and covers letters, digits and punctuation`() {
        // Not every upstream font has all of ASCII (busy_regular_5 has no backtick), so check the common set.
        val required = ('A'..'Z') + ('a'..'z') + ('0'..'9') + " .,:;!?-+%/()".toList()
        for (f in ApiFont.entries) {
            val font = fonts.get(f.file)
            for (c in required) assertNotNull("${f.apiName} lacks '$c'", font.glyph(c.code))
        }
    }

    @Test
    fun `regular 7 metrics match the firmware header`() {
        val font = fonts.get("busy_regular_7")
        assertEquals(11, font.lineHeight)
        assertEquals(2, font.baseLine)
        val a = font.glyph('A'.code)!!
        assertEquals(6, a.advance)
        assertEquals(5, a.width)
        assertEquals(7, a.height)
    }

    @Test
    fun `glyph bitmap decodes`() {
        val a = fonts.get("busy_regular_7").glyph('A'.code)!!
        val rows = (0 until a.height).map { r ->
            (0 until a.width).joinToString("") { c -> if (a.alpha[r * a.width + c] != 0.toByte()) "#" else "." }
        }
        assertEquals(listOf("..#..", "..#..", ".#.#.", ".#.#.", ".###.", "#...#", "#...#"), rows)
    }
}
