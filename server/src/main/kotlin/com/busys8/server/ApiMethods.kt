package com.busys8.server

/**
 * Methods per API path, generated from vendor/openapi.yaml (v27.14.0). Used to answer unsupported
 * methods with 405 and an Allow header, as http_reply_405_method_not_allowed does.
 * Regenerate when the spec is re-synced.
 */
val API_METHODS: Map<String, Set<String>> = mapOf(
    "/api/access" to setOf("GET", "POST"),
    "/api/access/tokens" to setOf("GET", "POST", "DELETE"),
    "/api/access/tokens/{short_id}" to setOf("DELETE"),
    "/api/account" to setOf("DELETE"),
    "/api/account/backend" to setOf("GET", "PUT"),
    "/api/account/info" to setOf("GET"),
    "/api/account/link" to setOf("POST"),
    "/api/account/status" to setOf("GET"),
    "/api/apps" to setOf("DELETE"),
    "/api/apps/install" to setOf("POST"),
    "/api/apps/launch" to setOf("POST"),
    "/api/apps/list" to setOf("GET"),
    "/api/apps/quit" to setOf("POST"),
    "/api/apps/settings" to setOf("GET", "PUT", "DELETE"),
    "/api/apps/stage" to setOf("POST"),
    "/api/assets/upload" to setOf("POST", "DELETE"),
    "/api/audio/play" to setOf("POST", "DELETE"),
    "/api/audio/volume" to setOf("GET", "POST"),
    "/api/ble/disable" to setOf("POST"),
    "/api/ble/enable" to setOf("POST"),
    "/api/ble/pairing" to setOf("DELETE"),
    "/api/ble/status" to setOf("GET"),
    "/api/busy/profiles/{slot}" to setOf("GET", "PUT"),
    "/api/busy/snapshot" to setOf("GET", "PUT"),
    "/api/display/brightness" to setOf("GET", "POST"),
    "/api/display/draw" to setOf("POST", "DELETE"),
    "/api/input" to setOf("POST"),
    "/api/log_dump" to setOf("POST"),
    "/api/name" to setOf("GET", "POST"),
    "/api/screen" to setOf("GET"),
    "/api/smart_home/pairing" to setOf("GET", "POST", "DELETE"),
    "/api/smart_home/switch" to setOf("GET", "POST"),
    "/api/status" to setOf("GET"),
    "/api/status/device" to setOf("GET"),
    "/api/status/firmware" to setOf("GET"),
    "/api/status/power" to setOf("GET"),
    "/api/status/system" to setOf("GET"),
    "/api/status/ws" to setOf("GET"),
    "/api/storage/list" to setOf("GET"),
    "/api/storage/mkdir" to setOf("POST"),
    "/api/storage/read" to setOf("GET"),
    "/api/storage/remove" to setOf("DELETE"),
    "/api/storage/rename" to setOf("POST"),
    "/api/storage/status" to setOf("GET"),
    "/api/storage/write" to setOf("POST"),
    "/api/time" to setOf("GET"),
    "/api/time/timestamp" to setOf("POST"),
    "/api/time/timezone" to setOf("GET", "POST"),
    "/api/time/tzlist" to setOf("GET"),
    "/api/transport" to setOf("GET"),
    "/api/update" to setOf("POST"),
    "/api/update/abort_download" to setOf("POST"),
    "/api/update/autoupdate" to setOf("GET", "POST"),
    "/api/update/changelog" to setOf("GET"),
    "/api/update/check" to setOf("POST"),
    "/api/update/install" to setOf("POST"),
    "/api/update/status" to setOf("GET"),
    "/api/version" to setOf("GET"),
    "/api/wifi/connect" to setOf("POST"),
    "/api/wifi/disconnect" to setOf("POST"),
    "/api/wifi/networks" to setOf("GET"),
    "/api/wifi/status" to setOf("GET"),
)

private val TEMPLATES = API_METHODS.keys.associateWith { t ->
    Regex("^" + t.split('/').joinToString("/") { seg -> if (seg.startsWith("{")) "[^/]+" else Regex.escape(seg) } + "$")
}

/** The spec's methods for a concrete request path, or null if the path is not in the API. */
fun allowedMethods(path: String): Set<String>? =
    TEMPLATES.entries.firstOrNull { it.value.matches(path.trimEnd('/')) }?.let { API_METHODS.getValue(it.key) }
