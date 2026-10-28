package com.busys8.server

import com.busys8.core.Argb
import com.busys8.core.Display
import com.busys8.engine.Busybar
import com.busys8.engine.system.SettingsChange
import com.busys8.engine.system.DeviceSettings
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Base64
import java.util.Locale

/** Firmware identity reported by /api/status/firmware: this emulator, tracking API 27.14.0. */
private const val FIRMWARE_VERSION = Busybar.FIRMWARE_VERSION
private const val FIRMWARE_TARGET = 28

suspend fun ApplicationCall.respondJson(json: JsonObject) = respondText(json.toString(), ContentType.Application.Json)

/** http_api/api_root.c, api_status.c, api_name.c, api_display.c (brightness), api_audio.c (volume), api_time.c, api_streaming.c. */
fun Route.systemRoutes(bar: Busybar) {
    val platform = bar.platform

    get("/api/version") { call.respondJson(buildJsonObject { put("api_semver", API_SEMVER) }) }

    get("/api/transport") {
        val usb = call.attributes.getOrNull(AccessGrantKey) == AccessGrant.LOCAL
        call.respondJson(buildJsonObject { put("type", if (usb) "usb" else "wifi") })
    }

    get("/api/status") {
        call.respondJson(buildJsonObject {
            put("device", statusDevice(bar))
            put("firmware", statusFirmware())
            put("system", statusSystem(bar))
            put("power", statusPower(bar))
        })
    }
    get("/api/status/device") { call.respondJson(statusDevice(bar)) }
    get("/api/status/firmware") { call.respondJson(statusFirmware()) }
    get("/api/status/system") { call.respondJson(statusSystem(bar)) }
    get("/api/status/power") { call.respondJson(statusPower(bar)) }

    get("/api/name") { call.respondJson(buildJsonObject { put("name", bar.settings.data.name) }) }
    post("/api/name") {
        val body = call.jsonBody() as? JsonObject
        val name = (body?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        // The schema allows no other properties.
        if (name == null || body.keys != setOf("name") || !DeviceSettings.NAME_PATTERN.matches(name)) {
            return@post call.replyError(400, "Bad Request")
        }
        bar.settings.update(SettingsChange.NAME) { it.copy(name = name) }
        call.replyOk()
    }

    get("/api/display/brightness") {
        call.respondJson(buildJsonObject { put("value", bar.settings.data.brightness?.toString() ?: "auto") })
    }
    post("/api/display/brightness") {
        val value = call.request.queryParameters["value"].orEmpty()
        val level = when {
            value == "auto" -> null
            value.toIntOrNull() in 0..100 && value.length <= 3 -> value.toInt()
            else -> return@post call.replyError(400, "Invalid brightness value")
        }
        bar.settings.update(SettingsChange.BRIGHTNESS) { it.copy(brightness = level) }
        platform.applyBrightness(level)
        call.replyOk()
    }

    get("/api/audio/volume") { call.respondJson(buildJsonObject { put("volume", platform.volume()) }) }
    post("/api/audio/volume") {
        val volume = call.request.queryParameters["volume"]?.toDoubleOrNull()
        if (volume == null || volume < 0 || volume > 100) return@post call.replyError(400, "Invalid volume value")
        platform.setVolume(volume.toInt())
        bar.settings.notifyChanged(SettingsChange.VOLUME)
        call.replyOk()
    }

    get("/api/time") {
        val now = ZonedDateTime.now(zone(platform.timeZone())).withNano(0)
        call.respondJson(buildJsonObject { put("timestamp", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)) })
    }
    post("/api/time/timestamp") {
        val raw = call.request.queryParameters["timestamp"].orEmpty()
        val epochMs = parseTimestamp(raw) ?: return@post call.replyError(400, "Invalid timestamp format")
        if (!platform.setTime(epochMs)) return@post call.replyError(400, "Setting the clock is not allowed on this device")
        call.replyOk()
    }
    get("/api/time/timezone") { call.respondJson(zoneInfo(platform.timeZone())) }
    post("/api/time/timezone") {
        val id = call.request.queryParameters["timezone"].orEmpty()
        if (!TIMEZONE_PATTERN.matches(id) || id !in ZoneId.getAvailableZoneIds() || !platform.setTimeZone(id)) {
            return@post call.replyError(400, "Invalid timezone")
        }
        bar.settings.notifyChanged(SettingsChange.TIMEZONE)
        call.replyOk()
    }
    get("/api/time/tzlist") {
        val zones = ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted()
        call.respondJson(buildJsonObject { put("list", buildJsonArray { zones.forEach { add(zoneInfo(it)) } }) })
    }

    post("/api/log_dump") {
        val name = call.request.queryParameters["filename"] ?: "log_${System.currentTimeMillis() / 1000}"
        if (!Regex("^[a-zA-Z0-9_-]+$").matches(name)) return@post call.replyError(400, "Invalid filename")
        val path = "/ext/logs/$name.log"
        val file = bar.device.storage.resolveExt(path) ?: return@post call.replyError(400, "Invalid filename")
        file.parentFile.mkdirs()
        file.writeText(logDump(bar))
        call.respondJson(buildJsonObject { put("result", "OK"); put("path", path) })
    }

    get("/api/screen") {
        val display = call.request.queryParameters["display"]
        val frame = bar.device.frames.value
        val bytes = when (display) {
            "0" -> frontBgr(frame.front)
            "1" -> backL4(frame.back)
            else -> return@get call.replyError(400, "Wrong display")
        }
        call.respondText(Base64.getEncoder().encodeToString(bytes), ContentType.parse("image/bmp"))
    }
}

