package com.busys8.server

import com.busys8.engine.Busybar
import com.busys8.engine.input.InputEvent
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputType
import com.busys8.engine.system.BatteryState
import com.busys8.engine.timer.ProfileSlot
import com.busys8.engine.timer.TimerJson
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * A minimal proto3 writer: enough to encode the status stream's messages (vendor/proto) without
 * a code generator. Scalar fields equal to their default are omitted, as proto3 requires.
 */
class Pb {
    private val out = ByteArrayOutputStream()

    fun uint(field: Int, v: Long) {
        if (v == 0L) return
        key(field, 0)
        varint(v)
    }

    fun uint(field: Int, v: Int) = uint(field, v.toLong() and 0xFFFFFFFFL)

    fun sint(field: Int, v: Int) {
        if (v == 0) return
        key(field, 0)
        varint(((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL)
    }

    fun bool(field: Int, v: Boolean) = uint(field, if (v) 1 else 0)

    fun fixed64(field: Int, v: Long) {
        if (v == 0L) return
        key(field, 1)
        for (i in 0 until 8) out.write((v ushr (8 * i)).toInt() and 0xFF)
    }

    fun string(field: Int, s: String) = bytes(field, s.toByteArray())

    fun bytes(field: Int, b: ByteArray) {
        if (b.isEmpty()) return
        key(field, 2)
        varint(b.size.toLong())
        out.write(b)
    }

    /** A sub-message; written even when empty, which is how a oneof selects an empty case. */
    fun msg(field: Int, block: Pb.() -> Unit) {
        val inner = Pb().apply(block).toByteArray()
        key(field, 2)
        varint(inner.size.toLong())
        out.write(inner)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/** BSB_State.StateUpdate cases, by field number (state.proto). */
enum class StateKind(val field: Int) {
    DEVICE_NAME(1), POWER(2), BRIGHTNESS(3), AUDIO_VOLUME(4), WIFI(5), UPDATE_STATE(6), UPDATE_CHECK(7),
    TIMEZONE(8), MATTER(9), FRAME(10), INPUT(11), TIMER(12), BLE(13), AUTO_UPDATE(14), TIMER_PROFILES(15),
}

/** Builds BSB_State.State messages from the device's current state. */
class StateEncoder(private val bar: Busybar) {
    /** state.proto State: timestamp, then one StateUpdate per entry. */
    fun state(updates: List<Pb.() -> Unit>): ByteArray = Pb().apply {
        fixed64(1, System.currentTimeMillis())
        updates.forEach { u -> msg(2, u) }
    }.toByteArray()

    /** error.proto: RESOURCE_LIMIT, FATAL — sent to a client beyond the connection limit. */
    fun resourceLimitError(): ByteArray = Pb().apply {
        fixed64(1, System.currentTimeMillis())
        msg(3) { uint(1, 0); uint(2, 0) }
    }.toByteArray()

    fun update(kind: StateKind): (Pb.() -> Unit)? = when (kind) {
        StateKind.DEVICE_NAME -> { { msg(1) { string(1, bar.settings.data.name) } } }
        StateKind.POWER -> { { msg(2) { power() } } }
        StateKind.BRIGHTNESS -> { { msg(3) { brightness() } } }
        StateKind.AUDIO_VOLUME -> { { msg(4) { uint(1, bar.platform.volume()) } } }
        StateKind.WIFI -> { { msg(5) { wifi() } } }
        // update.proto: idle (EVENT_NONE, ACTION_NONE, OK) and no check performed.
        StateKind.UPDATE_STATE -> { { msg(6) { uint(1, 6); uint(2, 5) } } }
        StateKind.UPDATE_CHECK -> { { msg(7) { msg(2) { uint(1, 2) }; uint(3, 2) } } }
        StateKind.TIMEZONE -> { { msg(8) { timezone() } } }
        StateKind.MATTER -> { { msg(9) { msg(2) {} } } }
        StateKind.FRAME -> frame()
        StateKind.INPUT -> null
        StateKind.TIMER -> { { msg(12) { jsonMsg(1, TimerJson.snapshot(bar.timer.lastKnownSnapshot).toString()) } } }
        // ble.proto READY: initialised but disabled.
        StateKind.BLE -> { { msg(13) { uint(1, 2) } } }
        StateKind.AUTO_UPDATE -> { { msg(14) { autoUpdate() } } }
        StateKind.TIMER_PROFILES -> { { msg(15) { profiles() } } }
    }

    /** Everything a client gets for `{"send":"all"}` (state_publisher_collect_all). */
    fun all(): List<Pb.() -> Unit> = StateKind.entries.mapNotNull(::update)

    /** input.proto InputEvent, or null for events the stream does not carry (short/long/repeat). */
    fun input(e: InputEvent): (Pb.() -> Unit)? {
        val button = when (e.key) {
            InputKey.OK -> 0
            InputKey.BACK -> 1
            InputKey.START -> 2
            else -> -1
        }
        return when {
            button >= 0 && (e.type == InputType.PRESS || e.type == InputType.RELEASE) ->
                { { msg(11) { msg(1) { uint(1, button); uint(2, if (e.type == InputType.PRESS) 0 else 1) } } } }
            e.key.isSwitch && e.type == InputType.SHORT ->
                { { msg(11) { msg(2) { uint(1, InputKey.SWITCH_POSITIONS.indexOf(e.key)) } } } }
            (e.key == InputKey.UP || e.key == InputKey.DOWN) && e.type == InputType.SHORT ->
                { { msg(11) { msg(3) { sint(1, if (e.key == InputKey.UP) 1 else -1) } } } }
            else -> null
        }
    }

    /** frame.proto: the front display, RGB888 (B, G, R bytes), uncompressed. */
    private fun frame(): Pb.() -> Unit {
        val bgr = frontBgr(bar.device.frames.value.front)
        return { msg(10) { uint(1, 0); uint(2, 72); uint(3, 16); uint(4, 0); uint(5, 0); bytes(6, bgr) } }
    }

    private fun Pb.power() {
        val p = bar.platform.power()
        val status = when (p.state) {
            BatteryState.DISCHARGING -> 0
            BatteryState.CHARGING -> 1
            BatteryState.CHARGED -> 2
        }
        msg(2) { uint(1, status); uint(2, p.chargePercent); uint(3, p.voltageMv); sint(4, p.currentMa); uint(5, p.usbVoltageMv) }
    }

    private fun Pb.brightness() {
        val b = bar.settings.data.brightness
        if (b == null) msg(1) {} else msg(2) { uint(1, b) }
        uint(3, bar.platform.actualBrightness())
    }

    private fun Pb.wifi() {
        val w = bar.platform.wifi()
        if (w == null) {
            msg(2) {}
            return
        }
        msg(3) { uint(1, 0); string(2, w.ssid); string(3, w.bssid); uint(4, w.channel); sint(5, w.rssi) }
        w.ipv4?.let { ip -> msg(4) { string(3, ip); string(4, w.gateway.orEmpty()); string(5, w.netmask.orEmpty()) } }
    }

    private fun Pb.timezone() {
        val id = bar.platform.timeZone()
        val zone = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.of("UTC"))
        val now = Instant.now()
        string(1, id)
        sint(2, zone.rules.getOffset(now).totalSeconds / 60)
        string(3, TimeZone.getTimeZone(zone).getDisplayName(zone.rules.isDaylightSavings(now), TimeZone.SHORT, Locale.ROOT))
    }

    private fun Pb.autoUpdate() {
        val s = bar.settings.data
        fun minutes(hhmm: String) = hhmm.split(':').let { (h, m) -> h.toInt() * 60 + m.toInt() }
        bool(1, s.autoUpdateEnabled)
        msg(2) { uint(1, minutes(s.autoUpdateStart)); uint(2, minutes(s.autoUpdateEnd)) }
    }

    private fun Pb.profiles() {
        for (slot in ProfileSlot.entries) {
            msg(1) {
                string(1, slot.path)
                jsonMsg(2, TimerJson.profile(bar.timer.profile(slot)).toString())
            }
        }
    }

    /** A util/json.proto Json { compression = PLAIN; data = text } message at [field]. */
    private fun Pb.jsonMsg(field: Int, text: String) = msg(field) { bytes(2, text.toByteArray()) }
}
