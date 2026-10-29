package com.busys8.engine.system

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The phone's always-on date and time under the bar's picture: a 72×[HEIGHT] strip of LEDs,
 * phone-only (the bar's 72×16 framebuffer and everything that reads it are untouched).
 *
 * HH:MM in 7×12 segment digits with 2-LED strokes and the corners left off, so they read
 * rounded, colon blinking; the weekday and date in a 4×5 font to the right. Every glyph is
 * placed on the grid by hand, so nothing is anti-aliased.
 */
object ClockStrip {
    const val WIDTH = 72
    const val HEIGHT = 13

    private const val TIME = 0xEBEBEB
    private const val DATE = 0x969696
    private const val MERIDIEM = 0x6E6E6E

    /** RGB per LED, row-major, 0 = off. */
    fun render(epochMs: Long, zone: String, clock12h: Boolean): IntArray {
        val out = IntArray(WIDTH * HEIGHT)
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC")))
        val hour = if (clock12h) (t.hour % 12).let { if (it == 0) 12 else it } else t.hour
        val hh = if (clock12h) hour.toString().padStart(2, ' ') else hour.toString().padStart(2, '0')
        val mm = t.minute.toString().padStart(2, '0')

        fun set(x: Int, y: Int, c: Int) {
            if (x in 0 until WIDTH && y in 0 until HEIGHT) out[y * WIDTH + x] = c
        }
        fun digit(ch: Char, x0: Int) {
            if (ch == ' ') return
            for (s in DIGITS[ch - '0']) {
                val (x1, y1, x2, y2) = SEGMENTS.getValue(s)
                for (y in y1 until y2) for (x in x1 until x2) set(x0 + x, y, TIME)
            }
        }
        fun text(s: String, x0: Int, y0: Int, c: Int) = drawText(out, WIDTH, HEIGHT, s, x0, y0, c)

        digit(hh[0], 1)
        digit(hh[1], 9)
        digit(mm[0], 22)
        digit(mm[1], 30)
        if (epochMs / 1000 % 2 == 0L) for (y in intArrayOf(3, 4, 7, 8)) for (x in 18..19) set(x, y, TIME)

        text(DAYS[t.dayOfWeek.value - 1], 42, 0, DATE)
        text(t.dayOfMonth.toString().padStart(2, '0') + " " + MONTHS[t.monthValue - 1], 42, 7, DATE)
        if (clock12h) text(if (t.hour < 12) "AM" else "PM", 61, 0, MERIDIEM)
        return out
    }

    /** Draws [s] in the 4×5 font into [out] (RGB per LED, [w]×[h], row-major), clipped. */
    private fun drawText(out: IntArray, w: Int, h: Int, s: String, x0: Int, y0: Int, color: Int) {
        var x = x0
        for (ch in s.uppercase()) {
            SMALL[ch]?.let { rows ->
                for (y in 0 until 5) for (dx in 0 until 4) {
                    val px = x + dx
                    val py = y0 + y
                    if (rows[y * 4 + dx] == '1' && px in 0 until w && py in 0 until h) out[py * w + px] = color
                }
            }
            x += 5
        }
    }

    /** Segment rectangles in a 7×12 cell: x1, y1, x2, y2 (exclusive). */
    private val SEGMENTS = mapOf(
        'a' to listOf(1, 0, 6, 2), 'b' to listOf(5, 1, 7, 6), 'c' to listOf(5, 6, 7, 11),
        'd' to listOf(1, 10, 6, 12), 'e' to listOf(0, 6, 2, 11), 'f' to listOf(0, 1, 2, 6),
        'g' to listOf(1, 5, 6, 7),
    )
    private val DIGITS = listOf("abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg")

    private val DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** 4×5 glyphs, rows top to bottom. */
    private val SMALL = mapOf(
        '0' to "01101001100110010110", '1' to "00100110001000100111", '2' to "11100001011010001111",
        '3' to "11100001011000011110", '4' to "10011001111100010001", '5' to "11111000111000011110",
        '6' to "01101000111010010110", '7' to "11110001001001000100", '8' to "01101001011010010110",
        '9' to "01101001011100010110", 'A' to "01101001111110011001", 'B' to "11101001111010011110",
        'C' to "01111000100010000111", 'D' to "11101001100110011110", 'E' to "11111000111010001111",
        'F' to "11111000111010001000", 'G' to "01111000101110010111", 'H' to "10011001111110011001",
        'I' to "01110010001000100111", 'J' to "00010001000110010110", 'L' to "10001000100010001111",
        'M' to "10011111111110011001", 'N' to "10011101101110011001", 'O' to "01101001100110010110",
        'P' to "11101001111010001000", 'R' to "11101001111010101001", 'S' to "01111000011000011110",
        'T' to "11110110011001100110", 'U' to "10011001100110010110", 'V' to "10011001100101100110",
        'W' to "10011001111111111001", 'Y' to "10011001011001100110", 'K' to "10011010110010101001",
    )
}