private val TIMESTAMP_PATTERN = Regex("^20[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](Z|[+-]([01][0-9]|2[0-3])(:?[0-5][0-9])?)$")
private val TIMEZONE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9 _+\\-/]{0,50}$")

/**
 * ISO date-time with `Z` or an offset up to ±23:59 (the spec's pattern), to epoch ms. Parsed by
 * hand because java.time rejects offsets beyond ±18:00.
 */
fun parseTimestamp(raw: String): Long? {
    val m = TIMESTAMP_PATTERN.matchEntire(raw) ?: return null
    val local = runCatching { java.time.LocalDateTime.parse(raw.substring(0, 19)) }.getOrNull() ?: return null
    val zone = m.groupValues[4]
    val offsetMin = if (zone == "Z") 0 else {
        val digits = zone.drop(1).replace(":", "")
        val minutes = digits.take(2).toInt() * 60 + (digits.drop(2).toIntOrNull() ?: 0)
        if (zone[0] == '-') -minutes else minutes
    }
    return local.toEpochSecond(java.time.ZoneOffset.UTC) * 1000 - offsetMin * 60_000L
}

private fun zone(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.of("UTC"))

private fun zoneInfo(id: String): JsonObject {
    val z = zone(id)
    val now = Instant.now()
    val offset = z.rules.getOffset(now)
    val abbr = java.util.TimeZone.getTimeZone(z).getDisplayName(z.rules.isDaylightSavings(now), java.util.TimeZone.SHORT, Locale.ROOT)
    return buildJsonObject {
        put("name", id)
        put("offset", if (offset.totalSeconds == 0) "+00:00" else offset.id)
        put("abbr", abbr.ifEmpty { z.getDisplayName(TextStyle.SHORT, Locale.ROOT) })
    }
}

private fun statusDevice(bar: Busybar) = bar.platform.ids().let { ids ->
    buildJsonObject {
        put("serial_number", ids.serial)
        put("usb_mac", ids.usbMac)
        put("wifi_mac", ids.wifiMac)
        put("otp_valid", false)
        put("firmware_security", "unknown")
    }
}

private fun statusFirmware() = buildJsonObject {
    put("version", FIRMWARE_VERSION)
    put("target", FIRMWARE_TARGET)
    put("branch", "busy-s8")
    put("build_date", "2026-10-28")
    put("commit_hash", "4d1c24ac")
    put("intercom_version", "0.0.0")
}

/** api_status.c, including its uptime format whose hour field does not wrap at 24. */
private fun statusSystem(bar: Busybar) = buildJsonObject {
    val s = bar.platform.uptimeMs() / 1000
    put("api_semver", API_SEMVER)
    put("uptime", "%02dd %02dh %02dm %02ds".format(s / 86400, s / 3600, s / 60 % 60, s % 60))
    put("boot_time", bar.platform.bootTimeS())
    put("auto_update_enabled", bar.settings.data.autoUpdateEnabled)
}

private fun statusPower(bar: Busybar) = bar.platform.power().let { p ->
    buildJsonObject {
        put("state", p.state.api)
        put("battery_charge", p.chargePercent)
        put("battery_voltage", p.voltageMv)
        put("battery_current", p.currentMa)
        put("usb_voltage", p.usbVoltageMv)
        p.temperatureC?.let { put("battery_temperature", it) }
    }
}

private fun logDump(bar: Busybar): String = buildString {
    appendLine("BUSY S8 log dump ${Instant.now()}")
    appendLine("timer: ${bar.timer.state} running=${bar.timer.isRunning}")
    appendLine("switch: ${bar.desktop.switchPosition}")
    appendLine("settings: ${bar.settings.data}")
}

/** The front framebuffer as LVGL's RGB888, which is B, G, R in memory. */
fun frontBgr(argb: IntArray): ByteArray {
    val out = ByteArray(argb.size * 3)
    argb.forEachIndexed { i, c ->
        out[i * 3] = Argb.b(c).toByte()
        out[i * 3 + 1] = Argb.g(c).toByte()
        out[i * 3 + 2] = Argb.r(c).toByte()
    }
    return out
}

/** The back framebuffer as L4: two greyscale pixels per byte, the first in the low nibble. */
fun backL4(argb: IntArray): ByteArray {
    val out = ByteArray(Display.BACK.width * Display.BACK.height / 2)
    for (i in out.indices) {
        val a = Argb.luminance(argb[i * 2]) shr 4
        val b = Argb.luminance(argb[i * 2 + 1]) shr 4
        out[i] = (a or (b shl 4)).toByte()
    }
    return out
}
