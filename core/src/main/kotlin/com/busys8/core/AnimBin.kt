package com.busys8.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for the device's binary `.anim` ("bicycle1", documentation/File Formats.dox.md),
 * the format `scripts/seq2anim.py` writes and JS apps ship in `animations/`.
 *
 * Each frame carries a mask of the pixels that changed since the previous frame and only those
 * pixels' values; section starts are keyframes with every pixel set. All frames are decoded up
 * front into full images, which is cheap at 72×16 or 160×80.
 */
object AnimBin {
    private val SIGNATURE = "bicycle1".toByteArray()
    private const val HEADER_LEN = 34

    private const val COLOR_BGR888 = 0
    private const val COLOR_GRAY4 = 1
    private const val COLOR_BGRA8888 = 2

    fun isAnim(bytes: ByteArray) = bytes.size >= HEADER_LEN && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    fun parse(bytes: ByteArray): AnimFile? = runCatching { decode(bytes) }.getOrNull()

    private fun decode(bytes: ByteArray): AnimFile? {
        if (!isAnim(bytes)) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(8)
        b.get() // flags
        val width = b.get().toInt() and 0xFF
        val height = b.get().toInt() and 0xFF
        val colorMode = b.get().toInt() and 0xFF
        b.short; b.short // max mask / pixel lengths
        val fps = b.get().toInt() and 0xFF
        b.get()
        b.int; b.int // section and frame chunk lengths
        val sectionCount = b.int
        val frameCount = b.int
        if (width == 0 || height == 0 || frameCount <= 0 || colorMode > COLOR_BGRA8888) return null

        val sections = LinkedHashMap<String, IntRange>()
        repeat(sectionCount) {
            val start = b.int
            val end = b.int
            b.int // file offset of the start frame
            val name = StringBuilder()
            while (true) {
                val c = b.get().toInt() and 0xFF
                if (c == 0) break
                name.append(c.toChar())
            }
            if (name.toString() != AnimFile.DEFAULT_SECTION) sections[name.toString()] = start..end
        }

        val count = width * height
        val pixels = IntArray(count)
        val changed = BooleanArray(count)
        val frames = ArrayList<RawImage>(frameCount)
        repeat(frameCount) {
            val joint = b.get().toInt() and 0xFF
            val maskBits = b.short.toInt() and 0xFFFF
            val pixelLen = b.short.toInt() and 0xFFFF
            val maskBytes = ByteArray((maskBits + 7) / 8).also { b.get(it) }
            val pixelData = ByteArray(pixelLen).also { b.get(it) }
            val n = decodeMask(joint shr 4, maskBytes, maskBits, changed)
            val values = decodePixels(joint and 0x0F, colorMode, pixelData, n)
            var k = 0
            for (i in 0 until count) if (changed[i]) pixels[i] = values[k++]
            frames += RawImage(width, height, pixels.copyOf())
        }
        check(!b.hasRemaining())
        return AnimFile(frames, if (fps == 0) 60 else fps, sections)
    }

    /** Fills [out] with which pixels this frame sets; returns how many. */
    private fun decodeMask(encoding: Int, data: ByteArray, bits: Int, out: BooleanArray): Int {
        when (encoding) {
            0 -> { out.fill(false); return 0 }
            1 -> { out.fill(true); return out.size }
            4 -> {
                var n = 0
                for (i in out.indices) {
                    val set = i < bits && (data[i shr 3].toInt() shr (7 - (i and 7))) and 1 == 1
                    out[i] = set
                    if (set) n++
                }
                return n
            }
            2, 3 -> {
                // Alternating runs, starting with black (unchanged) or white; 3-bit lengths,
                // with 7 meaning an 8-bit length follows.
                var value = encoding == 3
                var pos = 0
                var bit = 0
                var n = 0
                fun read(count: Int): Int {
                    var v = 0
                    repeat(count) {
                        v = (v shl 1) or ((data[bit shr 3].toInt() shr (7 - (bit and 7))) and 1)
                        bit++
                    }
                    return v
                }
                while (bit + 3 <= bits && pos < out.size) {
                    var len = read(3)
                    if (len == 7) len = read(8)
                    repeat(len) {
                        if (pos < out.size) {
                            out[pos++] = value
                            if (value) n++
                        }
                    }
                    value = !value
                }
                while (pos < out.size) out[pos++] = false
                return n
            }
            else -> error("mask encoding $encoding")
        }
    }

