package com.busys8.server

import com.busys8.engine.Busybar
import com.busys8.engine.input.InputKey
import com.busys8.engine.timer.ProfileSlot
import com.busys8.engine.timer.TimerJson
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.withContext

/** /api/busy/... (http_api/api_busy.c). Timer state lives on the device thread. */
fun Route.busyRoutes(bar: Busybar) {
    val timer = bar.timer
    val onDevice = bar.device.dispatcher

    get("/api/busy/snapshot") {
        val json = withContext(onDevice) { TimerJson.snapshot(timer.lastKnownSnapshot) }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    put("/api/busy/snapshot") {
        val snapshot = TimerJson.parseSnapshot(call.jsonBody(), System.currentTimeMillis())
            ?: return@put call.replyError(400, "Failed to parse snapshot")
        withContext(onDevice) { timer.setSnapshot(snapshot) }
        call.replyOk()
    }

    get("/api/busy/profiles/{slot}") {
        val slot = ProfileSlot.fromPath(call.parameters["slot"].orEmpty())
            ?: return@get call.replyError(400, "Invalid profile slot name")
        val json = withContext(onDevice) { TimerJson.profile(timer.profile(slot)) }
        call.respondText(json.toString(), ContentType.Application.Json)
    }

    put("/api/busy/profiles/{slot}") {
        val slot = ProfileSlot.fromPath(call.parameters["slot"].orEmpty())
            ?: return@put call.replyError(400, "Invalid profile slot name")
        val profile = TimerJson.parseProfile(call.jsonBody())
            ?: return@put call.replyError(400, "Failed to parse profile")
        withContext(onDevice) { timer.setProfile(slot, profile) }
        call.replyOk()
    }
}

/** POST /api/input?key=... (http_api/api_input.c): a press and release of one key. */
fun Route.inputRoutes(bar: Busybar) {
    post("/api/input") {
        val key = InputKey.fromApi(call.request.queryParameters["key"].orEmpty())
            ?: return@post call.replyError(400, "Bad Request")
        withContext(bar.device.dispatcher) { bar.input.toggle(key) }
        call.replyOk()
    }
}
