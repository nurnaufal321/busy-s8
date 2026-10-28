package com.busys8.engine.apps

import com.busys8.core.Align
import com.busys8.core.AnimLoader
import com.busys8.core.AnimPlayer
import com.busys8.core.ApiFont
import com.busys8.core.DeviceStorage
import com.busys8.core.Gfx
import com.busys8.core.ImageLoader
import com.busys8.core.RawImage
import com.busys8.engine.input.InputKey
import com.busys8.engine.system.BleControl

/**
 * applications/settings/ble_settings: SETTINGS → Bluetooth, the screen the official app sends
 * you to. Without a paired phone it is pairing mode: Bluetooth comes on, the front shows a
 * blinking Bluetooth icon and "Pairing mode...", the back the device name; when a phone pairs
 * it shows "Connected". Leaving unpaired, or five minutes without a pairing, turns Bluetooth
 * off again. With a paired phone it offers "Forget device", behind a confirmation.
 */
class BluetoothScreen(
    private val ble: () -> BleControl,
    private val deviceName: () -> String,
    private val images: ImageLoader,
    private val anims: AnimLoader,
    /** Persists Bluetooth on/off (the ble settings' enabled flag). */
    private val setEnabled: (Boolean) -> Unit,
) {
    private enum class State { PAIRING, CONNECTED, FORGET, CONFIRM }

    private var state = State.PAIRING
    private var pairingSinceMs = 0L
    private var confirmForget = false
    private var pairingIcon: AnimPlayer? = null

    /** Set when the screen is done and SETTINGS should show its list again. */
    var finished = false
        private set

    fun enter(nowMs: Long) {
        finished = false
        if (ble().paired) state = State.FORGET else startPairing(nowMs)
    }

    fun exit() {
        // scene_pairing_mode_on_exit: unpaired, Bluetooth goes off with the screen.
        if (state == State.PAIRING && !ble().paired) {
            ble().disable()
            setEnabled(false)
        }
        pairingIcon = null
    }

    fun tick(nowMs: Long) {
        if (state != State.PAIRING) return
        if (ble().paired) {
            state = State.CONNECTED
        } else if (nowMs - pairingSinceMs > PAIRING_TIMEOUT_MS) {
            leave()
        }
    }

    /** Short presses; returns true if used. */
    fun input(key: InputKey, nowMs: Long): Boolean {
        when (state) {
            State.PAIRING, State.CONNECTED -> if (key == InputKey.BACK) leave() else return false
            State.FORGET -> when (key) {
                InputKey.OK, InputKey.START -> {
                    state = State.CONFIRM
                    confirmForget = true
                }
                InputKey.BACK -> leave()
                else -> return false
            }
            State.CONFIRM -> when (key) {
                InputKey.UP, InputKey.DOWN -> confirmForget = !confirmForget
                InputKey.OK, InputKey.START -> if (confirmForget) {
                    ble().forget()
                    startPairing(nowMs)
                } else {
                    state = State.FORGET
                }
                InputKey.BACK -> state = State.FORGET
                else -> return false
            }
        }
        return true
    }

    fun render(front: Gfx, back: Gfx, nowMs: Long) {
        when (state) {
            State.PAIRING -> {
                val icon = pairingIcon ?: anims.load(asset("animations/ble_pairing_8x8.anim"))?.let { AnimPlayer(it, nowMs) }?.also { pairingIcon = it }
                status(front, icon?.frame(nowMs), "Pairing mode...")
                backStatus(back, "ble_back_white_11x11", "Pairing mode...", "Device name: ${deviceName()}")
            }
            State.CONNECTED -> {
                status(front, images.load(DeviceStorage.sharedImage("checkmark_front_8x8.image")), "Connected")
                backStatus(back, "checkmark_back_11x11", "Connected", "Device name: ${deviceName()}", shared = true)
            }
            State.FORGET -> {
                status(front, images.load(asset("images/ble_front_paired_8x8.image")), "Forget device")
                backStatus(back, "ble_back_paired_11x11", "Forget device", "Paired")
            }
            State.CONFIRM -> {
                // dialog_set_options: turn the dial between Forget and Cancel.
                val choice = if (confirmForget) "Forget" else "Cancel"
                front.text("< $choice >", ApiFont.NORMAL, 36, 8, if (confirmForget) FORGET_COLOR else WHITE, Align.CENTER)
                back.text("Forget device?", ApiFont.LARGE, 74, 26, WHITE, Align.CENTER)
                back.text("Forget", ApiFont.NORMAL, 50, 50, if (confirmForget) FORGET_COLOR else GRAY, Align.CENTER)
                back.text("Cancel", ApiFont.NORMAL, 98, 50, if (!confirmForget) WHITE else GRAY, Align.CENTER)
            }
        }
    }

    private fun startPairing(nowMs: Long) {
        state = State.PAIRING
        pairingSinceMs = nowMs
        if (ble().enable()) setEnabled(true)
    }

    private fun leave() {
        exit()
        finished = true
    }

    /** status_view on the front: the icon and the text, centred together. */
    private fun status(front: Gfx, icon: RawImage?, text: String) {
        val iconW = icon?.width ?: 0
        val gap = if (icon != null) 2 else 0
        // The widest font that fits beside the icon ("Pairing mode..." needs the condensed one).
        val font = FRONT_FONTS.firstOrNull { iconW + gap + front.textWidth(text, it) <= 72 } ?: FRONT_FONTS.last()
        val w = iconW + gap + front.textWidth(text, font)
        val x = maxOf(0, (72 - w) / 2)
        icon?.let { front.image(it, x, (16 - it.height) / 2) }
        front.text(text, font, x + iconW + gap, 8, WHITE, Align.MID_LEFT)
    }

    private fun backStatus(back: Gfx, iconName: String, primary: String, auxiliary: String, shared: Boolean = false) {
        val path = if (shared) DeviceStorage.sharedImage("$iconName.image") else asset("images/$iconName.image")
        images.load(path)?.let { back.image(it, 74 - it.width / 2, 12) }
        back.text(primary, ApiFont.LARGE, 74, 36, WHITE, Align.CENTER)
        back.text(auxiliary, ApiFont.SMALL, 74, 52, GRAY, Align.CENTER)
    }

    private fun asset(path: String) = "${DeviceStorage.APPS_ASSETS}ble_settings/$path"

    private companion object {
        /** BLE_PAIRING_TIMEOUT_MIN. */
        const val PAIRING_TIMEOUT_MS = 5 * 60_000L
        val FRONT_FONTS = listOf(ApiFont.NORMAL, ApiFont.CONDENSED, ApiFont.TINY)
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GRAY = 0xFF888888.toInt()
        const val FORGET_COLOR = 0xFFFF3B30.toInt()
    }
}
