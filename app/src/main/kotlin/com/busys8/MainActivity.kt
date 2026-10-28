package com.busys8

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.lifecycleScope
import com.busys8.engine.input.InputKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The kiosk HOME screen: the LED panel full screen, the phone's controls, never sleeping. */
class MainActivity : ComponentActivity() {
    private lateinit var controls: PhoneControls
    private lateinit var proximity: PhoneControls.ProximityWave

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Kiosk.apply(this)
        BusyService.start(this)
        val app = BusyS8App.of(this)
        controls = PhoneControls(app.bar, lifecycleScope)
        proximity = controls.ProximityWave(this)
        lifecycleScope.launch {
            app.platform.brightness.collect { level ->
                window.attributes = window.attributes.apply {
                    screenBrightness = level?.let { maxOf(it, 1) / 100f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
        setContent {
            val frame by app.device.frames.collectAsState()
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectBarGestures(controls) }) {
                LedPanel(frame)
                SwitchStrip(controls)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = controls.onKey(event) || super.dispatchKeyEvent(event)

    override fun onResume() {
        super.onResume()
        Kiosk.enterLockTask(this)
        proximity.start()
    }

    override fun onPause() {
        proximity.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
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
 * app), vertical swipe = mode switch, horizontal
 * drag = the dial: one click per [DIAL_STEP_DP] travelled, right = clockwise, so a long drag
 * scrolls like turning the encoder.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectBarGestures(controls: PhoneControls) {
    val swipeMin = SWIPE_MIN_DP.dp.toPx()
    val dialStep = DIAL_STEP_DP.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        val slop = viewConfiguration.touchSlop
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
            if (!longFired && !dialing && abs(moved.x) > slop && abs(moved.x) > abs(moved.y)) {
                dialing = true
                dialCarry = moved.x
            } else if (dialing) {
                dialCarry += delta.x
            }
            if (dialing) {
                while (abs(dialCarry) >= dialStep) {
                    val dir = if (dialCarry > 0) 1 else -1
                    controls.dial(dir)
                    dialCarry -= dir * dialStep
                }
            }
            if (!change.pressed) break
        }
        if (longFired || dialing) return@awaitEachGesture
        when {
            abs(moved.y) >= swipeMin && abs(moved.y) > 2 * abs(moved.x) -> controls.moveSwitch(if (moved.y > 0) 1 else -1)
            moved.getDistance() <= slop -> controls.tap()
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
