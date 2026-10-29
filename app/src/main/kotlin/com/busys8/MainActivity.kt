package com.busys8

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.busys8.engine.input.InputKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The kiosk HOME screen: the LED panel full screen, the phone's controls, never sleeping. */
class MainActivity : ComponentActivity() {
    private lateinit var controls: PhoneControls
    private lateinit var proximity: PhoneControls.ProximityWave
    private lateinit var camera: PresenceCamera
    private lateinit var voice: VoiceMonitor

    /** What the sensing loop shows: panel blanked while away, and which sensors are live. */
    private val sensing = MutableStateFlow(SensingState())

    /** Whether the kiosk policies are on (SETTINGS → Kiosk). */
    private var kiosk = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        BusyService.start(this)
        val app = BusyS8App.of(this)
        kiosk = app.bar.settings.data.kiosk
        Kiosk.apply(this, kiosk)
        controls = PhoneControls(app.bar, lifecycleScope)
        proximity = controls.ProximityWave(this)
        camera = PresenceCamera(this, app.bar, lifecycleScope)
        voice = VoiceMonitor(app.bar, lifecycleScope)
        lifecycleScope.launch {
            // Follows the Sensors settings once a second; the camera and mic run only while on.
            while (true) {
                val d = app.bar.settings.data
                val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (d.kiosk != kiosk) setKiosk(d.kiosk)
                if (d.presence && resumed) camera.start() else camera.stop()
                if (d.autoOnCall && resumed) voice.start() else voice.stop()
                sensing.value = SensingState(away = app.bar.presence.away, camera = camera.running, mic = voice.running)
                delay(1000)
            }
        }
        lifecycleScope.launch {
            app.platform.brightness.collect { level ->
                window.attributes = window.attributes.apply {
                    screenBrightness = level?.let { maxOf(it, 1) / 100f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
        setContent {
            val frame by app.device.frames.collectAsState()
            val state by sensing.collectAsState()
            var dragPx by remember { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectBarGestures(controls) { dragPx = it } }) {
                if (!state.away) {
                    LedPanel(
                        frame,
                        showClock = app.bar.desktop.switchPosition != InputKey.OFF,
                        zone = app.platform::timeZone,
                        clock12h = { app.bar.settings.data.clock12h },
                        dragPx = dragPx,
                    )
                }
                SwitchStrip(controls)
                SensorDots(state)
            }
        }
    }

    /** SETTINGS → Kiosk. */
    private fun setKiosk(on: Boolean) {
        kiosk = on
        Kiosk.apply(this, on)
        if (on) {
            Kiosk.enterLockTask(this)
            hideSystemUi()
        } else {
            runCatching { stopLockTask() }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = controls.onKey(event) || super.dispatchKeyEvent(event)

    override fun onResume() {
        super.onResume()
        if (kiosk) Kiosk.enterLockTask(this)
        proximity.start()
    }

    override fun onPause() {
        proximity.stop()
        camera.stop()
        voice.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && kiosk) hideSystemUi()
    }

    @Suppress("DEPRECATION") // WindowInsetsController needs API 30; this is API 28.
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}

/**
 * Tap = START, long-press = BACK (fires while held), holding on = a long BACK (force-quits a JS
 * app), horizontal swipe = mode switch (right = next position, towards SETTINGS), vertical drag =
 * the dial: one click per [DIAL_STEP_DP] travelled, up = clockwise, so a long drag scrolls like
 * turning the encoder.
 */
/**
 * The panel's touch controls (see [PhoneControls]). A sideways swipe that starts on the picture
 * changes status, reporting the drag to [onStatusDrag] so the picture can follow; one that starts
 * on the clock strip moves the mode switch.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectBarGestures(
    controls: PhoneControls,
    onStatusDrag: (Float) -> Unit,
) {
    val swipeMin = SWIPE_MIN_DP.dp.toPx()
    val dialStep = DIAL_STEP_DP.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        if (controls.wakeIfAway()) {
            // The first touch on a dark panel only wakes it.
            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
            return@awaitEachGesture
        }
        val slop = viewConfiguration.touchSlop
        val onPicture = down.position.y < PanelLayout(size.width.toFloat(), size.height.toFloat()).splitY
        var moved = Offset.Zero
        var longFired = false
        var holdFired = false
        var dialing = false
        var dialCarry = 0f
        while (true) {
            val still = moved.getDistance() <= slop && !holdFired
            val deadline = if (longFired) HOLD_BACK_MS else LONG_PRESS_MS
            val waitMs = deadline - (SystemClock.uptimeMillis() - down.uptimeMillis)
            val event = if (still && waitMs > 0) withTimeoutOrNull(waitMs) { awaitPointerEvent() }
            else if (still) null
            else awaitPointerEvent()
            if (event == null) {
                if (!longFired) {
                    longFired = true
                    controls.longPress()
                } else {
                    holdFired = true
                    controls.holdBack()
                }
                continue
            }
            val change = event.changes.first()
            val delta = change.positionChange()
            moved += delta
            if (!longFired && !dialing && abs(moved.y) > slop && abs(moved.y) > abs(moved.x)) {
                dialing = true
                dialCarry = moved.y
            } else if (dialing) {
                dialCarry += delta.y
            }
            if (onPicture && !longFired && !dialing && abs(moved.x) > slop) onStatusDrag(moved.x)
            if (dialing) {
                // Up is clockwise (screen y grows downwards).
                while (abs(dialCarry) >= dialStep) {
                    val dir = if (dialCarry < 0) 1 else -1
                    controls.dial(dir)
                    dialCarry += dir * dialStep
                }
            }
            if (!change.pressed) break
        }
        onStatusDrag(0f)
        if (longFired || dialing) return@awaitEachGesture
        when {
            abs(moved.x) >= swipeMin && abs(moved.x) > 2 * abs(moved.y) ->
                // Swiping left brings the next status in from the right.
                if (onPicture) controls.swipeStatus(if (moved.x < 0) 1 else -1) else controls.moveSwitch(if (moved.x > 0) 1 else -1)
            moved.getDistance() <= slop -> controls.tap()
        }
    }
}

data class SensingState(val away: Boolean = false, val camera: Boolean = false, val mic: Boolean = false)

/**
 * Privacy indicators, since Android 9 has none: a green dot while the camera is on, an orange
 * one while the microphone is, in the top-right corner outside the LED grid.
 */
@Composable
private fun SensorDots(state: SensingState) {
    if (!state.camera && !state.mic) return
    Box(Modifier.fillMaxSize().padding(top = 12.dp, end = 24.dp), contentAlignment = Alignment.TopEnd) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.camera) Box(Modifier.size(8.dp).background(Color(0xFF22C55E), CircleShape))
            if (state.mic) Box(Modifier.size(8.dp).background(Color(0xFFFF8A00), CircleShape))
        }
    }
}

/** Flashes the five switch positions for a moment after a local switch move. */
@Composable
private fun SwitchStrip(controls: PhoneControls) {
    val shownAt by controls.switchShownAt.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(shownAt) {
        if (shownAt == 0L) return@LaunchedEffect
        visible = true
        delay(STRIP_VISIBLE_MS)
        visible = false
    }
    if (!visible) return
    // Recompose with the position the move produced (it lands on the device thread first).
    var current by remember { mutableStateOf(controls.switchPosition) }
    LaunchedEffect(shownAt) {
        repeat(10) {
            current = controls.switchPosition
            delay(50)
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            InputKey.SWITCH_POSITIONS.forEach { key ->
                BasicText(
                    key.apiName.uppercase(),
                    style = TextStyle(color = if (key == current) Color.White else Color(0xFF3A3A3A), fontSize = 18.sp),
                )
            }
        }
    }
}

private const val LONG_PRESS_MS = 600L
private const val HOLD_BACK_MS = 2000L
private const val SWIPE_MIN_DP = 60
private const val DIAL_STEP_DP = 60
private const val STRIP_VISIBLE_MS = 1500L
