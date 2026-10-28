package com.busys8.engine.jsapps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream

/** appmeta/manifest.json (JavaScript Applications.dox.md, appendix 1). */
data class AppManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: String,
    val author: String,
    val description: String,
    val heapSizeKib: Int,
    val isDebug: Boolean,
)

/** An installed (or staged) app: its manifest and where it lives. */
data class JsApp(val manifest: AppManifest, val dir: File, val devicePath: String) {
    val iconPath get() = "$devicePath/appmeta/icon_front_8x8.png"
}

/** Why staging failed (JsAppInstallerError), with the firmware's message and code. */
enum class InstallError(val message: String, val code: String) {
    UNPACK("Cannot unpack", "unpack_error"),
    MANIFEST("Wrong app manifest", "manifest_error"),
    API_VERSION("API version mismatch", "api_mismatch"),
    INSTALL("Installation failed", "installation_failed"),
}

class StageResult(val installKey: Long, val staged: JsApp, val installed: JsApp?)

/**
 * The JS app registry and installer (services/js_app_installer, lib/js_app): apps live in
 * `/ext/user_assets/<id>/`, packages are tar or tar.gz archives with one `<id>/` folder, and
 * installation is two-step: stage (validate, returns a key), then install with that key.
 */
class JsApps(private val userAssets: File, private val dataDir: File, private val apiVersion: String) {
    private val staging = File(dataDir, "installer/staging")
    private var stagedKey = 0L
    private val random = SecureRandom()

    fun list(): List<JsApp> = userAssets.listFiles()?.filter { it.isDirectory }?.mapNotNull(::load)?.sortedBy { it.manifest.id }.orEmpty()

    fun get(id: String): JsApp? = File(userAssets, id).takeIf { ID.matches(id) }?.let(::load)

    @Synchronized
    fun stage(archive: ByteArray): Result<StageResult> {
        staging.deleteRecursively()
        staging.mkdirs()
        stagedKey = 0
        if (!untar(archive, staging)) return failure(InstallError.UNPACK)
        val folder = staging.listFiles()?.firstOrNull { it.isDirectory && File(it, MANIFEST).isFile }
            ?: return failure(InstallError.MANIFEST)
        val app = load(folder) ?: return failure(InstallError.MANIFEST)
        if (!isCompatible(app.manifest.apiVersion)) return failure(InstallError.API_VERSION)
        stagedKey = generateSequence { random.nextInt().toLong() and 0x7FFFFFFF }.first { it != 0L }
        val installed = get(app.manifest.id)
        return Result.success(StageResult(stagedKey, app.copy(devicePath = "/ext/user_assets/${app.manifest.id}"), installed))
    }

    @Synchronized
    fun install(key: Long): Boolean {
        if (key == 0L || key != stagedKey) return false
        stagedKey = 0
        val folder = staging.listFiles()?.firstOrNull { it.isDirectory && File(it, MANIFEST).isFile } ?: return false
        val id = load(folder)?.manifest?.id ?: return false
        val target = File(userAssets, id)
        // Keep uploaded assets of a previous version only if the package does not replace them.
        target.deleteRecursively()
        userAssets.mkdirs()
        val moved = folder.renameTo(target) || folder.copyRecursively(target, overwrite = true)
        staging.deleteRecursively()
        return moved
    }

    fun remove(id: String): Boolean {
        val dir = File(userAssets, id)
        if (!ID.matches(id) || !File(dir, MANIFEST).isFile) return false
        settingsFile(id).delete()
        return dir.deleteRecursively()
    }

    // ---- settings (appmeta/settings.json + stored values) ----

    /** `{version, values}` with defaults filled in, or null when the app has no settings file. */
    fun settings(id: String): JsonObject? {
        val schema = schema(id) ?: return null
        val version = schema["version"]?.jsonPrimitive?.intOrNull ?: 1
        val stored = storedValues(id, version)
        return buildJsonObject {
            put("version", JsonPrimitive(version))
            put("values", merge(defaults(schema["fields"] as? JsonObject), stored))
        }
    }

    /** Saves values for the schema's current version; false if the version or a value is wrong. */
    fun setSettings(id: String, body: JsonObject): Boolean {
        val schema = schema(id) ?: return false
        val version = schema["version"]?.jsonPrimitive?.intOrNull ?: 1
        if (body["version"]?.jsonPrimitive?.intOrNull != version) return false
        val values = body["values"] as? JsonObject ?: return false
        if (!valid(schema["fields"] as? JsonObject, values)) return false
        settingsFile(id).apply { parentFile.mkdirs() }.writeText(buildJsonObject {
            put("version", JsonPrimitive(version))
            put("values", values)
        }.toString())
        return true
    }

    fun resetSettings(id: String): Boolean {
        if (schema(id) == null) return false
        settingsFile(id).delete()
        return true
    }

    fun hasApp(id: String) = get(id) != null

