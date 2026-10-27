package com.busys8.engine.timer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BusyTimerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_761_582_532_251L
    private val events = ArrayList<TimerEvent>()
    private val shown = ArrayList<String>()
    private val hooks = object : TimerAppHooks {
        override fun showTimer(app: AppConfig) { shown += "show:${app.theme}" }
        override fun exitTimer() { shown += "exit" }
    }
    private val store by lazy { FileTimerStore(tmp.root) }

    private fun timer() = BusyTimer({ now }, store, hooks).also { t -> t.subscribe { events += it } }

    private fun advance(ms: Long, t: BusyTimer) {
        // The poll timer fires ~30 times a second; stepping by 100 ms is equivalent.
        repeat((ms / 100).toInt()) {
            now += 100
            t.poll()
        }
    }

    @Test
    fun `fresh device reports the zeroed snapshot`() {
        assertEquals(
            """{"snapshot":{"type":"NOT_STARTED","busy_bar_settings":{"theme":"","show_work_phase_only":false,"trigger_smart_home":false}},"snapshot_timestamp_ms":0}""",
            TimerJson.snapshot(timer().lastKnownSnapshot).toString(),
        )
    }

    @Test
    fun `default profiles match the firmware`() {
        val t = timer()
        assertEquals(
            """{"sort_order":-1,"title":"BUSY","id":"00000000-0000-0000-0000-000000000000","timer_settings":{"type":"INTERVAL","interval_work_ms":1200000,"interval_rest_ms":300000,"interval_work_cycles_count":3,"is_autostart_enabled":false},"busy_bar_settings":{"theme":"busy","show_work_phase_only":false,"trigger_smart_home":true},"profile_timestamp_ms":0}""",
            TimerJson.profile(t.profile(ProfileSlot.BUSY)).toString(),
        )
        assertEquals(TimerMode.INFINITE, t.profile(ProfileSlot.CUSTOM).timer.mode)
        assertEquals("keep_out", t.profile(ProfileSlot.CUSTOM).app.theme)
    }

    @Test
    fun `full default interval session without autostart`() {
        val t = timer()
        t.start(ProfileSlot.BUSY)
        assertEquals(TimerState.WORK, t.state)
        assertTrue(t.isRunning)
        val start = t.lastKnownSnapshot.state as SnapshotState.Interval
        assertEquals(0L, start.index)
        assertEquals(1_200_000L, start.leftMs)

        // Work 1 ends: the timer moves to REST but waits (no autostart).
        advance(20 * 60_000L + 1000, t)
        assertEquals(TimerState.REST, t.state)
        assertFalse(t.isRunning)
        assertTrue(events.any { it is TimerEvent.IntervalEnded && !it.forced })

        // START resumes; the 2 remaining work and 1 more rest interval follow the same way.
        for (expected in listOf(TimerState.WORK, TimerState.REST, TimerState.WORK)) {
            t.start(ProfileSlot.BUSY)
            assertTrue(t.isRunning)
            advance(20 * 60_000L + 1000, t)
            assertEquals(expected, t.state)
        }
        t.start(ProfileSlot.BUSY)
        advance(20 * 60_000L + 1000, t)
        assertEquals(TimerState.IDLE, t.state)
        // The index moves past the last interval on the way to IDLE: (5 + 1) / 2 = 3 cycles done.
        assertEquals(5L, t.runInfo().intervalIndex)
        assertEquals(TimerEvent.StateChanged(TimerState.IDLE), events.filterIsInstance<TimerEvent.StateChanged>().last())
    }

    @Test
    fun `pause, skip and add time`() {
        val t = timer()
        t.start(ProfileSlot.BUSY)
        advance(90_000, t)
        t.toggle()
        assertTrue(t.lastKnownSnapshot.isPaused)
        t.skip() // ignored while paused
        assertEquals(TimerState.WORK, t.state)
        t.toggle()
        // 18:30 left; +5 rounds up to 20:00, -5 rounds down to 15:00.
        t.addTime(5)
        assertEquals(1_200_000L, (t.lastKnownSnapshot.state as SnapshotState.Interval).leftMs)
        t.addTime(-5)
        assertEquals(900_000L, (t.lastKnownSnapshot.state as SnapshotState.Interval).leftMs)
        t.skip()
        assertEquals(TimerState.REST, t.state)
        assertTrue(t.isRunning) // forced transitions start immediately
    }

    @Test
    fun `infinite timer turns simple when time is added`() {
        val t = timer()
        t.start(ProfileSlot.CUSTOM)
        assertTrue(t.lastKnownSnapshot.state is SnapshotState.Infinite)
        t.addTime(5)
        assertEquals(300_000L, (t.lastKnownSnapshot.state as SnapshotState.Simple).timeLeftMs)
    }

    @Test
    fun `snapshot round trip and stale snapshots are ignored`() {
        val t = timer()
        val json = """{"snapshot":{"type":"SIMPLE","card_id":"12345678-1234-1234-1234-123456789abc","is_paused":false,"time_left_ms":240000,"busy_bar_settings":{"theme":"on_air","show_work_phase_only":false,"trigger_smart_home":true}},"snapshot_timestamp_ms":${now - 1000}}"""
        val s = TimerJson.parseSnapshot(Json.parseToJsonElement(json), now)
        assertNotNull(s)
        t.setSnapshot(s!!)
        assertEquals(json, TimerJson.snapshot(t.lastKnownSnapshot).toString())
        assertEquals(listOf("show:on_air"), shown)
        // Time since the snapshot's own timestamp (1 s old, plus 1 s now) is applied on the next poll.
        now += 1000
        t.poll()
        assertEquals(TimerEvent.Tick(2, 238), events.filterIsInstance<TimerEvent.Tick>().last())

        val stale = s.copy(timestampMs = s.timestampMs - 1)
        t.setSnapshot(stale)
        assertEquals(s.timestampMs, t.lastKnownSnapshot.timestampMs)
    }

    @Test
    fun `invalid snapshots are rejected`() {
        fun parse(body: String) = TimerJson.parseSnapshot(Json.parseToJsonElement(body), now)
        val settings = """"busy_bar_settings":{"theme":"busy","show_work_phase_only":false,"trigger_smart_home":true}"""
        // More than a minute in the future.
        assertNull(parse("""{"snapshot":{"type":"NOT_STARTED",$settings},"snapshot_timestamp_ms":${now + 61_000}}"""))
        // Simple timer longer than 24 h.
        assertNull(parse("""{"snapshot":{"type":"SIMPLE","card_id":"00000000-0000-0000-0000-000000000000","is_paused":false,"time_left_ms":86400001,$settings},"snapshot_timestamp_ms":$now}"""))
        // Interval with 1 cycle.
        assertNull(parse("""{"snapshot":{"type":"INTERVAL","card_id":"00000000-0000-0000-0000-000000000000","is_paused":false,"current_interval":0,"current_interval_time_total_ms":300000,"current_interval_time_left_ms":300000,"interval_settings":{"type":"INTERVAL","interval_work_ms":300000,"interval_rest_ms":300000,"interval_work_cycles_count":1,"is_autostart_enabled":false},$settings},"snapshot_timestamp_ms":$now}"""))
        // Bad card id.
        assertNull(parse("""{"snapshot":{"type":"INFINITE","card_id":"not-a-uuid-not-a-uuid-not-a-uuid-xx","is_paused":false,$settings},"snapshot_timestamp_ms":$now}"""))
    }

    @Test
    fun `profiles only move forward in time`() {
        val t = timer()
        val p = Profile.DEFAULT_BUSY.copy(title = "study", timestampMs = 100)
        t.setProfile(ProfileSlot.BUSY, p)
        assertEquals("study", t.profile(ProfileSlot.BUSY).title)
        t.setProfile(ProfileSlot.BUSY, p.copy(title = "older", timestampMs = 50))
        assertEquals("study", t.profile(ProfileSlot.BUSY).title)
        t.setProfile(ProfileSlot.BUSY, p.copy(title = "future", timestampMs = now + 999_999))
        assertEquals(now, t.profile(ProfileSlot.BUSY).timestampMs)
    }

    @Test
    fun `a running timer survives a restart`() {
        val t = timer()
        t.start(ProfileSlot.BUSY)
        advance(5 * 60_000L, t)
        // Process dies; 10 minutes later the device comes back.
        now += 10 * 60_000L
        val restored = BusyTimer({ now }, store, hooks)
        assertEquals(TimerState.WORK, restored.state)
        assertTrue(restored.isRunning)
        val ticks = ArrayList<TimerEvent.Tick>()
        restored.subscribe { if (it is TimerEvent.Tick) ticks += it }
        restored.poll()
        // The saved snapshot is from the start (20:00 left), so 15 minutes have elapsed.
        assertEquals(5L * 60, ticks.last().remainingS)
    }
}
