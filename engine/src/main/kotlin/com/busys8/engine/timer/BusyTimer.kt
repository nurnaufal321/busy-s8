package com.busys8.engine.timer

/** BusyTimerState. */
enum class TimerState { IDLE, WORK, REST }

/** BusyTimerEvent: what the BUSY app (and the status stream) listen to. */
sealed interface TimerEvent {
    data class Tick(val elapsedS: Long, val remainingS: Long) : TimerEvent
    data class ModeChanged(val mode: TimerMode?) : TimerEvent
    data class StateChanged(val state: TimerState) : TimerEvent
    data class IntervalEnded(val forced: Boolean) : TimerEvent
    data class Paused(val paused: Boolean) : TimerEvent
    data class ProfileChanged(val slot: ProfileSlot, val profile: Profile) : TimerEvent
    data class SnapshotCreated(val snapshot: Snapshot) : TimerEvent
}

/** busy_timer_start_app / busy_timer_exit_app: a remote snapshot shows or hides the BUSY app. */
interface TimerAppHooks {
    fun showTimer(app: AppConfig)
    fun exitTimer()
}

/** Where profiles and the last snapshot survive restarts (settings_*.json, state.json). */
interface TimerStore {
    fun loadProfile(slot: ProfileSlot): Profile?
    fun saveProfile(slot: ProfileSlot, profile: Profile)
    fun loadSnapshot(): Snapshot?
    fun saveSnapshot(snapshot: Snapshot)
}

/** BusyTimerRunInfo. */
data class RunInfo(val state: TimerState, val config: TimerConfig?, val intervalIndex: Long)

/**
 * Port of the BUSY timer service (services/busy_timer/busy_timer.c). Not thread-safe: like the
 * firmware's service thread, one owner calls everything, including [poll] ~30 times a second.
 *
 * Differences from the firmware: no MQTT/cloud sync, no Matter, no demo-mode UI; publishing is
 * immediate rather than debounced by 250 ms because there is no network peer to spare.
 */
