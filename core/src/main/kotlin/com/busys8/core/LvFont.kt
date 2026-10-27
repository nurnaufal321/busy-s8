package com.busys8.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A font in lv_font_conv's binary format (`--format bin`), as the firmware loads it with
 * lv_binfont_create(). Only uncompressed fonts are supported, which is all the firmware ships.
 * Metrics follow LVGL: line height = ascent - descent, baseline = -descent.
 */
class LvFont private constructor(
    val lineHeight: Int,
    val baseLine: Int,
    private val glyphs: Map<Int, Glyph>,
) {
    class Glyph(
        val advance: Int,
        val ofsX: Int,
        val ofsY: Int,
        val width: Int,
        val height: Int,
        /** Row-major coverage, 0..255 per pixel. */
        val alpha: ByteArray,
    )

    fun glyph(codepoint: Int): Glyph? = glyphs[codepoint]

    fun advance(codepoint: Int) = glyphs[codepoint]?.advance ?: 0

    fun textWidth(text: String) = text.sumOf { advance(it.code) }

    companion object {
        fun parse(bytes: ByteArray): LvFont {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val tables = HashMap<String, Int>()
            var offset = 0
            while (offset + 8 <= bytes.size) {
                val size = buf.getInt(offset)
                require(size >= 8) { "corrupt font table at $offset" }
                tables[String(bytes, offset + 4, 4, Charsets.US_ASCII)] = offset
                offset += size
            }
            val head = tables["head"] ?: error("font has no head table")
            val cmap = tables["cmap"] ?: error("font has no cmap table")
            val loca = tables["loca"] ?: error("font has no loca table")
            val glyf = tables["glyf"] ?: error("font has no glyf table")

            val h = head + 8
            val ascent = buf.getShort(h + 8).toInt()
            val descent = buf.getShort(h + 10).toInt()
            val locaFormat = bytes[h + 26].toInt()
            val advanceFormat = bytes[h + 28].toInt()
            val bpp = bytes[h + 29].toInt()
            val xyBits = bytes[h + 30].toInt()
            val whBits = bytes[h + 31].toInt()
            val advBits = bytes[h + 32].toInt()
            val compression = bytes[h + 33].toInt()
            require(compression == 0) { "compressed fonts are not supported" }

            val locaCount = buf.getInt(loca + 8)
            fun glyphOffset(id: Int) =
                if (locaFormat == 0) buf.getShort(loca + 12 + id * 2).toInt() and 0xFFFF
                else buf.getInt(loca + 12 + id * 4)

            fun readGlyph(id: Int): Glyph {
                val bits = BitReader(bytes, glyf + glyphOffset(id))
                val rawAdv = if (advBits == 0) 0 else bits.unsigned(advBits)
                val advance = if (advanceFormat == 1) (rawAdv + 8) / 16 else rawAdv
                val ofsX = bits.signed(xyBits)
                val ofsY = bits.signed(xyBits)
                val w = bits.unsigned(whBits)
                val hgt = bits.unsigned(whBits)
                val maxVal = (1 shl bpp) - 1
                val alpha = ByteArray(w * hgt) { (bits.unsigned(bpp) * 255 / maxVal).toByte() }
                return Glyph(advance, ofsX, ofsY, w, hgt, alpha)
            }

            val glyphById = HashMap<Int, Glyph>()
            val byCodepoint = HashMap<Int, Glyph>()
            val subtables = buf.getInt(cmap + 8)
            for (i in 0 until subtables) {
                val s = cmap + 12 + i * 16
                val dataOffset = cmap + buf.getInt(s)
                val rangeStart = buf.getInt(s + 4)
                val rangeLength = buf.getShort(s + 8).toInt() and 0xFFFF
                val glyphIdStart = buf.getShort(s + 10).toInt() and 0xFFFF
                val entries = buf.getShort(s + 12).toInt() and 0xFFFF
                val pairs: List<Pair<Int, Int>> = when (bytes[s + 14].toInt()) {
                    0 -> (0 until rangeLength).map { k ->
                        rangeStart + k to glyphIdStart + (bytes[dataOffset + k].toInt() and 0xFF)
                    }
                    1 -> (0 until entries).map { k ->
                        val cp = buf.getShort(dataOffset + k * 2).toInt() and 0xFFFF
                        val gid = buf.getShort(dataOffset + entries * 2 + k * 2).toInt() and 0xFFFF
                        rangeStart + cp to glyphIdStart + gid
                    }
                    2 -> (0 until rangeLength).map { k -> rangeStart + k to glyphIdStart + k }
                    3 -> (0 until entries).map { k ->
                        rangeStart + (buf.getShort(dataOffset + k * 2).toInt() and 0xFFFF) to glyphIdStart + k
                    }
                    else -> error("unknown cmap format")
                }
                for ((cp, gid) in pairs) {
                    if (gid in 1 until locaCount) byCodepoint[cp] = glyphById.getOrPut(gid) { readGlyph(gid) }
                }
            }
            return LvFont(ascent - descent, -descent, byCodepoint)
        }
    }

    private class BitReader(private val data: ByteArray, byteOffset: Int) {
        private var bit = byteOffset * 8

        fun unsigned(n: Int): Int {
            var v = 0
            repeat(n) {
                v = (v shl 1) or ((data[bit ushr 3].toInt() shr (7 - (bit and 7))) and 1)
                bit++
            }
            return v
        }

        fun signed(n: Int): Int {
            val v = unsigned(n)
            return if (n > 0 && v and (1 shl (n - 1)) != 0) v - (1 shl n) else v
        }
    }
}

/** The text fonts of the draw API, mapped to files as in api_display.c. */
enum class ApiFont(val apiName: String, val file: String) {
    TINY("tiny", "busy_tiny"),
    SMALL("small", "busy_regular_5"),
    NORMAL("normal", "busy_regular_7"),
    CONDENSED("condensed", "busy_condensed_7"),
    BOLD("bold", "busy_bold_7"),
    LARGE("large", "busy_regular_9"),
    EXTRA_LARGE("extra_large", "busy_bold_10"),
    GLOBAL("global", "lana_pixel_regular_11"),
    SUPERSCRIPT("superscript", "busy_superscript_7");

    companion object {
        fun fromApi(name: String) = entries.firstOrNull { it.apiName == name }
    }
}

/** Loads fonts by firmware name (e.g. `busy_regular_7`) and keeps them for the process lifetime. */
class FontRegistry(private val assets: AssetSource) {
    private val cache = HashMap<String, LvFont>()

    @Synchronized
    fun get(name: String): LvFont = cache.getOrPut(name) {
        val bytes = assets.open("apps_assets/shared/fonts/$name.font")?.use { it.readBytes() } ?: error("missing font $name")
        LvFont.parse(bytes)
    }
}
