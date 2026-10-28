package com.busys8.engine.system

import kotlin.math.log10

/**
 * A small voice activity detector for 16 kHz mono PCM, in 20 ms frames: energy above an
 * adaptive noise floor, with a zero-crossing check that rejects hum and hiss, and a short
 * hangover so the gaps between words count as speech. It only answers "is someone talking",
 * which is all auto ON CALL needs; audio is never stored.
 */
class Vad {
    private var floorDb = -70.0
    private var prev = 0f
    private var hangover = 0

    /** Energy of the last frame, dBFS, for diagnostics. */
    var lastDb = -100.0
        private set

    /** Classifies one frame of [FRAME_SAMPLES] samples. */
    fun frame(pcm: ShortArray, offset: Int = 0): Boolean {
        var energy = 0.0
        var crossings = 0
        var last = 0f
        for (i in offset until offset + FRAME_SAMPLES) {
            val x = pcm[i] / 32768f
            // Pre-emphasis: a first-order high-pass that removes DC and most mains hum.
            val y = x - 0.95f * prev
            prev = x
            energy += y * y
            if (i > offset && (y >= 0f) != (last >= 0f)) crossings++
            last = y
        }
        val db = 10 * log10(energy / FRAME_SAMPLES + 1e-12)
        lastDb = db
        val zcr = crossings.toDouble() / FRAME_SAMPLES

        // Noise floor: follows quiet frames down quickly and loud ones up slowly (~1 dB/s).
        floorDb = if (db < floorDb) floorDb + (db - floorDb) * 0.2 else floorDb + FLOOR_RISE_DB
        val speechLike = db > floorDb + MARGIN_DB && db > MIN_DB && zcr in ZCR_MIN..ZCR_MAX
        if (speechLike) hangover = HANGOVER_FRAMES else if (hangover > 0) hangover--
        return speechLike || hangover > 0
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
        private const val MARGIN_DB = 12.0
        private const val MIN_DB = -55.0
        private const val FLOOR_RISE_DB = 0.02
        private const val ZCR_MIN = 0.02
        private const val ZCR_MAX = 0.35
        private const val HANGOVER_FRAMES = 10
    }
}
