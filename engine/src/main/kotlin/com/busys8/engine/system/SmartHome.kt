package com.busys8.engine.system

import com.busys8.engine.timer.BusyTimer
import com.busys8.engine.timer.ProfileSlot
import com.busys8.engine.timer.TimerEvent
import com.busys8.engine.timer.TimerState

/**
 * The Matter device that carries the switch, as the host runs it (on the phone, the
 * busy-matter process). [NoMatter] stands in where there is none.
 */
interface MatterLink {
    val available: Boolean

    /** Paired ecosystems (fabrics). */
    val fabricCount: Int

    /** api_smart_home.c: never_started, started, completed_successfully or failed. */
    val pairingStatus: String
    val pairingStatusAtMs: Long

    /** The 11-digit manual code and `MT:` QR payload, once the device has reported them. */
    val manualCode: String?
    val qrCode: String?

    /** Opens a pairing window; returns how long it stays open in seconds, or null if it can't. */
    fun openWindow(): Int?

    /** Forgets every pairing (matter_factory_reset). */
    fun factoryReset(): Boolean

    /** The switch changed on the bar's side; the Matter attribute follows. */
    fun send(on: Boolean)
}

object NoMatter : MatterLink {
    override val available = false
    override val fabricCount = 0
    override val pairingStatus = "never_started"
    override val pairingStatusAtMs = 0L
    override val manualCode: String? = null
    override val qrCode: String? = null
    override fun openWindow(): Int? = null
    override fun factoryReset() = false
    override fun send(on: Boolean) = Unit
}

/**
 * services/busy_timer/busy_timer_smart_home.c: the smart-home switch and the timer, both ways.
 *
 * - Timer → switch, when the profile's "Smart home" is on: on while a work interval runs, off
 *   on rest, pause, interval end or stop.
 * - Switch → timer, from a Matter controller or `POST /api/smart_home/switch`: on starts the
 *   BUSY profile (forcing smart home on for that session), resumes a paused work interval or
 *   skips a rest; off stops the session.
 *
 * The first state the switch reports is taken as is, without acting on it, as the firmware does
 * until "the initial state is received", so a restart never starts or stops a session.
 * Device thread only.
 */
class SmartHomeSwitch(private val timer: BusyTimer) {
    /** The switch as last known; null until the Matter device has reported it. */
    var state: Boolean? = null
        private set

    /** Where switch changes made here go (the Matter device). */
    var output: (Boolean) -> Unit = {}

    /** Smart home forced on for a session the switch started. */
    private var forced = false

    private val enabled get() = forced || timer.appConfig.smartHome

    init {
        timer.subscribe(::onTimerEvent)
    }

    private fun onTimerEvent(event: TimerEvent) {
        if (event is TimerEvent.StateChanged && event.state == TimerState.IDLE) {
            val wasEnabled = enabled
            forced = false
            if (wasEnabled) set(false)
            return
        }
        if (!enabled) return
        val desired = when (event) {
            is TimerEvent.StateChanged -> timer.isRunning && event.state == TimerState.WORK
            is TimerEvent.Paused -> if (timer.state == TimerState.WORK) !event.paused else return
            is TimerEvent.IntervalEnded -> false
            else -> return
        }
        set(desired)
    }

    /** busy_timer_smart_home_timer_pubsub_callback → matter_set_switch_state. */
    private fun set(on: Boolean) {
        if (on == state) return
        output(on)
        if (state != null) state = on
    }

    /**
     * busy_timer_smart_home_handle_switch_state: the switch was set to [on] from outside.
     * [fromMatter] is false for the HTTP API, whose change the Matter device must also hear.
     */
    fun handleSwitch(on: Boolean, fromMatter: Boolean = true) {
        if (on == state) return
        val initial = state == null
        val result = if (initial) on else process(on)
        if (result != null) {
            state = result
            if (result != on || !fromMatter) output(result)
        } else if (!fromMatter) {
            output(on)
        }
    }

    /** busy_timer_smart_home_process_switch_state; null = the timer event will report it. */
    private fun process(on: Boolean): Boolean? {
        if (!on) {
            if (timer.state != TimerState.IDLE) timer.stop()
            return false
        }
        return when (timer.state) {
            TimerState.IDLE -> {
                startBusy()
                null
            }
            TimerState.WORK -> when {
                timer.isRunning -> true
                timer.elapsedSeconds == 0L -> startBusy().let { null }
                else -> timer.toggle().let { null }
            }
            TimerState.REST -> when {
                timer.isRunning -> timer.skip().let { null }
                timer.elapsedSeconds == 0L -> startBusy().let { false }
                else -> {
                    timer.toggle()
                    timer.skip()
                    null
                }
            }
        }
    }

    /** busy_timer_smart_home_start_app: the BUSY profile, smart home forced on for the session. */
    private fun startBusy() {
        forced = true
        timer.start(ProfileSlot.BUSY)
    }
}