class BusyTimer(
    private val clock: () -> Long,
    private val store: TimerStore,
    private val hooks: TimerAppHooks,
) {
    private val listeners = ArrayList<(TimerEvent) -> Unit>()

    var state = TimerState.IDLE
        private set
    private var app = AppConfig.EMPTY

    /** BUSY app settings of the current session. */
    val appConfig get() = app

    /** Seconds into the current interval (time_elapsed_s). */
    val elapsedSeconds get() = elapsedS
    /** null mirrors BusyTimerModeMax: nothing configured (after a NOT_STARTED snapshot). */
    private var mode: TimerMode? = null
    private var config = TimerConfig(TimerMode.INFINITE)
    private var prevTickMs = 0L
    private var intervalIndex = 0L
    private var elapsedS = 0L
    private var remainingS = 0L
    private var cardId = ""
    var isRunning = false
        private set
    private var demoMode = false

    var lastKnownSnapshot = Snapshot.INITIAL
        private set
    private var savedSnapshot = Snapshot.INITIAL
    private val profiles = ProfileSlot.entries.associateWith { (store.loadProfile(it) ?: it.default) }.toMutableMap()

    init {
        ProfileSlot.entries.forEach { if (store.loadProfile(it) == null) store.saveProfile(it, profiles.getValue(it)) }
        store.loadSnapshot()?.let {
            savedSnapshot = it
            applySnapshot(it)
        }
    }

    fun subscribe(listener: (TimerEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    // ---- public API (busy_timer_api.c) ----

    fun start(slot: ProfileSlot) {
        if (state == TimerState.IDLE) applyProfile(slot)
        startInternal()
    }

    fun stop() {
        if (state == TimerState.IDLE) return
        state = TimerState.IDLE
        stopTimer()
        notify(TimerEvent.StateChanged(state))
        captureAndPublish()
    }

    fun toggle() {
        if (isRunning) stopTimer() else startTimer()
        notify(TimerEvent.Paused(!isRunning))
        captureAndPublish()
    }

    fun skip() {
        if (!isRunning) return
        nextState(forced = true)
        captureAndPublish()
    }

    fun finalize() {
        if (state == TimerState.IDLE) captureAndPublish()
    }

    /** busy_timer_add_time_api_message_handler: ±minutes, rounded to the increment. */
    fun addTime(minutes: Int) {
        if (!isRunning) return
        if (mode == TimerMode.INFINITE) {
            if (minutes > 0) {
                infiniteToSimple()
                captureAndPublish()
            }
            return
        }
        var remaining = remainingS
        var increment = minutes * 60L
        if (increment < 0 && remaining < TimerLimits.TIME_MIN_MN * 60L) return
        val remainder = remaining % increment
        if (remainder != 0L) increment = if (increment > 0) increment - remainder else -remainder
        remaining += increment
        remainingS = remaining.coerceIn(TimerLimits.TIME_MIN_MN * 60L, TimerLimits.TIME_MAX_MN * 60L)
        startTimer()
        notifyTick()
        captureAndPublish()
    }

    fun runInfo() = RunInfo(state, mode?.let { config.copy(mode = it) }, intervalIndex)

    fun setSnapshot(snapshot: Snapshot) = applySnapshot(snapshot)

    fun profile(slot: ProfileSlot): Profile = profiles.getValue(slot)

    /** busy_timer_set_profile_internal: newer, valid profiles replace the stored one. */
    fun setProfile(slot: ProfileSlot, profile: Profile) {
        if (!profile.isValid()) return
        val current = profiles.getValue(slot)
        if (profile.timestampMs <= current.timestampMs) return
        val now = clock()
        val accepted = if (profile.timestampMs > now) profile.copy(timestampMs = now) else profile
        profiles[slot] = accepted
        store.saveProfile(slot, accepted)
        notify(TimerEvent.ProfileChanged(slot, accepted))
    }

    /** busy_timer_set_preset: the on-device setup screens edit timer and app settings in place. */
    fun setPreset(slot: ProfileSlot, timer: TimerConfig, appConfig: AppConfig) {
        val updated = profiles.getValue(slot).copy(timer = timer, app = appConfig, timestampMs = clock())
        profiles[slot] = updated
        store.saveProfile(slot, updated)
        notify(TimerEvent.ProfileChanged(slot, updated))
    }

    /** The poll timer: advances the clock while running (not in infinite mode). */
    fun poll() {
        if (isRunning && mode != TimerMode.INFINITE) update(clock())
    }

    /** Replays the current state to a new subscriber (busy_timer_notify_initial_state). */
    fun notifyInitialState() {
        notify(TimerEvent.ModeChanged(mode))
        notifyTick()
        notify(TimerEvent.StateChanged(state))
        notify(TimerEvent.Paused(!isRunning))
    }

    // ---- internals (busy_timer.c) ----

    private fun applyProfile(slot: ProfileSlot) {
        val p = profiles.getValue(slot)
        app = p.app
        config = p.timer
        mode = p.timer.mode
        cardId = p.cardId
        demoMode = false
    }

    private fun startInternal() {
        if (state == TimerState.IDLE) {
            notify(TimerEvent.ModeChanged(mode))
            nextState(forced = true)
        } else {
            startTimer()
            notifyInitialState()
        }
        captureAndPublish()
    }

    private fun nextStateOf(): TimerState = when (state) {
        TimerState.IDLE -> TimerState.WORK
        TimerState.WORK ->
            if (mode == TimerMode.INTERVAL && intervalIndex < config.interval.cycles * 2 - 1) TimerState.REST
            else TimerState.IDLE
        TimerState.REST -> {
            check(mode == TimerMode.INTERVAL) { "Invalid timer mode in rest state" }
            TimerState.WORK
        }
    }

    private fun intervalLengthS(): Long = when (mode) {
        TimerMode.INFINITE -> UINT32_MAX
        TimerMode.SIMPLE -> config.simpleTotalMs / 1000
        TimerMode.INTERVAL -> (if (state == TimerState.WORK) config.interval.workMs else config.interval.restMs) / 1000
        null -> error("Invalid BusyTimerMode value")
    }

    private fun nextState(forced: Boolean) {
        if (state != TimerState.IDLE && mode == TimerMode.INTERVAL) intervalIndex += 1 else intervalIndex = 0
        state = nextStateOf()
        if (state != TimerState.IDLE) {
            elapsedS = 0
            remainingS = intervalLengthS()
            val autostart = mode == TimerMode.INTERVAL && config.interval.autostart
            if (autostart || forced) {
                startTimer()
                notify(TimerEvent.StateChanged(state))
                notifyTick()
            } else {
                stopTimer()
                notify(TimerEvent.IntervalEnded(forced))
            }
        } else {
            stopTimer()
            notify(TimerEvent.StateChanged(state))
            notify(TimerEvent.IntervalEnded(forced))
        }
    }

    private fun update(nowMs: Long) {
        if (nowMs < prevTickMs) {
            prevTickMs = nowMs
            notifyTick()
            return
        }
        val dtMs = nowMs - prevTickMs
        val dtS = dtMs / 1000
        if (dtS == 0L) return
        for (i in 0 until dtS) {
            val inc = increment()
            if (remainingS >= inc) {
                remainingS -= inc
                elapsedS += inc
                if (i == dtS - 1) notifyTick()
            } else {
                nextState(forced = false)
                if (state == TimerState.IDLE) break
                if (mode == TimerMode.INTERVAL && !config.interval.autostart) break
            }
        }
        prevTickMs = nowMs - (dtMs - dtS * 1000)
    }

    private fun increment(): Long = if (!demoMode) 1 else when {
        remainingS > 60 -> 60
        remainingS > 30 -> 30
        remainingS > 15 -> 15
        remainingS > 5 -> 5
        else -> 1
    }

    private fun startTimer() {
        prevTickMs = clock()
        isRunning = true
    }

    private fun stopTimer() {
        isRunning = false
    }

    private fun infiniteToSimple() {
        mode = TimerMode.SIMPLE
        config = config.copy(mode = TimerMode.SIMPLE)
        remainingS = TimerLimits.TIME_INCREMENT_MN * 60L
        elapsedS = 0
        startTimer()
        notifyInitialState()
    }

    private fun capture() {
        val st: SnapshotState = if (state == TimerState.IDLE) SnapshotState.NotStarted else when (mode) {
            TimerMode.INFINITE -> SnapshotState.Infinite(cardId, !isRunning)
            TimerMode.SIMPLE -> SnapshotState.Simple(cardId, !isRunning, remainingS * 1000)
            TimerMode.INTERVAL -> SnapshotState.Interval(
                cardId, !isRunning, intervalIndex, (elapsedS + remainingS) * 1000, remainingS * 1000, config.interval,
            )
            null -> error("Invalid BusyTimerMode value")
        }
        lastKnownSnapshot = Snapshot(st, app, clock())
    }

    private fun captureAndPublish() {
        capture()
        publishSnapshot()
    }

    private fun publishSnapshot() {
        notify(TimerEvent.SnapshotCreated(lastKnownSnapshot))
        if (lastKnownSnapshot.timestampMs > savedSnapshot.timestampMs) {
            savedSnapshot = lastKnownSnapshot
            store.saveSnapshot(savedSnapshot)
        }
    }

    /** busy_timer_apply_snapshot: newer snapshots replace the running state. */
    private fun applySnapshot(snapshot: Snapshot) {
        if (snapshot.timestampMs <= lastKnownSnapshot.timestampMs) return
        stopTimer()
        val newState: TimerState
        when (val s = snapshot.state) {
            SnapshotState.NotStarted -> {
                mode = null
                newState = TimerState.IDLE
            }
            is SnapshotState.Infinite -> {
                mode = TimerMode.INFINITE
                newState = TimerState.WORK
            }
            is SnapshotState.Simple -> {
                mode = TimerMode.SIMPLE
                newState = TimerState.WORK
                elapsedS = 0
                remainingS = s.timeLeftMs / 1000
            }
            is SnapshotState.Interval -> {
                mode = TimerMode.INTERVAL
                newState = if (s.index % 2 == 1L) TimerState.REST else TimerState.WORK
                intervalIndex = s.index
                elapsedS = (s.totalMs - s.leftMs) / 1000
                remainingS = s.leftMs / 1000
                config = config.copy(interval = s.config)
            }
        }
        mode?.let { config = config.copy(mode = it) }
        lastKnownSnapshot = snapshot
        state = newState
        snapshot.cardId?.let { cardId = it }
        app = snapshot.app
        if (newState != TimerState.IDLE) {
            if (!snapshot.isPaused) startTimer()
            prevTickMs = snapshot.timestampMs
            hooks.showTimer(snapshot.app)
            notifyInitialState()
        } else {
            notify(TimerEvent.StateChanged(state))
            hooks.exitTimer()
        }
        publishSnapshot()
    }

    private fun notifyTick() = notify(TimerEvent.Tick(elapsedS, remainingS))

    private fun notify(event: TimerEvent) = listeners.toList().forEach { it(event) }

    companion object {
        const val UINT32_MAX = 0xFFFFFFFFL
    }
}
