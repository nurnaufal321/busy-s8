package com.busys8.server

import com.busys8.core.AssetSource
import com.busys8.engine.Busybar
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The BUSY Bar HTTP API (web_server/http_api/) served from the phone. */
class BusyServer(private val bar: Busybar, private val assets: AssetSource, private val port: Int = DEFAULT_PORT) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { module(bar, assets) }.start(wait = false)
    }

    fun stop() {
        engine?.stop(500, 2000)
        engine = null
    }

    companion object {
        /** Unprivileged apps cannot bind :80 on Android; see DEVICE_SETUP.md. */
        const val DEFAULT_PORT = 8080
    }
}

/** CORS headers on every reply and 200 for preflights, as web_server_i.h does. */
private val FirmwareCors = createApplicationPlugin("FirmwareCors") {
    onCall { call ->
        call.response.headers.append("Access-Control-Allow-Origin", "*")
        call.response.headers.append("Access-Control-Allow-Headers", "*")
        if (call.request.httpMethod == HttpMethod.Options) {
            call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE")
            call.respondText("", status = HttpStatusCode.OK)
        }
    }
}

fun Application.module(bar: Busybar, assets: AssetSource) {
    val device = bar.device
    install(FirmwareCors)
    routing {
        busyRoutes(bar)
        inputRoutes(bar)

        get("/openapi.yaml") {
            val spec = assets.open("openapi.yaml")?.use { it.readBytes() }
            if (spec == null) call.replyError(404, "Not Found")
            else call.respondBytes(spec, ContentType.parse("application/yaml"))
        }

        post("/api/display/draw") {
            val (code, error) = device.draw(call.jsonBody())
            call.reply(code, error)
        }

        delete("/api/display/draw") {
            val body = call.receiveText()
            val parsed = if (body.isBlank()) null else runCatching { Json.parseToJsonElement(body) }.getOrNull()
            if (body.isNotBlank() && parsed == null) return@delete call.replyError(400, "body contains malformed JSON")
            if (parsed != null && parsed !is JsonObject) return@delete call.replyError(400, "body JSON must be an object")
            val obj = parsed as JsonObject?
            val ids = when (val raw = obj?.get("element_ids")) {
                null -> null
                is JsonArray -> raw.map {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                        ?: return@delete call.replyError(400, "element_ids must be an array of strings")
                }
                else -> return@delete call.replyError(400, "element_ids must be an array of strings")
            }
            val bodyApp = when (val raw = obj?.get("application_name")) {
                null -> null
                is JsonPrimitive -> raw.takeIf { it.isString }?.content
                    ?: return@delete call.replyError(400, "application_name must be a string")
                else -> return@delete call.replyError(400, "application_name must be a string")
            }
            val queryApp = call.request.queryParameters["application_name"]?.takeIf { it.isNotEmpty() }
            if (queryApp != null && bodyApp != null) {
                return@delete call.replyError(
                    400, "application_name present in both the body and query parameters; exactly one is required",
                )
            }
            val (code, error) = device.clear(queryApp ?: bodyApp, ids)
            call.reply(code, error)
        }
    }
}

internal suspend fun ApplicationCall.jsonBody(): JsonElement? =
    runCatching { Json.parseToJsonElement(receiveText()) }.getOrNull()

internal suspend fun ApplicationCall.reply(code: Int, error: String?) =
    if (error == null && code == 200) replyOk() else replyError(code, error ?: "failed")

internal suspend fun ApplicationCall.replyOk() =
    respondText("{\"result\":\"OK\"}\n", ContentType.Application.Json)

internal suspend fun ApplicationCall.replyError(code: Int, message: String) = respondText(
    buildJsonObject { put("error", message) }.toString() + "\n",
    ContentType.Application.Json,
    HttpStatusCode.fromValue(code),
)
