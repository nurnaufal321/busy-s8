package com.busys8.server

import com.busys8.core.DeviceStorage
import com.busys8.core.DrawRequestParser
import com.busys8.engine.Busybar
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Largest upload accepted (storage, assets, app packages). */
const val MAX_UPLOAD_BYTES = 32L * 1024 * 1024

private val STORAGE_PATH = Regex("^/ext(/[a-zA-Z0-9._\\-]*)*$")
private val APP_NAME = Regex("^[a-zA-Z0-9._-]{1,32}$")
private val ASSET_PATH = Regex("^[a-zA-Z0-9._/-]{1,64}$")

/** Reads the request body, or null when it exceeds [limit]. */
suspend fun ApplicationCall.bodyBytes(limit: Long = MAX_UPLOAD_BYTES): ByteArray? {
    val bytes = receiveChannel().readRemaining(limit + 1).readByteArray()
    return bytes.takeIf { it.size <= limit }
}

/** /api/storage/... (api_storage.c) over the phone's sandboxed /ext, with bundled assets read-only. */
fun Route.storageRoutes(bar: Busybar) {
    val storage = bar.device.storage

    fun ApplicationCall.pathParam(name: String = "path"): String? =
        request.queryParameters[name]?.takeIf { STORAGE_PATH.matches(it) && DrawRequestParser.isSanePath(it) }

    get("/api/storage/read") {
        val path = call.pathParam() ?: return@get call.replyError(400, "Invalid path")
        val bytes = storage.read(path) ?: return@get call.replyError(400, "File not found")
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    get("/api/storage/list") {
        val path = call.pathParam() ?: return@get call.replyError(400, "Invalid path")
        val entries = storage.list(path) ?: return@get call.replyError(400, "Directory not found")
        call.respondJson(buildJsonObject {
            put("list", buildJsonArray {
                entries.forEach { (name, isDir) -> add(buildJsonObject { put("type", if (isDir) "dir" else "file"); put("name", name) }) }
            })
        })
    }

    post("/api/storage/write") {
        val path = call.pathParam() ?: return@post call.replyError(400, "Invalid path")
        val file = writableFile(storage, path) ?: return@post call.replyError(400, "Path is read-only")
        val bytes = call.bodyBytes() ?: return@post call.replyError(413, "File too large")
        file.parentFile?.mkdirs()
        if (call.request.queryParameters["append"] == "1") file.appendBytes(bytes) else file.writeBytes(bytes)
        bar.device.images.invalidate(path)
        call.replyOk()
    }

    delete("/api/storage/remove") {
        val path = call.pathParam() ?: return@delete call.replyError(400, "Invalid path")
        val file = writableFile(storage, path)?.takeIf { it.exists() } ?: return@delete call.replyError(400, "File not found")
        if (!file.deleteRecursively()) return@delete call.replyError(400, "Failed to remove")
        call.replyOk()
    }

    post("/api/storage/mkdir") {
        val path = call.pathParam() ?: return@post call.replyError(400, "Invalid path")
        val dir = writableFile(storage, path) ?: return@post call.replyError(400, "Path is read-only")
        if (dir.exists() && !dir.isDirectory) return@post call.replyError(400, "File exists")
        if (!dir.isDirectory && !dir.mkdirs()) return@post call.replyError(400, "Failed to create directory")
        call.replyOk()
    }

    post("/api/storage/rename") {
        val from = call.pathParam() ?: return@post call.replyError(400, "Invalid path")
        val to = call.pathParam("new_path") ?: return@post call.replyError(400, "Invalid new_path")
        val src = writableFile(storage, from)?.takeIf { it.exists() } ?: return@post call.replyError(400, "File not found")
        val dst = writableFile(storage, to)?.takeIf { !it.exists() } ?: return@post call.replyError(400, "Destination exists")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) return@post call.replyError(400, "Failed to rename")
        call.replyOk()
    }

    get("/api/storage/status") {
        val root = storage.extRoot
        call.respondJson(buildJsonObject {
            put("used_bytes", root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            put("free_bytes", root.usableSpace)
            put("total_bytes", root.totalSpace)
        })
    }

    post("/api/assets/upload") {
        val app = call.request.queryParameters["application_name"]?.takeIf { APP_NAME.matches(it) }
            ?: return@post call.replyError(400, "Invalid application_name")
        val name = call.request.queryParameters["file"]?.takeIf { ASSET_PATH.matches(it) && DrawRequestParser.isSanePath(it) }
            ?: return@post call.replyError(400, "Invalid file")
        val path = DeviceStorage.userAssets(app, name)
        val file = storage.resolveExt(path) ?: return@post call.replyError(400, "Invalid file")
        val bytes = call.bodyBytes() ?: return@post call.replyError(413, "File too large")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        bar.device.images.invalidate(path)
        call.replyOk()
    }

    delete("/api/assets/upload") {
        val app = call.request.queryParameters["application_name"]?.takeIf { APP_NAME.matches(it) }
            ?: return@delete call.replyError(400, "Invalid application_name")
        storage.resolveExt(DeviceStorage.userAssets(app, ""))?.deleteRecursively()
        call.replyOk()
    }

    post("/api/audio/play") {
        val body = call.jsonBody() as? JsonObject ?: return@post call.replyError(400, "Invalid request body")
        fun str(k: String) = (body[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val app = str("application_name")?.takeIf { APP_NAME.matches(it) } ?: return@post call.replyError(400, "Missing application_name")
        val path = str("path")
        val stock = str("stock_path")
        val devicePath = when {
            path != null && stock == null && ASSET_PATH.matches(path) && DrawRequestParser.isSanePath(path) -> DeviceStorage.userAssets(app, path)
            stock != null && path == null && stock.startsWith("shared/") -> "${DeviceStorage.SHARED}sounds/${stock.substringAfterLast('/')}"
            else -> return@post call.replyError(400, ".{path,stock_path}: exactly one of these must be present")
        }
        val bytes = storage.read(devicePath) ?: return@post call.replyError(404, "Audio file not found")
        bar.platform.playSound(toWav(bytes))
        call.replyOk()
    }

    delete("/api/audio/play") {
        if (bar.platform.stopSound()) call.replyOk() else call.replyError(410, "Nothing is playing")
    }
}

/** A real file under /ext that may be modified; bundled assets are read-only. */
private fun writableFile(storage: DeviceStorage, path: String): File? =
    if (storage.isBundled(path) || path.trimEnd('/') == "/ext") null else storage.resolveExt(path)

/** `.snd` is headerless 16-bit little-endian mono PCM at 44.1 kHz (scripts/audio.py); WAV passes through. */
fun toWav(bytes: ByteArray): ByteArray {
    if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") return bytes
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + bytes.size); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(SND_RATE); putInt(SND_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(bytes.size)
    }
    return header.array() + bytes
}

private const val SND_RATE = 44_100
