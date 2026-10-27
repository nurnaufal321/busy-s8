package com.busys8.server

import com.busys8.engine.Busybar
import com.busys8.engine.system.AccessMode
import com.busys8.engine.system.ApiTokens
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey

/** How a request was let in (HttpApiAccessStatus); token holders may not manage other tokens. */
enum class AccessGrant { ALLOWLIST, LOCAL, OPEN, PASSWORD, TOKEN }

val AccessGrantKey = AttributeKey<AccessGrant>("AccessGrant")
val AccessTokenKey = AttributeKey<String>("AccessToken")

/** Header JS apps' fetch adds (js_runner); requests carrying it are limited to the JS allowlist. */
const val JS_FETCH_HEADER = "X-BUSY-JS-App"

/** API version reported by /api/version; requests may pin the major via X-API-Sem-Ver. */
const val API_SEMVER = Busybar.API_VERSION
private val API_MAJOR = API_SEMVER.substringBefore('.').toInt()

private val ALWAYS_ALLOWED = listOf("version" to setOf(HttpMethod.Get), "access" to setOf(HttpMethod.Get), "transport" to setOf(HttpMethod.Get))

/** js_apps_api_allowlist: (path, exact match?, methods). */
private val JS_ALLOWED = listOf(
    Triple("apps/settings", true, setOf(HttpMethod.Get)),
    Triple("display/brightness", true, setOf(HttpMethod.Get, HttpMethod.Post)),
    Triple("display/draw", true, setOf(HttpMethod.Post, HttpMethod.Delete)),
    Triple("audio/play", true, setOf(HttpMethod.Post, HttpMethod.Delete)),
    Triple("busy", false, setOf(HttpMethod.Get, HttpMethod.Put)),
    Triple("name", true, setOf(HttpMethod.Get)),
    Triple("audio/volume", false, setOf(HttpMethod.Get, HttpMethod.Post)),
    Triple("time", false, setOf(HttpMethod.Get)),
    Triple("wifi/status", true, setOf(HttpMethod.Get)),
    Triple("version", true, setOf(HttpMethod.Get)),
    Triple("access", true, setOf(HttpMethod.Get)),
    Triple("transport", true, setOf(HttpMethod.Get)),
    Triple("status", true, setOf(HttpMethod.Get)),
    Triple("status/device", true, setOf(HttpMethod.Get)),
    Triple("status/firmware", true, setOf(HttpMethod.Get)),
    Triple("status/system", true, setOf(HttpMethod.Get)),
    Triple("status/power", true, setOf(HttpMethod.Get)),
)

/**
 * http_api_access_status + http_api_is_version_allowed (api_root.c) for everything under /api.
 * Loopback stands in for the bar's USB link: adb port-forwards and on-device JS apps come in
 * on 127.0.0.1 and are always trusted, as USB is on a real bar.
 */
fun Application.installFirmwareAuth(bar: Busybar) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (!path.startsWith("/api/") || call.request.httpMethod == HttpMethod.Options) return@intercept
        val api = path.removePrefix("/api/").trimEnd('/')
        val grant = grant(call, api, bar)
        if (grant == null) {
            call.replyError(403, "Forbidden")
            return@intercept finish()
        }
        call.attributes.put(AccessGrantKey, grant)
        val semver = call.request.header("X-API-Sem-Ver") ?: call.request.queryParameters["x-api-sem-ver"]
        if (semver != null && api != "version") {
            val major = semver.substringBefore('.').toIntOrNull()
            if (major == null || major != API_MAJOR) {
                if (major == null) call.replyError(400, "Bad Request") else call.replyError(405, "Incompatible API version")
                return@intercept finish()
            }
        }
    }
}

private fun grant(call: ApplicationCall, api: String, bar: Busybar): AccessGrant? {
    val method = call.request.httpMethod
    if (ALWAYS_ALLOWED.any { (p, m) -> p == api && method in m }) return AccessGrant.ALLOWLIST
    if (call.request.header(JS_FETCH_HEADER) != null && JS_ALLOWED.none { (p, exact, m) -> method in m && (api == p || !exact && api.startsWith("$p/")) }) {
        return null
    }
    if (isLoopback(call.request.local.remoteAddress)) return AccessGrant.LOCAL
    val settings = bar.settings.data
    when (settings.accessMode) {
        AccessMode.DISABLED -> return null
        AccessMode.ENABLED -> return AccessGrant.OPEN
        AccessMode.KEY -> Unit
    }
    val token = (call.request.header("X-API-Token") ?: call.request.queryParameters["x-api-token"]).orEmpty()
    if (token.isEmpty() || token.length > ApiTokens.TOKEN_LENGTH) return null
    if (settings.accessKey.isNotEmpty() && token == settings.accessKey) return AccessGrant.PASSWORD
    if (bar.tokens.validate(token)) {
        call.attributes.put(AccessTokenKey, token)
        return AccessGrant.TOKEN
    }
    return null
}

private fun isLoopback(address: String) = address == "127.0.0.1" || address == "::1" || address == "0:0:0:0:0:0:0:1" || address == "localhost"
