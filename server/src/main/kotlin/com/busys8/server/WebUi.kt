package com.busys8.server

import com.busys8.core.AssetSource
import io.ktor.http.ContentType
import io.ktor.server.request.path
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The firmware's bundled web UI (assets/frontend-build, a client-side Nuxt app) at `/`,
 * like the bar's static WEB_ROOT handler. Unknown paths get Nuxt's SPA fallback page.
 */
fun Route.webUi(assets: AssetSource) {
    get("/{path...}") {
        val path = call.request.path().trimStart('/')
        if (path.startsWith("api/")) return@get call.replyError(404, "Not Found")
        val candidates = when {
            path.isEmpty() -> listOf("index.html")
            path.endsWith("/") -> listOf("${path}index.html")
            else -> listOf(path, "$path/index.html")
        }
        val (name, bytes) = candidates.firstNotNullOfOrNull { c ->
            if (c.split('/').any { it == ".." }) null else assets.open("$ROOT/$c")?.use { c to it.readBytes() }
        } ?: ("200.html" to (assets.open("$ROOT/200.html")?.use { it.readBytes() } ?: return@get call.replyError(404, "Not Found")))
        call.response.headers.append("Cache-Control", if (name.startsWith("_nuxt/")) "public, max-age=31536000, immutable" else "no-cache")
        call.respondBytes(bytes, contentType(name))
    }
}

private const val ROOT = "frontend/public"

private fun contentType(name: String): ContentType = when (name.substringAfterLast('.').lowercase()) {
    "html" -> ContentType.Text.Html
    "js", "mjs" -> ContentType.parse("text/javascript")
    "css" -> ContentType.Text.CSS
    "json" -> ContentType.Application.Json
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    "ico" -> ContentType.parse("image/x-icon")
    "ttf" -> ContentType.parse("font/ttf")
    "woff2" -> ContentType.parse("font/woff2")
    "wasm" -> ContentType.parse("application/wasm")
    else -> ContentType.Application.OctetStream
}
