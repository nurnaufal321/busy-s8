package com.busys8.server

import com.busys8.engine.Busybar
import com.busys8.engine.system.AccessMode
import com.busys8.engine.system.ApiTokens
import com.busys8.engine.system.DeviceSettings
import com.busys8.engine.system.SettingsChange
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** /api/access and /api/access/tokens (api_root.c). */
fun Route.accessRoutes(bar: Busybar) {
    get("/api/access") {
        val s = bar.settings.data
        call.respondJson(buildJsonObject {
            put("mode", s.accessMode.api)
            put("key_valid", DeviceSettings.isValidKey(s.accessKey))
        })
    }

    post("/api/access") {
        val q = call.request.queryParameters
        val mode = AccessMode.fromApi(q["mode"].orEmpty()) ?: return@post call.replyError(400, "Bad Request")
        val key = q["key"]
        if (mode == AccessMode.KEY && key == null) return@post call.replyError(400, "Bad Request")
        if (key != null && !DeviceSettings.isValidKey(key)) return@post call.replyError(400, "Bad Request")
        bar.settings.update(SettingsChange.ACCESS) { it.copy(accessMode = mode, accessKey = key ?: it.accessKey) }
        call.replyOk()
    }

    get("/api/access/tokens") {
        call.respondJson(buildJsonObject {
            put("tokens", buildJsonArray { bar.tokens.list().forEach { add(tokenJson(it, null)) } })
        })
    }

    post("/api/access/tokens") {
        if (call.attributes.getOrNull(AccessGrantKey) == AccessGrant.TOKEN) return@post call.replyError(403, "Forbidden")
        val name = ((call.jsonBody() as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return@post call.replyError(400, "Bad Request")
        val minted = bar.tokens.mint(name)
        call.respondJson(tokenJson(minted.entry, minted.token))
    }

    delete("/api/access/tokens") {
        if (call.attributes.getOrNull(AccessGrantKey) == AccessGrant.TOKEN) return@delete call.replyError(403, "Forbidden")
        bar.tokens.revokeAll()
        call.replyOk()
    }

    delete("/api/access/tokens/{short_id}") {
        val id = call.parameters["short_id"].orEmpty()
        val own = call.attributes.getOrNull(AccessTokenKey)?.take(ApiTokens.SHORT_ID_LENGTH)
        if (call.attributes.getOrNull(AccessGrantKey) == AccessGrant.TOKEN && own != id) return@delete call.replyError(403, "Forbidden")
        if (bar.tokens.revoke(id)) call.replyOk() else call.replyError(404, "Not Found")
    }
}

private fun tokenJson(e: ApiTokens.Entry, fullToken: String?) = buildJsonObject {
    put("short_id", e.shortId)
    put("display_id", e.displayId)
    put("name", e.name)
    put("created_at", e.createdAt.toString())
    put("last_used_at", e.lastUsedAt.toString())
    fullToken?.let { put("token", it) }
}
