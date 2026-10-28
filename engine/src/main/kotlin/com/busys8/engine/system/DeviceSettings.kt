package com.busys8.engine.system

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The web server's access mode (api_root.c). */
enum class AccessMode(val api: String) {
    DISABLED("disabled"), ENABLED("enabled"), KEY("key");

    companion object {
        fun fromApi(s: String) = entries.firstOrNull { it.api == s }
    }
}

/**
 * Device settings the API reads and writes, persisted as one JSON file. Access defaults to
 * ENABLED, unlike the firmware: a real bar is first unlocked over USB, which the phone lacks.
 */
@Serializable
data class SettingsData(
    val name: String = DEFAULT_NAME,
    /** null = automatic. */
    val brightness: Int? = null,
    val accessMode: AccessMode = AccessMode.ENABLED,
    val accessKey: String = "",
    val autoUpdateEnabled: Boolean = false,
    val autoUpdateStart: String = "02:00",
    val autoUpdateEnd: String = "05:00",
    val smartSwitchState: Boolean = false,
    val smartSwitchStartup: String = "last",
    val clock12h: Boolean = false,
    /** Blank the panel when the front camera has seen nobody for [awayAfterMin] minutes. */
    val presence: Boolean = false,
    val awayAfterMin: Int = 5,
    /** Start an ON CALL session while someone keeps talking (microphone, on the phone only). */
    val autoOnCall: Boolean = false,
    /** ble settings: off by default, as on the bar. */
    val bleEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_NAME = "BUSY Bar"
    }
}

/** What changed, for the status stream. */
enum class SettingsChange { NAME, BRIGHTNESS, VOLUME, TIMEZONE, ACCESS, BLE, OTHER }

class DeviceSettings(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listeners = ArrayList<(SettingsChange) -> Unit>()

    @Volatile var data: SettingsData = load()
        private set

    fun subscribe(listener: (SettingsChange) -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun update(change: SettingsChange, transform: (SettingsData) -> SettingsData) {
        data = transform(data)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(SettingsData.serializer(), data))
        tmp.renameTo(file)
        notifyChanged(change)
    }

    /** For changes that live outside this file (volume, time zone) but feed the same stream. */
    fun notifyChanged(change: SettingsChange) = listeners.toList().forEach { it(change) }

    private fun load(): SettingsData =
        runCatching { json.decodeFromString(SettingsData.serializer(), file.readText()) }.getOrDefault(SettingsData())

    companion object {
        /** validate_access_key: 4–10 digits. */
        fun isValidKey(key: String) = key.length in 4..10 && key.all { it.isDigit() }

        /** /api/name: up to 20 printable characters from the firmware's set. */
        val NAME_PATTERN = Regex("^[a-zA-Z0-9 !()_=+;:,.?'|@#$%^&*\\[\\]{}/\\\\\"<>-]{1,20}$")
    }
}

/**
 * services/api_tokens: 32-character tokens shown once at creation and stored as SHA-256 hashes.
 * The first 8 characters are the short id; the display id is `short…last6`.
 */
class ApiTokens(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable
    data class Entry(
        val shortId: String,
        val displayId: String,
        val name: String,
        val createdAt: Long,
        val lastUsedAt: Long,
        val tokenHash: String,
    )

    /** A freshly minted token, the only time the full value exists. */
    data class Minted(val entry: Entry, val token: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val entries = load().toMutableList()

    @Synchronized
    fun list(): List<Entry> = entries.toList()

    @Synchronized
    fun mint(name: String): Minted {
        val token = generate()
        val shortId = token.take(SHORT_ID_LENGTH)
        val now = clock() / 1000
        val entry = Entry(shortId, shortId + "…" + token.takeLast(DISPLAY_TAIL), name, now, 0, sha256(token))
        entries += entry
        save()
        return Minted(entry, token)
    }

    /** Returns true and records the use when [token] matches a stored hash. */
    @Synchronized
    fun validate(token: String): Boolean {
        val hash = sha256(token)
        val i = entries.indexOfFirst { it.tokenHash == hash }
        if (i < 0) return false
        entries[i] = entries[i].copy(lastUsedAt = clock() / 1000)
        save()
        return true
    }

    @Synchronized
    fun revoke(shortId: String): Boolean {
        val removed = entries.removeAll { it.shortId == shortId }
        if (removed) save()
        return removed
    }

    @Synchronized
    fun revokeAll() {
        entries.clear()
        save()
    }

    private fun generate(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return Base64.getEncoder().encodeToString(bytes).take(TOKEN_LENGTH).replace('+', 'A').replace('/', 'B')
    }

    private fun load(): List<Entry> = runCatching {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Entry.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Entry.serializer()), entries))
    }

    companion object {
        const val TOKEN_LENGTH = 32
        const val SHORT_ID_LENGTH = 8
        private const val DISPLAY_TAIL = 6

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
