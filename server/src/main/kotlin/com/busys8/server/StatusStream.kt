package com.busys8.server

import com.busys8.engine.Busybar
import com.busys8.engine.input.InputEvent
import com.busys8.engine.system.SettingsChange
import com.busys8.engine.timer.TimerEvent
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fans device events out to status-stream clients (services/state_publisher). Each client
 * collects pending changes and sends them as one BSB_State.State per 100 ms tick.
 */
class StateHub(private val bar: Busybar) {
    class Client {
        @Volatile var enabled = false
        private val pending = LinkedHashSet<StateKind>()
        private val inputs = ArrayList<InputEvent>()

        @Synchronized fun mark(kind: StateKind) {
            if (enabled) pending += kind
        }

        @Synchronized fun input(e: InputEvent) {
            if (enabled) inputs += e
        }

        @Synchronized fun drain(): Pair<List<StateKind>, List<InputEvent>> {
            val r = pending.toList() to inputs.toList()
            pending.clear()
            inputs.clear()
            return r
        }
    }

    val clients = CopyOnWriteArrayList<Client>()

    fun markAll(kind: StateKind) = clients.forEach { it.mark(kind) }

    init {
        bar.settings.subscribe { change ->
            when (change) {
                SettingsChange.NAME -> markAll(StateKind.DEVICE_NAME)
                SettingsChange.BRIGHTNESS -> markAll(StateKind.BRIGHTNESS)
                SettingsChange.VOLUME -> markAll(StateKind.AUDIO_VOLUME)
                SettingsChange.TIMEZONE -> markAll(StateKind.TIMEZONE)
                SettingsChange.OTHER -> markAll(StateKind.AUTO_UPDATE)
                SettingsChange.BLE -> markAll(StateKind.BLE)
                SettingsChange.ACCESS -> Unit
            }
        }
        bar.timer.subscribe { e ->
            when (e) {
                is TimerEvent.SnapshotCreated -> markAll(StateKind.TIMER)
                is TimerEvent.ProfileChanged -> markAll(StateKind.TIMER_PROFILES)
                else -> Unit
            }
        }
        bar.input.subscribe { e -> clients.forEach { it.input(e) } }
    }

    companion object {
        const val MAX_CLIENTS = 4
        const val FRAME_INTERVAL_MS = 100L
        /** Power and Wi-Fi are polled; the firmware gets them as hardware events. */
        const val POLL_INTERVAL_MS = 5_000L
    }
}

/** GET /api/status/ws: the protobuf status and screen stream (api_status_streaming.c). */
fun Route.statusStream(bar: Busybar, hub: StateHub) {
    val encoder = StateEncoder(bar)

    webSocket("/api/status/ws") {
        if (hub.clients.size >= StateHub.MAX_CLIENTS) {
            send(Frame.Binary(true, encoder.resourceLimitError()))
            close()
            return@webSocket
        }
        val client = StateHub.Client()
        hub.clients += client
        val sender = launch {
            var lastSeq = -1L
            var lastPolled = Pair(bar.platform.power(), bar.platform.wifi())
            var lastPollMs = 0L
            while (isActive) {
                delay(StateHub.FRAME_INTERVAL_MS)
                if (!client.enabled) continue
                val now = System.currentTimeMillis()
                if (now - lastPollMs >= StateHub.POLL_INTERVAL_MS) {
                    lastPollMs = now
                    val polled = Pair(bar.platform.power(), bar.platform.wifi())
                    if (polled.first != lastPolled.first) client.mark(StateKind.POWER)
                    if (polled.second != lastPolled.second) client.mark(StateKind.WIFI)
                    lastPolled = polled
                }
                val seq = bar.device.frames.value.seq
                if (seq != lastSeq) {
                    lastSeq = seq
                    client.mark(StateKind.FRAME)
                }
                val (kinds, inputs) = client.drain()
                val updates = kinds.mapNotNull(encoder::update) + inputs.mapNotNull(encoder::input)
                if (updates.isNotEmpty()) send(Frame.Binary(true, encoder.state(updates)))
            }
        }
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val msg = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                (msg["enable"] as? JsonPrimitive)?.booleanOrNull?.let { client.enabled = it }
                if ((msg["send"] as? JsonPrimitive)?.content == "all" && client.enabled) {
                    client.drain()
                    send(Frame.Binary(true, encoder.state(encoder.all())))
                }
            }
        } finally {
            sender.cancel()
            hub.clients -= client
        }
    }
}
