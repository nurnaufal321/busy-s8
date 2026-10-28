package com.busys8.engine.system

import com.busys8.engine.timer.BusyTimer
import com.busys8.engine.timer.ProfileSlot
import com.busys8.engine.timer.Snapshot
import com.busys8.engine.timer.SnapshotState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Automatic brightness as the firmware does it: services/light_sensor (a 5-sample moving
 * average of lux, mapped logarithmically from 1–10 000 lux to levels 0–15) and
 * services/brightness_control (level → front display brightness on an ease-in curve, 1–100).
 * Feed it one sample a second, like LIGHT_SENSOR_SAMPLE_INTERVAL_MS.
 */
class AutoBrightness {
    private val window = FloatArray(WINDOW_SIZE) { LUX_MIN }
    private var index = 0

    /** Light level 0–15 (LightSensorLevel). */
    var level = 0
        private set

    /** Front display brightness 1–100 for the current level. */
    val percent get() = frontPercent(level)

    /** Adds a lux sample; returns the new brightness percent. */
    fun add(lux: Float): Int {
        window[index] = lux.coerceIn(LUX_MIN, LUX_MAX)
        index = (index + 1) % WINDOW_SIZE
        val mean = window.average().toFloat()
        val a = LEVEL_MAX / (ln(LUX_MAX) - ln(LUX_MIN))
        val real = a * ln(mean) - a * ln(LUX_MIN)
        if (abs(real - level) > THRESHOLD) level = floor(real).toInt().coerceIn(0, LEVEL_MAX.toInt())
        return percent
    }

    companion object {
        private const val WINDOW_SIZE = 5
        private const val LUX_MIN = 1f
        private const val LUX_MAX = 10_000f
        private const val LEVEL_MAX = 15f
        private const val THRESHOLD = (1f / LEVEL_MAX) / 2f

        /** brightness_conv_internal_to_front: min 1, max 100, power 0.5. */
        fun frontPercent(level: Int): Int {
            val v = level / LEVEL_MAX
            val power = 0.5f
            val eased = (exp(power * v) - 1f) / (exp(power) - 1f)
            return (1 + 99 * eased).roundToInt()
        }
    }
}

/**
 * Presence from the front camera: the panel goes dark once nobody has been seen (or has
 * touched the phone) for [timeoutMs], and wakes the moment a face shows up again.
 */
class Presence(private val clock: () -> Long) {
    @Volatile var enabled = false
        set(value) {
            if (value && !field) lastSeenMs = clock()
            field = value
        }
    @Volatile var timeoutMs = 5 * 60_000L
    @Volatile private var lastSeenMs = clock()

    /** A face was detected, or someone touched the phone. */
    fun seen() {
        lastSeenMs = clock()
    }

    val away get() = enabled && clock() - lastSeenMs > timeoutMs
}

/**
 * Auto ON CALL: when someone keeps talking near the bar, start the CUSTOM profile with the
 * `on_call` theme as an endless session; after a stretch of silence, end it again, but only if
 * it is still the session this started (like busy-defaults' release). A session the user
 * started is never touched.
 *
 * Feed [voice] a voice-activity decision per audio frame; call it on the device thread.
 */
class AutoOnCall(private val timer: BusyTimer, private val clock: () -> Long) {
    var enabled = false
        set(value) {
            field = value
            if (!value) release()
            frames.clear()
        }

    /** The session this started, while it may still be running. */
    var started: Snapshot? = null
        private set

    private val frames = ArrayDeque<Pair<Long, Boolean>>()
    private var lastVoiceMs = 0L

    fun voice(active: Boolean) {
        if (!enabled) return
        val now = clock()
        frames.addLast(now to active)
        while (frames.isNotEmpty() && now - frames.first().first > START_WINDOW_MS) frames.removeFirst()
        if (active) lastVoiceMs = now

        val ours = started
        if (ours != null) {
            if (!isOurs(ours)) {
                started = null
            } else if (now - lastVoiceMs > RELEASE_AFTER_MS) {
                release()
            }
            return
        }
        val span = now - frames.first().first
        val talking = span >= START_WINDOW_MS - 1000 && frames.count { it.second } >= frames.size * START_RATIO
        if (talking && timer.lastKnownSnapshot.state == SnapshotState.NotStarted) start(now)
    }

    private fun start(now: Long) {
        val profile = timer.profile(ProfileSlot.CUSTOM)
        val snapshot = Snapshot(SnapshotState.Infinite(profile.cardId, paused = false), profile.app.copy(theme = THEME), now)
        timer.setSnapshot(snapshot)
        started = snapshot
        lastVoiceMs = now
    }

    private fun release() {
        val ours = started ?: return
        started = null
        if (isOurs(ours)) timer.setSnapshot(Snapshot(SnapshotState.NotStarted, timer.lastKnownSnapshot.app, clock()))
    }

    private fun isOurs(ours: Snapshot): Boolean {
        val current = timer.lastKnownSnapshot
        return current.state is SnapshotState.Infinite && current.cardId == ours.cardId && current.app.theme == THEME
    }

    companion object {
        const val THEME = "on_call"

        /** Voice in at least [START_RATIO] of the frames over this window starts a call. */
        const val START_WINDOW_MS = 6_000L
        const val START_RATIO = 0.4

        /** Silence this long ends it. */
        const val RELEASE_AFTER_MS = 60_000L
    }
}