    private fun schema(id: String): JsonObject? =
        get(id)?.let { File(it.dir, "appmeta/settings.json") }?.takeIf { it.isFile }
            ?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }

    private fun storedValues(id: String, version: Int): JsonObject? = runCatching {
        val o = Json.parseToJsonElement(settingsFile(id).readText()).jsonObject
        o["values"]?.jsonObject?.takeIf { o["version"]?.jsonPrimitive?.int == version }
    }.getOrNull()

    private fun settingsFile(id: String) = File(dataDir, "app_settings/$id.json")

    private fun defaults(fields: JsonObject?): JsonObject = buildJsonObject {
        fields?.forEach { (key, node) ->
            val n = node as? JsonObject ?: return@forEach
            if (n["type"]?.jsonPrimitive?.content == "group") put(key, defaults(n["fields"] as? JsonObject))
            else n["default"]?.let { put(key, it) }
        }
    }

    private fun merge(base: JsonObject, over: JsonObject?): JsonObject = buildJsonObject {
        base.forEach { (k, v) ->
            val o = over?.get(k)
            put(k, if (v is JsonObject && o is JsonObject) merge(v, o) else o ?: v)
        }
    }

    /** Light validation: known keys only, and booleans/integers/strings where the schema says so. */
    private fun valid(fields: JsonObject?, values: JsonObject): Boolean = values.all { (k, v) ->
        val node = fields?.get(k) as? JsonObject ?: return@all false
        when (node["type"]?.jsonPrimitive?.content) {
            "group" -> v is JsonObject && valid(node["fields"] as? JsonObject, v)
            "boolean" -> (v as? JsonPrimitive)?.booleanOrNull != null && !v.isString
            "integer" -> (v as? JsonPrimitive)?.intOrNull?.let { i ->
                val min = node["min"]?.jsonPrimitive?.intOrNull ?: Int.MIN_VALUE
                val max = node["max"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
                !v.isString && i in min..max
            } ?: false
            "string", "enum", "color", "time" -> (v as? JsonPrimitive)?.isString == true
            else -> true
        }
    }

    private fun load(dir: File): JsApp? {
        val o = runCatching { Json.parseToJsonElement(File(dir, MANIFEST).readText()).jsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = str("id")?.takeIf { ID.matches(it) && it == dir.name } ?: return null
        val version = str("version")?.takeIf { SEMVER.matches(it) } ?: return null
        val api = str("api_version")?.takeIf { SEMVER.matches(it) } ?: return null
        val name = str("name") ?: return null
        if (o["format_version"] == null) return null
        val manifest = AppManifest(
            id, name, version, api, str("author").orEmpty(), str("description").orEmpty(),
            (o["heap_size_kib"] as? JsonPrimitive)?.intOrNull ?: 128,
            (o["debug"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
        return JsApp(manifest, dir, "/ext/user_assets/$id")
    }

    /** The firmware only checks that the app does not need a newer API than it has. */
    fun isCompatible(required: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
        val need = parts(required)
        val have = parts(apiVersion)
        for (i in 0 until 3) if (need[i] != have[i]) return need[i] < have[i]
        return true
    }

    private fun <T> failure(e: InstallError): Result<T> = Result.failure(InstallException(e))

    class InstallException(val error: InstallError) : Exception(error.message)

    companion object {
        const val MANIFEST = "appmeta/manifest.json"
        val ID = Regex("^[a-zA-Z0-9_\\-][a-zA-Z0-9_\\-.]{0,31}$")
        private val SEMVER = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

        /** Extracts a tar or tar.gz archive into [into]; refuses entries that escape it. */
        fun untar(archive: ByteArray, into: File): Boolean = runCatching {
            val gz = archive.size > 2 && archive[0] == 0x1f.toByte() && archive[1] == 0x8b.toByte()
            val input: InputStream = if (gz) GZIPInputStream(ByteArrayInputStream(archive)) else ByteArrayInputStream(archive)
            input.use { readTar(it, into) }
        }.getOrDefault(false)

        /** InputStream.readNBytes, which Android 9 lacks: up to [n] bytes, fewer only at the end. */
        private fun readUpTo(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input.read(buf, got, n - got)
                if (r < 0) break
                got += r
            }
            return if (got == n) buf else buf.copyOf(got)
        }

        private fun readTar(input: InputStream, into: File): Boolean {
            val root = into.canonicalPath
            var longName: String? = null
            var paxPath: String? = null
            var entries = 0
            while (true) {
                val header = readUpTo(input, 512)
                if (header.size < 512 || header.all { it == 0.toByte() }) break
                fun field(off: Int, len: Int) = String(header, off, len, Charsets.UTF_8).trimEnd('\u0000', ' ')
                val size = field(124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val prefix = if (field(257, 6).startsWith("ustar")) field(345, 155) else ""
                val name = longName ?: paxPath ?: listOf(prefix, field(0, 100)).filter { it.isNotEmpty() }.joinToString("/")
                val data = readUpTo(input, size.toInt())
                readUpTo(input, ((512 - size % 512) % 512).toInt())
                when (type) {
                    'L' -> { longName = String(data, Charsets.UTF_8).trimEnd('\u0000'); continue }
                    'x' -> {
                        paxPath = String(data, Charsets.UTF_8).lines().firstNotNullOfOrNull { l ->
                            l.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.removePrefix("path=")
                        }
                        continue
                    }
                    'g' -> continue
                }
                longName = null
                paxPath = null
                val clean = name.removePrefix("./")
                if (clean.isEmpty() || clean.startsWith("._") || clean.contains("/._")) continue
                val out = File(into, clean).canonicalFile
                if (!out.path.startsWith(root)) return false
                when (type) {
                    '5' -> out.mkdirs()
                    '0', '\u0000', '7' -> { out.parentFile.mkdirs(); out.writeBytes(data); entries++ }
                }
            }
            return entries > 0
        }
    }
}
