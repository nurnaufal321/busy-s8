package com.busys8.engine.system

import com.busys8.engine.timer.AppConfig
import com.busys8.engine.timer.BusyTimer
import com.busys8.engine.timer.FileTimerStore
import com.busys8.engine.timer.ProfileSlot
import com.busys8.engine.timer.SnapshotState
import com.busys8.engine.timer.TimerAppHooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SensorsTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_761_582_532_251L
    private val hooks = object : TimerAppHooks {
        override fun showTimer(app: AppConfig) {}
        override fun exitTimer() {}
    }

    @Test
    fun `auto brightness follows the firmware curve`() {
        assertEquals(1, AutoBrightness.frontPercent(0))
        assertEquals(100, AutoBrightness.frontPercent(15))
        assertEquals(48, AutoBrightness.frontPercent(8))
        val auto = AutoBrightness()
        repeat(5) { auto.add(10_000f) }
        assertEquals(15, auto.level)
        auto.add(1f) // one dark sample barely moves the 5-sample average
        assertEquals(14, auto.level)
        repeat(5) { auto.add(100f) } // 100 lux = half the log range
        assertEquals(7, auto.level)
        repeat(5) { auto.add(0f) }
        assertEquals(0, auto.level)
    }

    @Test
    fun `presence goes away after the timeout and wakes on a face`() {
        val p = Presence { now }
        p.timeoutMs = 60_000
        assertFalse(p.away)
        p.enabled = true
        now += 61_000
        assertTrue(p.away)
        p.seen()
        assertFalse(p.away)
        p.enabled = false
        now += 120_000
        assertFalse(p.away)
    }

    private fun talk(call: AutoOnCall, ms: Long, active: Boolean) {
        val end = now + ms
        while (now < end) {
            now += 100
            call.voice(active)
        }
    }

    @Test
    fun `auto on call starts on speech and releases after silence`() {
        val timer = BusyTimer({ now }, FileTimerStore(tmp.root), hooks)
        val call = AutoOnCall(timer) { now }
        talk(call, 10_000, true) // disabled: nothing happens
        assertEquals(SnapshotState.NotStarted, timer.lastKnownSnapshot.state)

        call.enabled = true
        talk(call, 2_000, true) // too short
        assertNull(call.started)
        talk(call, 5_000, true)
        val state = timer.lastKnownSnapshot.state
        assertTrue(state is SnapshotState.Infinite)
        assertEquals("on_call", timer.lastKnownSnapshot.app.theme)
        assertEquals(timer.profile(ProfileSlot.CUSTOM).cardId, timer.lastKnownSnapshot.cardId)

        talk(call, 50_000, false)
        talk(call, 1_000, true) // a word resets the silence
        talk(call, 59_000, false)
        assertTrue(timer.lastKnownSnapshot.state is SnapshotState.Infinite)
        talk(call, 2_000, false)
        assertEquals(SnapshotState.NotStarted, timer.lastKnownSnapshot.state)
    }

    @Test
    fun `auto on call leaves sessions it did not start`() {
        val timer = BusyTimer({ now }, FileTimerStore(tmp.root), hooks)
        val call = AutoOnCall(timer) { now }
        call.enabled = true
        timer.start(ProfileSlot.BUSY)
        talk(call, 10_000, true)
        assertNull(call.started)
        assertEquals("busy", timer.lastKnownSnapshot.app.theme.take(4))

        // It started one, then the user switched to their own session: hands off.
        timer.stop()
        talk(call, 10_000, true)
        assertTrue(call.started != null)
        timer.stop()
        timer.start(ProfileSlot.BUSY)
        talk(call, 70_000, false)
        assertTrue(timer.lastKnownSnapshot.state !is SnapshotState.NotStarted)
    }
}

class VadTest {
    private val rnd = java.util.Random(1)

    /** [seconds] of signal at 16 kHz: [noise] amplitude of white noise plus an optional voiced sound. */
    private fun signal(seconds: Double, noise: Double, voice: Double = 0.0, pitch: Double = 140.0): ShortArray {
        val n = (seconds * Vad.SAMPLE_RATE).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / Vad.SAMPLE_RATE
            // A crude vowel: a pitch with harmonics, amplitude-modulated at a syllable rate.
            val syllable = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * 4 * t)
            val v = (1..12).sumOf { h -> kotlin.math.sin(2 * Math.PI * pitch * h * t) / h } * voice * syllable
            ((rnd.nextGaussian() * noise + v) * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun activeFraction(vad: Vad, pcm: ShortArray): Double {
        var active = 0
        var frames = 0
        var i = 0
        while (i + Vad.FRAME_SAMPLES <= pcm.size) {
            if (vad.frame(pcm, i)) active++
            frames++
            i += Vad.FRAME_SAMPLES
        }
        return active.toDouble() / frames
    }

    @Test
    fun `room noise is quiet and speech over it is not`() {
        val vad = Vad()
        assertTrue(activeFraction(vad, signal(3.0, noise = 0.003)) < 0.05)
        assertTrue(activeFraction(vad, signal(3.0, noise = 0.003, voice = 0.1)) > 0.6)
        // Back to room noise: the hangover ends and it goes quiet again.
        assertTrue(activeFraction(vad, signal(3.0, noise = 0.003)) < 0.1)
    }

    @Test
    fun `steady loud noise becomes the new floor`() {
        val vad = Vad()
        activeFraction(vad, signal(2.0, noise = 0.003))
        // A fan switching on: loud at first, but the floor rises to meet it within a minute.
        activeFraction(vad, signal(60.0, noise = 0.03))
        assertTrue(activeFraction(vad, signal(3.0, noise = 0.03)) < 0.1)
    }
}
