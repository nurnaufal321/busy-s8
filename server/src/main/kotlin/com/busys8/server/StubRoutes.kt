package com.busys8.server

import com.busys8.engine.Busybar
import com.busys8.engine.system.BleStatus
import com.busys8.engine.system.SettingsChange
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Endpoints for services the phone handles differently: Wi-Fi (Android owns the network; status
 * is real, configuration refused), BLE and smart home (backed by the app's Bluetooth bridge and
 * Matter device), and the BUSY cloud account and firmware updates, which answer with a
 * schema-valid "not available" so clients degrade instead of breaking.
 */
fun Route.stubRoutes(bar: Busybar) {
    // ---- Wi-Fi: real status from the phone, configuration refused ----
    get("/api/wifi/status") {
        val w = bar.platform.wifi()
        call.respondJson(buildJsonObject {
            if (w == null) {
                put("state", "disconnected")
            } else {
                put("state", "connected")
                put("ssid", w.ssid)
                put("bssid", w.bssid)
                put("channel", w.channel)
                put("rssi", w.rssi)
                put("security", "Unsupported")
                w.ipv4?.let { ip ->
                    putJsonObject("ip_config") { put("ip_method", "dhcp"); put("ip_type", "ipv4"); put("address", ip) }
                }
            }
        })
    }
    get("/api/wifi/networks") {
        val w = bar.platform.wifi()
        call.respondJson(buildJsonObject {
            put("count", if (w == null) 0 else 1)
            put("networks", buildJsonArray {
                w?.let { add(buildJsonObject { put("ssid", it.ssid); put("security", "Unsupported"); put("rssi", it.rssi) }) }
            })
        })
    }
    post("/api/wifi/connect") { call.unavailable("Wi-Fi is managed by the phone's settings") }
    post("/api/wifi/disconnect") { call.unavailable("Wi-Fi is managed by the phone's settings") }

    // ---- BLE (api_ble.c), backed by the host's Bluetooth bridge ----
    get("/api/ble/status") {
        val ble = bar.ble
        if (ble.status == BleStatus.ERROR) return@get call.unavailable(ble.status.api)
        call.respondJson(buildJsonObject {
            put("status", ble.status.api)
            if (ble.status == BleStatus.CONNECTED) ble.peerAddress?.let { put("address", it) }
        })
    }
    post("/api/ble/enable") {
        if (!bar.ble.enable()) return@post call.unavailable("Unable to start BLE")
        bar.settings.update(SettingsChange.BLE) { it.copy(bleEnabled = true) }
        call.replyOk()
    }
    post("/api/ble/disable") {
        if (!bar.ble.disable()) return@post call.unavailable("Unable to stop BLE")
        bar.settings.update(SettingsChange.BLE) { it.copy(bleEnabled = false) }
        call.replyOk()
    }
    delete("/api/ble/pairing") {
        if (bar.ble.forget()) call.replyOk() else call.unavailable("Unable to remove pairing")
    }

    // ---- Smart home (api_smart_home.c), backed by the phone's Matter device ----
    get("/api/smart_home/pairing") {
        val m = bar.matter
        if (!m.available) return@get call.unavailable("Smart home unavailable")
        call.respondJson(buildJsonObject {
            put("fabric_count", m.fabricCount)
            putJsonObject("latest_pairing_status") {
                put("value", m.pairingStatus)
                if (m.pairingStatusAtMs > 0) put("timestamp", m.pairingStatusAtMs / 1000)
            }
        })
    }
    post("/api/smart_home/pairing") {
        val m = bar.matter
        val seconds = m.openWindow()
        val qr = m.qrCode
        val code = m.manualCode
        if (seconds == null || qr == null || code == null) return@post call.unavailable("Smart home unavailable")
        call.respondJson(buildJsonObject {
            put("available_until", (System.currentTimeMillis() + seconds * 1000L).toString())
            put("qr_code", qr)
            put("manual_code", code)
        })
    }
    delete("/api/smart_home/pairing") {
        if (bar.matter.factoryReset()) call.replyOk() else call.unavailable("Smart home unavailable")
    }
    get("/api/smart_home/switch") {
        val state = withContext(bar.device.dispatcher) { bar.smartHome.state }
            ?: return@get call.unavailable("Smart home unavailable")
        call.respondJson(buildJsonObject { put("state", state); put("startup", bar.settings.data.smartSwitchStartup) })
    }
    post("/api/smart_home/switch") {
        val body = call.jsonBody() as? JsonObject ?: return@post call.replyError(400, "Bad Request")
        val state = (body["state"] as? JsonPrimitive)?.booleanOrNull
        val startup = (body["startup"] as? JsonPrimitive)?.content
        if (state == null && startup == null) return@post call.replyError(400, "Bad Request")
        if (startup != null && startup !in setOf("off", "on", "toggle", "last")) return@post call.replyError(400, "Bad Request")
        if (state != null) {
            if (!bar.matter.available) return@post call.unavailable("Smart home unavailable")
            withContext(bar.device.dispatcher) { bar.smartHome.handleSwitch(state, fromMatter = false) }
        }
        if (startup != null) bar.settings.update(SettingsChange.OTHER) { it.copy(smartSwitchStartup = startup) }
        call.replyOk()
    }

    // ---- BUSY cloud account ----
    get("/api/account/info") { call.respondJson(buildJsonObject { put("linked", false) }) }
    get("/api/account/status") { call.respondJson(buildJsonObject { put("status", "disconnected") }) }
    post("/api/account/link") { call.unavailable("Cloud account linking is not supported") }
    delete("/api/account") { call.replyOk() }
    // No cloud backend is configured, and none can be.
    get("/api/account/backend") {
        call.respondJson(buildJsonObject { put("server_url", ""); put("client_cert_type", "none"); put("ignore_server_cert", false) })
    }
    put("/api/account/backend") { call.replyError(400, "Cloud account is not supported") }

    // ---- Firmware updates ----
    get("/api/update/status") {
        call.respondJson(buildJsonObject {
            putJsonObject("install") {
                put("is_allowed", false)
                put("event", "none")
                put("action", "none")
                put("status", "ok")
                put("detail", "")
                putJsonObject("download") { put("speed_bytes_per_sec", 0); put("received_bytes", 0); put("total_bytes", 0) }
            }
            putJsonObject("check") { put("available_version", ""); put("event", "stop"); put("status", "not_available") }
        })
    }
    // The check "runs" and finds nothing; the stream reports it as not available.
    post("/api/update/check") { call.replyOk() }
    get("/api/update/changelog") { call.replyError(400, "Version not found") }
    post("/api/update/install") {
        call.respondText(
            buildJsonObject { put("error", "No update available"); put("error_code", "not_available") }.toString(),
            ContentType.Application.Json, HttpStatusCode.BadRequest,
        )
    }
    post("/api/update/abort_download") { call.replyOk() }
    post("/api/update") { call.replyError(400, "Firmware updates are not supported; update the APK instead") }
    get("/api/update/autoupdate") {
        val s = bar.settings.data
        call.respondJson(buildJsonObject {
            put("is_enabled", s.autoUpdateEnabled)
            put("interval_start", s.autoUpdateStart)
            put("interval_end", s.autoUpdateEnd)
        })
    }
    post("/api/update/autoupdate") {
        val body = call.jsonBody() as? JsonObject ?: return@post call.replyError(400, "Bad Request")
        fun str(k: String) = (body[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val hhmm = Regex("^([0-1][0-9]|2[0-3]):[0-5][0-9]$")
        val start = str("interval_start")
        val end = str("interval_end")
        if (start != null && !hhmm.matches(start) || end != null && !hhmm.matches(end)) {
            return@post call.replyError(400, "Invalid time format (expected HH:MM)")
        }
        val enabled = (body["is_enabled"] as? JsonPrimitive)?.booleanOrNull
        bar.settings.update(SettingsChange.OTHER) {
            it.copy(
                autoUpdateEnabled = enabled ?: it.autoUpdateEnabled,
                autoUpdateStart = start ?: it.autoUpdateStart,
                autoUpdateEnd = end ?: it.autoUpdateEnd,
            )
        }
        call.replyOk()
    }
}

private suspend fun ApplicationCall.unavailable(message: String) = replyError(503, message)
