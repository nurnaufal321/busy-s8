package com.busys8

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.busys8.engine.Busybar
import com.busys8.engine.input.InputKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Maps the phone's hardware to the bar's controls:
 *
 * | Phone                     | Bar                                   |
 * |---------------------------|---------------------------------------|
 * | Volume up / down          | Encoder clockwise / counter-clockwise |
 * | Tap                       | START                                 |
 * | Long-press                | BACK                                  |
 * | Hold about 2 s            | Long BACK (force-quits a JS app)      |
 * | Swipe right / left        | Mode switch to the next / previous position |
 * | Drag up / down            | Encoder, one click per 60 dp          |
 * | Proximity-sensor wave     | OK (encoder button)                   |
 * | Bixby key (if delivered)  | Mode switch, next position, wrapping  |
 *
 * A hardware keyboard (the emulator) also works: arrows, Enter = OK, Space = START,
 * Esc/Backspace = BACK, 1–5 = switch positions.
 */
class PhoneControls(private val bar: Busybar, private val scope: CoroutineScope) {
    private val _switchShownAt = MutableStateFlow(0L)

    /** Uptime of the last local switch move, so the UI can flash the position strip. */
    val switchShownAt: StateFlow<Long> = _switchShownAt

    val switchPosition get() = bar.desktop.switchPosition

    /** Wakes a panel blanked by presence; true if it was dark (the input is then used up). */
    fun wakeIfAway(): Boolean {
        val away = bar.presence.away
        post { bar.presence.seen() }
        return away
    }

    fun tap() = toggle(InputKey.START)

    fun longPress() = toggle(InputKey.BACK)

    /** Holds BACK long enough for the input service to fire LONG, then lets go. */
    fun holdBack() {
        post { bar.input.press(InputKey.BACK) }
        scope.launch {
            delay(HOLD_MS)
            post { bar.input.release(InputKey.BACK) }
        }
    }

    /** One encoder click: [dir] > 0 is clockwise. */
    fun dial(dir: Int) = toggle(if (dir > 0) InputKey.UP else InputKey.DOWN)

    /** [steps] > 0 moves towards SETTINGS. The physical switch has ends, so this clamps. */
    fun moveSwitch(steps: Int, wrap: Boolean = false) {
        val positions = InputKey.SWITCH_POSITIONS
        val i = positions.indexOf(bar.desktop.switchPosition)
        val next = if (wrap) positions[(i + steps).mod(positions.size)] else positions[(i + steps).coerceIn(0, positions.lastIndex)]
        _switchShownAt.value = SystemClock.uptimeMillis()
        if (next != bar.desktop.switchPosition) toggle(next)
    }

    /** Returns true when the key event was used. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.keyCode != KeyEvent.KEYCODE_BACK && wakeIfAway()) return true
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP -> InputKey.UP
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> InputKey.DOWN
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> InputKey.OK
            KeyEvent.KEYCODE_SPACE -> InputKey.START
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> InputKey.BACK
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_5 -> InputKey.SWITCH_POSITIONS[event.keyCode - KeyEvent.KEYCODE_1]
            KEYCODE_BIXBY -> {
                if (down) Log.i(TAG, "Bixby key")
                if (down && event.repeatCount == 0) moveSwitch(1, wrap = true)
                return true
            }
            else -> {
                if (down) Log.i(TAG, "unmapped key ${event.keyCode}")
                return false
            }
        }
        when {
            // Each volume press or auto-repeat is one encoder click, as the encoder toggles keys.
            key == InputKey.UP || key == InputKey.DOWN -> if (down) toggle(key)
            key.isSwitch -> if (down && event.repeatCount == 0) {
                _switchShownAt.value = SystemClock.uptimeMillis()
                toggle(key)
            }
            down && event.repeatCount == 0 -> post { bar.input.press(key) }
            !down -> post { bar.input.release(key) }
        }
        return true
    }

    private fun toggle(key: InputKey) = post { bar.input.toggle(key) }

    private fun post(block: () -> Unit) = bar.device.post(scope, block)

    /**
     * Proximity "wave": near then far again within [WAVE_MAX_MS] presses OK. Holding a hand
     * over the sensor longer does nothing, so the phone lying face-down won't spam OK.
     */
    inner class ProximityWave(context: Context) : SensorEventListener {
        private val sensors = context.getSystemService(SensorManager::class.java)
        private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        private var nearSinceMs = -1L

        fun start() {
            sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }

        fun stop() = sensors.unregisterListener(this)

        override fun onSensorChanged(event: SensorEvent) {
            val near = event.values[0] < (sensor?.maximumRange ?: 1f)
            val now = SystemClock.uptimeMillis()
            if (near) {
                if (nearSinceMs < 0) nearSinceMs = now
            } else {
                if (nearSinceMs >= 0 && now - nearSinceMs <= WAVE_MAX_MS) toggle(InputKey.OK)
                nearSinceMs = -1
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private companion object {
        const val TAG = "PhoneControls"
        /** Samsung's Bixby key on One UI 1.0. */
        const val KEYCODE_BIXBY = 1082
        const val WAVE_MAX_MS = 1000L
        /** Past InputService's LONG threshold (2 × 150 ms). */
        const val HOLD_MS = 450L
    }
}
