package com.busys8.core

/** XPM2 decoder covering what the draw API documents for `xpmbitmap` elements. */
class Xpm(source: String) {
    class Header(val width: Int, val height: Int, val colors: Int, val cpp: Int)

    private val lines = source.split('\n').map { it.trimEnd('\r') }

    fun header(): Header? {
        if (lines.size < 2 || lines[0].trim() != "! XPM2") return null
        val parts = lines[1].trim().split(WS)
        if (parts.size < 4) return null
        val n = parts.take(4).map { it.toIntOrNull() ?: return null }
        if (n.any { it <= 0 }) return null
        return Header(n[0], n[1], n[2], n[3])
    }

    fun colors(h: Header): Map<String, Int>? {
        if (lines.size < 2 + h.colors) return null
        val palette = HashMap<String, Int>()
        for (i in 0 until h.colors) {
            val line = lines[2 + i]
            if (line.length < h.cpp) return null
            val tokens = line.substring(h.cpp).trim().split(WS).filter { it.isNotEmpty() }
            if (tokens.size < 2 || tokens.size % 2 != 0) return null
            val byKey = tokens.chunked(2).associate { it[0] to it[1] }
            val value = VISUALS.firstNotNullOfOrNull { byKey[it] } ?: return null
            palette[line.substring(0, h.cpp)] = parseColor(value) ?: return null
        }
        return palette
    }

    fun pixels(h: Header, palette: Map<String, Int>): RawImage? {
        val first = 2 + h.colors
        if (lines.size < first + h.height) return null
        val out = IntArray(h.width * h.height)
        for (y in 0 until h.height) {
            val row = lines[first + y]
            if (row.length < h.width * h.cpp) return null
            for (x in 0 until h.width) {
                out[y * h.width + x] = palette[row.substring(x * h.cpp, (x + 1) * h.cpp)] ?: return null
            }
        }
        return RawImage(h.width, h.height, out)
    }

    companion object {
        private val WS = Regex("\\s+")
        private val VISUALS = listOf("c", "g", "g4", "m")
        private val NAMED = mapOf(
            "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x00FF00,
            "blue" to 0x0000FF, "yellow" to 0xFFFF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF,
            "gray" to 0x808080, "grey" to 0x808080,
        )

        fun parseColor(v: String): Int? {
            val lower = v.lowercase()
            if (lower == "none") return 0
            NAMED[lower]?.let { return (0xFF shl 24) or it }
            if (!v.startsWith('#')) return null
            val hex = v.substring(1)
            if (hex.isEmpty() || hex.length % 3 != 0 || hex.length > 12 || hex.any { it.digitToIntOrNull(16) == null }) return null
            val digits = hex.length / 3
            val channels = (0 until 3).map { c ->
                val part = hex.substring(c * digits, (c + 1) * digits).toInt(16)
                val bits = digits * 4
                if (bits >= 8) part shr (bits - 8) else part * 0x11
            }
            return Argb.of(255, channels[0], channels[1], channels[2])
        }
    }
}
