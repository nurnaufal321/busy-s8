package com.busys8.server

import com.busys8.engine.Busybar
import com.busys8.engine.jsapps.JsApp
import com.busys8.engine.jsapps.JsApps
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** /api/apps/... (http_api/api_apps.c): staging, installing, listing, launching, removing and settings. */
fun Route.appsRoutes(bar: Busybar) {
    val apps = bar.jsApps
    val onDevice = bar.device.dispatcher

    post("/api/apps/stage") {
        val bytes = call.bodyBytes() ?: return@post call.replyError(413, "Payload Too Large")
        if (bytes.isEmpty()) return@post call.replyError(400, "Bad Request")
        apps.stage(bytes).fold(
            onSuccess = { r ->
                call.respondJson(buildJsonObject {
                    put("result", "OK")
                    put("install_key", r.installKey)
                    put("staged", appJson(r.staged))
                    r.installed?.let { put("installed", appJson(it)) }
                })
            },
            onFailure = { e ->
                val error = (e as JsApps.InstallException).error
                call.respondText(
                    buildJsonObject { put("error", error.message); put("error_code", error.code) }.toString(),
                    ContentType.Application.Json, HttpStatusCode.BadRequest,
                )
            },
        )
    }

    post("/api/apps/install") {
        val key = call.request.queryParameters["install_key"]?.toLongOrNull() ?: return@post call.replyError(400, "Bad Request")
        if (apps.install(key)) call.replyOk() else call.replyError(400, "Bad Request")
    }

    get("/api/apps/list") {
        call.respondJson(buildJsonObject { put("apps", buildJsonArray { apps.list().forEach { add(appJson(it)) } }) })
    }

    delete("/api/apps") {
        val id = call.request.queryParameters["app_id"]?.takeIf { JsApps.ID.matches(it) } ?: return@delete call.replyError(400, "Bad Request")
        if (!apps.hasApp(id)) return@delete call.replyError(404, "Not Found")
        withContext(onDevice) { if (bar.launcher.runningAppId == id) bar.launcher.quit() }
        if (apps.remove(id)) call.replyOk() else call.replyError(404, "Not Found")
    }

    post("/api/apps/launch") {
        val id = call.request.queryParameters["app_id"]?.takeIf { JsApps.ID.matches(it) } ?: return@post call.replyError(400, "Bad Request")
        when (withContext(onDevice) { bar.launchApp(id) }) {
            Busybar.LaunchResult.OK -> call.replyOk()
            Busybar.LaunchResult.NOT_FOUND -> call.replyError(404, "Not Found")
            Busybar.LaunchResult.API_MISMATCH -> call.replyError(409, "API version mismatch, please update firmware")
            Busybar.LaunchResult.LOW_PRIORITY -> call.replyError(409, "not started due to low priority")
        }
    }

    post("/api/apps/quit") {
        if (withContext(onDevice) { bar.launcher.quit() }) call.replyOk() else call.replyError(409, "application is not running")
    }

    get("/api/apps/settings") {
        val id = call.request.queryParameters["app_id"]?.takeIf { JsApps.ID.matches(it) } ?: return@get call.replyError(400, "Bad Request")
        if (!apps.hasApp(id)) return@get call.replyError(404, "Not Found")
        // An app may only read its own settings, and only while it runs.
        if (call.request.isFromJs() && bar.launcher.runningAppId != id) return@get call.replyError(403, "Forbidden")
        val settings = apps.settings(id) ?: return@get call.replyError(404, "Not Found")
        call.respondJson(settings)
    }

    put("/api/apps/settings") {
        val id = call.request.queryParameters["app_id"]?.takeIf { JsApps.ID.matches(it) } ?: return@put call.replyError(400, "Bad Request")
        if (!apps.hasApp(id)) return@put call.replyError(404, "Not Found")
        val body = call.jsonBody() as? JsonObject ?: return@put call.replyError(400, "Bad Request")
        if (apps.setSettings(id, body)) call.replyOk() else call.replyError(400, "Bad Request")
    }

    delete("/api/apps/settings") {
        val id = call.request.queryParameters["app_id"]?.takeIf { JsApps.ID.matches(it) } ?: return@delete call.replyError(400, "Bad Request")
        if (apps.resetSettings(id)) call.replyOk() else call.replyError(404, "Not Found")
    }
}

private fun appJson(app: JsApp) = buildJsonObject {
    val m = app.manifest
    put("id", m.id)
    put("name", m.name)
    put("version", m.version)
    put("api_version", m.apiVersion)
    put("author", m.author)
    put("description", m.description)
    put("is_debug", m.isDebug)
    put("icon_path", app.iconPath)
}