    private fun decodePixels(encoding: Int, colorMode: Int, data: ByteArray, count: Int): IntArray = when (encoding) {
        0 -> unpack(colorMode, data.also { check(it.size == packedSize(colorMode, count)) }, count)
        1 -> unpack(colorMode, rle(data, if (colorMode == COLOR_BGR888) 3 else if (colorMode == COLOR_GRAY4) 1 else 4).also { check(it.size == packedSize(colorMode, count)) }, count)
        2 -> qoi(data, count)
        else -> error("pixel encoding $encoding")
    }

    private fun packedSize(colorMode: Int, count: Int) = when (colorMode) {
        COLOR_BGR888 -> count * 3
        COLOR_BGRA8888 -> count * 4
        else -> (count + 1) / 2
    }

    private fun unpack(colorMode: Int, data: ByteArray, count: Int): IntArray {
        val out = IntArray(count)
        fun u(i: Int) = data[i].toInt() and 0xFF
        for (i in 0 until count) {
            out[i] = when (colorMode) {
                COLOR_BGR888 -> Argb.of(0xFF, u(i * 3 + 2), u(i * 3 + 1), u(i * 3))
                COLOR_BGRA8888 -> Argb.of(u(i * 4 + 3), u(i * 4 + 2), u(i * 4 + 1), u(i * 4))
                else -> {
                    val nibble = (u(i / 2) shr if (i % 2 == 0) 4 else 0) and 0x0F
                    val g = nibble * 0x11
                    Argb.of(0xFF, g, g, g)
                }
            }
        }
        return out
    }

    /** lib/toolbox/rle_encode: bit 7 set = that many verbatim blocks, else one block repeated. */
    private fun rle(data: ByteArray, block: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size * 2)
        var i = 0
        while (i < data.size) {
            val op = data[i++].toInt() and 0xFF
            val n = op and 0x7F
            if (op and 0x80 != 0) {
                out.write(data, i, minOf(n * block, data.size - i))
                i += n * block
            } else {
                repeat(n) { out.write(data, i, minOf(block, data.size - i)) }
                i += block
            }
        }
        return out.toByteArray()
    }

    /** The headerless QOI variant from seq2anim.py, over RGBA. */
    private fun qoi(data: ByteArray, count: Int): IntArray {
        val out = IntArray(count)
        val lut = IntArray(64 * 4)
        var r = 0; var g = 0; var b = 0; var a = 255
        var i = 0
        var o = 0
        fun remember() {
            val h = ((r * 3 + g * 5 + b * 7 + a * 11) % 64) * 4
            lut[h] = r; lut[h + 1] = g; lut[h + 2] = b; lut[h + 3] = a
        }
        fun u() = data[i++].toInt() and 0xFF
        while (o < count && i < data.size) {
            val op = u()
            when {
                op == 0xFE -> { r = u(); g = u(); b = u(); remember() }
                op == 0xFF -> { r = u(); g = u(); b = u(); a = u(); remember() }
                op shr 6 == 0 -> {
                    val h = (op and 0x3F) * 4
                    r = lut[h]; g = lut[h + 1]; b = lut[h + 2]; a = lut[h + 3]
                }
                op shr 6 == 1 -> {
                    r = (r + ((op shr 4) and 3) - 2) and 0xFF
                    g = (g + ((op shr 2) and 3) - 2) and 0xFF
                    b = (b + (op and 3) - 2) and 0xFF
                    remember()
                }
                op shr 6 == 2 -> {
                    val dg = (op and 0x3F) - 32
                    val next = u()
                    r = (r + dg + (next shr 4) - 8) and 0xFF
                    g = (g + dg) and 0xFF
                    b = (b + dg + (next and 0x0F) - 8) and 0xFF
                    remember()
                }
                else -> {
                    val run = (op and 0x3F) + 1
                    remember()
                    repeat(run - 1) { if (o < count) out[o++] = Argb.of(a, r, g, b) }
                }
            }
            if (o < count) out[o++] = Argb.of(a, r, g, b)
        }
        check(o == count && i == data.size)
        return out
    }
}
