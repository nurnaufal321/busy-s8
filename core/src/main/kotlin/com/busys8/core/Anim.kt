package com.busys8.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A decoded animation: the firmware's `.anim` source, a zip of numbered PNG frames plus
 * `meta.json` (`fps`, named `sections` as inclusive frame ranges). `scripts/seq2anim.py`
 * turns these into `.anim` for the device; we play the frames directly.
 */
class AnimFile(val frames: List<RawImage>, val fps: Int, val sections: Map<String, IntRange>) {
    val width get() = frames.firstOrNull()?.width ?: 0
    val height get() = frames.firstOrNull()?.height ?: 0

    fun section(name: String): IntRange? = if (name == DEFAULT_SECTION) frames.indices else sections[name]

    companion object {
        const val DEFAULT_SECTION = "default"
        private val DIGITS = Regex("\\d+")

        fun parseZip(bytes: ByteArray, decoder: ImageDecoder): AnimFile? {
            val pngs = ArrayList<Pair<Int, ByteArray>>()
            var meta: JsonObject? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name.contains("__MACOSX")) continue
                    val base = name.substringAfterLast('/')
                    when {
                        base == "meta.json" -> meta = runCatching { Json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject }.getOrNull()
                        base.endsWith(".png", ignoreCase = true) -> {
                            val number = DIGITS.findAll(base).lastOrNull()?.value?.toIntOrNull() ?: 0
                            pngs += number to zip.readBytes()
                        }
                    }
                }
            }
            if (pngs.isEmpty()) return null
            val frames = pngs.sortedBy { it.first }.map { decoder.decode(it.second) ?: return null }
            val fps = meta?.get("fps")?.jsonPrimitive?.int ?: 60
            val sections = meta?.get("sections")?.jsonArray?.associate {
                val s = it.jsonObject
                s.getValue("name").jsonPrimitive.content to (s.getValue("start").jsonPrimitive.int..s.getValue("end").jsonPrimitive.int)
            }.orEmpty()
            return AnimFile(frames, fps, sections)
        }
    }
}

/**
 * Loads and caches animations by device path (e.g. `/ext/apps_assets/busy/animations/x.anim`):
 * bundled frame zips, or binary `.anim` files such as the ones JS apps ship ([AnimBin]).
 */
class AnimLoader(private val storage: DeviceStorage, private val decoder: ImageDecoder) {
    private val cache = object : LinkedHashMap<String, AnimFile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnimFile>) = size > 48
    }

    @Synchronized
    fun load(path: String): AnimFile? =
        cache[path] ?: storage.read(path)?.let { if (AnimBin.isAnim(it)) AnimBin.parse(it) else AnimFile.parseZip(it, decoder) }?.also { cache[path] = it }
}

/**
 * Time-based playback of one animation (gui/modules/anim_player.c). A fresh player loops the
 * whole file; [play] switches to a named section, once or looped, optionally after the current
 * pass finishes (AnimFilePlayFlagFinishCurrent).
 */
class AnimPlayer(val file: AnimFile, nowMs: Long) {
    private var range = file.frames.indices
    private var loop = true
    private var startMs = nowMs
    /** A section waiting for the current pass to end: range, loop flag, switch time. */
    private var queued: Triple<IntRange, Boolean, Long>? = null
    private var pausedAtMs: Long? = null

    /** Requests [section]; returns false for an unknown section name. */
    fun play(section: String, loop: Boolean, afterCurrent: Boolean, nowMs: Long): Boolean {
        val r = file.section(section) ?: return false
        if (afterCurrent && !isFinished(nowMs)) {
            val passMs = maxOf(1L, range.count() * 1000L / file.fps)
            val passes = if (this.loop) (nowMs - startMs) / passMs + 1 else 1
            queued = Triple(r, loop, startMs + passes * passMs)
            return true
        }
        range = r
        this.loop = loop
        startMs = nowMs
        queued = null
        return true
    }

    fun isFinished(nowMs: Long) = !loop && elapsedFrames(nowMs) >= range.count()

    fun pause(nowMs: Long) {
        if (pausedAtMs == null) pausedAtMs = nowMs
    }

    fun resume(nowMs: Long) {
        pausedAtMs?.let { startMs += nowMs - it }
        pausedAtMs = null
    }

    fun frame(nowMs: Long): RawImage {
        queued?.let { (r, l, switchAtMs) ->
            if ((pausedAtMs ?: nowMs) >= switchAtMs) {
                range = r
                loop = l
                startMs = switchAtMs
                queued = null
            }
        }
        val n = range.count()
        val i = elapsedFrames(nowMs)
        val idx = if (loop) (i % n).toInt() else minOf(i, n - 1L).toInt()
        return file.frames[range.first + idx]
    }

    private fun elapsedFrames(nowMs: Long): Long = maxOf(0, ((pausedAtMs ?: nowMs) - startMs) * file.fps / 1000)
}
