package com.busys8.engine.timer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * BUSY timer data model and its JSON form, ported from busy_timer_common.c,
 * busy_timer_snapshot.c and busy_timer_profile.c. Key order and validation match the
 * firmware, so `/api/busy/...` bodies are byte-compatible with a real bar.
 */
object TimerLimits {
    const val MIN_MS = 60_000L
    const val TIME_MIN_MN = 5
    const val TIME_MAX_MN = 24 * 60
    const val TIME_INCREMENT_MN = 5
    const val WORK_MIN_MN = 5
    const val WORK_MAX_MN = 8 * 60
    const val REST_MIN_MN = 5
    const val REST_MAX_MN = 8 * 60
    const val CYCLES_MIN = 2
    const val CYCLES_MAX = 35
    const val WORK_DEFAULT_MN = 20
    const val REST_DEFAULT_MN = 5
    const val CYCLES_DEFAULT = 3
    const val CARD_ID_LEN = 36
    const val TITLE_LEN = 32 * 4
    const val THEME_LEN = 64

    /** busy_timer_snapshot.c TIMESTAMP_TOLERANCE_MS: snapshots may be at most 1 min in the future. */
    const val TIMESTAMP_TOLERANCE_MS = 60_000L
}

/** BusyAppConfig: which theme the BUSY app shows, and its behaviour flags. */
data class AppConfig(val theme: String, val showWorkOnly: Boolean, val smartHome: Boolean) {
    companion object {
        const val THEME_DEFAULT = "busy"
        const val THEME_CUSTOM_DEFAULT = "keep_out"

        /** Zeroed struct, as the firmware stores a profile that arrived without settings. */
        val EMPTY = AppConfig("", showWorkOnly = false, smartHome = false)
        val SNAPSHOT_FALLBACK = AppConfig(THEME_DEFAULT, showWorkOnly = false, smartHome = true)
    }
}

enum class TimerMode(val json: String) { INFINITE("INFINITE"), SIMPLE("SIMPLE"), INTERVAL("INTERVAL") }

data class IntervalConfig(val workMs: Long, val restMs: Long, val cycles: Long, val autostart: Boolean) {
    fun isValid() = cycles in TimerLimits.CYCLES_MIN..TimerLimits.CYCLES_MAX &&
        workMs in TimerLimits.WORK_MIN_MN * TimerLimits.MIN_MS..TimerLimits.WORK_MAX_MN * TimerLimits.MIN_MS &&
        restMs in TimerLimits.REST_MIN_MN * TimerLimits.MIN_MS..TimerLimits.REST_MAX_MN * TimerLimits.MIN_MS
}

/** BusyTimerConfig. `simpleTotalMs` is only meaningful for SIMPLE, `interval` only for INTERVAL. */
data class TimerConfig(
    val mode: TimerMode,
    val simpleTotalMs: Long = 0,
    val interval: IntervalConfig = IntervalConfig(0, 0, 0, false),
)

data class Profile(
    val sortOrder: Int,
    val title: String,
    val cardId: String,
    val timer: TimerConfig,
    val app: AppConfig,
    val timestampMs: Long,
) {
    fun isValid(): Boolean = TimerJson.isValidCardId(cardId) && when (timer.mode) {
        TimerMode.INFINITE -> true
        TimerMode.SIMPLE -> timer.simpleTotalMs <= TimerLimits.TIME_MAX_MN * TimerLimits.MIN_MS
        TimerMode.INTERVAL -> timer.interval.isValid()
    }

    companion object {
        /** busy_timer_settings_v1_defaults. */
        val DEFAULT_BUSY = Profile(
            -1, "BUSY", "00000000-0000-0000-0000-000000000000",
            TimerConfig(
                TimerMode.INTERVAL,
                interval = IntervalConfig(
                    TimerLimits.WORK_DEFAULT_MN * TimerLimits.MIN_MS, TimerLimits.REST_DEFAULT_MN * TimerLimits.MIN_MS,
                    TimerLimits.CYCLES_DEFAULT.toLong(), false,
                ),
            ),
            AppConfig(AppConfig.THEME_DEFAULT, showWorkOnly = false, smartHome = true), 0,
        )
        val DEFAULT_CUSTOM = Profile(
            -1, "ZEN", "00000000-0000-0000-0000-000000000001", TimerConfig(TimerMode.INFINITE),
            AppConfig(AppConfig.THEME_CUSTOM_DEFAULT, showWorkOnly = true, smartHome = true), 0,
        )
    }
}

enum class ProfileSlot(val path: String) {
    BUSY("busy"), CUSTOM("custom");

    val default get() = if (this == BUSY) Profile.DEFAULT_BUSY else Profile.DEFAULT_CUSTOM

    companion object {
        fun fromPath(path: String) = entries.firstOrNull { it.path == path }
    }
}

/** The four snapshot kinds of BusyTimerSnapshot. */
sealed interface SnapshotState {
    data object NotStarted : SnapshotState
    data class Infinite(val cardId: String, val paused: Boolean) : SnapshotState
    data class Simple(val cardId: String, val paused: Boolean, val timeLeftMs: Long) : SnapshotState
    data class Interval(
        val cardId: String,
        val paused: Boolean,
        val index: Long,
        val totalMs: Long,
        val leftMs: Long,
        val config: IntervalConfig,
    ) : SnapshotState
}

data class Snapshot(val state: SnapshotState, val app: AppConfig, val timestampMs: Long) {
    val cardId get() = when (state) {
        SnapshotState.NotStarted -> null
        is SnapshotState.Infinite -> state.cardId
        is SnapshotState.Simple -> state.cardId
        is SnapshotState.Interval -> state.cardId
    }

    val isPaused get() = when (state) {
        SnapshotState.NotStarted -> false
        is SnapshotState.Infinite -> state.paused
        is SnapshotState.Simple -> state.paused
        is SnapshotState.Interval -> state.paused
    }

    /** busy_timer_snapshot_is_valid, with "now" for the future-timestamp check. */
    fun isValid(nowMs: Long): Boolean {
        if (timestampMs > nowMs + TimerLimits.TIMESTAMP_TOLERANCE_MS) return false
        return when (val s = state) {
            SnapshotState.NotStarted -> true
            is SnapshotState.Infinite -> TimerJson.isValidCardId(s.cardId)
            is SnapshotState.Simple -> TimerJson.isValidCardId(s.cardId) && s.timeLeftMs <= TimerLimits.TIME_MAX_MN * TimerLimits.MIN_MS
            is SnapshotState.Interval -> TimerJson.isValidCardId(s.cardId) && s.config.isValid() && isValidIntervalState(s)
        }
    }

    private fun isValidIntervalState(s: SnapshotState.Interval): Boolean {
        if (s.index > TimerLimits.CYCLES_MAX * 2L - 2) return false
        if (s.leftMs > s.totalMs) return false
        val upper = if (s.index % 2 == 1L) TimerLimits.REST_MAX_MN else TimerLimits.WORK_MAX_MN
        return s.leftMs <= upper * TimerLimits.MIN_MS
    }

    companion object {
        /** The zeroed snapshot a fresh device reports before anything has run. */
        val INITIAL = Snapshot(SnapshotState.NotStarted, AppConfig.EMPTY, 0)
    }
}

object TimerJson {
    // ---- serialization (cJSON insertion order) ----

    fun snapshot(s: Snapshot): JsonObject = buildJsonObject {
        putJsonObject("snapshot") {
            when (val st = s.state) {
                SnapshotState.NotStarted -> put("type", "NOT_STARTED")
                is SnapshotState.Infinite -> {
                    put("type", "INFINITE"); put("card_id", st.cardId); put("is_paused", st.paused)
                }
                is SnapshotState.Simple -> {
                    put("type", "SIMPLE"); put("card_id", st.cardId); put("is_paused", st.paused)
                    put("time_left_ms", st.timeLeftMs)
                }
                is SnapshotState.Interval -> {
                    put("type", "INTERVAL"); put("card_id", st.cardId); put("is_paused", st.paused)
                    put("current_interval", st.index)
                    put("current_interval_time_total_ms", st.totalMs)
                    put("current_interval_time_left_ms", st.leftMs)
                    put("interval_settings", interval(st.config))
                }
            }
            put("busy_bar_settings", appConfig(s.app))
        }
        put("snapshot_timestamp_ms", s.timestampMs)
    }

    fun profile(p: Profile): JsonObject = buildJsonObject {
        put("sort_order", p.sortOrder)
        put("title", p.title)
        put("id", p.cardId)
        put("timer_settings", timerConfig(p.timer))
        put("busy_bar_settings", appConfig(p.app))
        put("profile_timestamp_ms", p.timestampMs)
    }

    private fun appConfig(a: AppConfig) = buildJsonObject {
        put("theme", a.theme)
        put("show_work_phase_only", a.showWorkOnly)
        put("trigger_smart_home", a.smartHome)
    }

    private fun interval(c: IntervalConfig) = buildJsonObject {
        put("type", "INTERVAL")
        put("interval_work_ms", c.workMs)
        put("interval_rest_ms", c.restMs)
        put("interval_work_cycles_count", c.cycles)
        put("is_autostart_enabled", c.autostart)
    }

    private fun timerConfig(t: TimerConfig): JsonObject = when (t.mode) {
        TimerMode.INFINITE -> buildJsonObject { put("type", "INFINITE") }
        TimerMode.SIMPLE -> buildJsonObject { put("type", "SIMPLE"); put("total_time_ms", t.simpleTotalMs) }
        TimerMode.INTERVAL -> interval(t.interval)
    }

    // ---- deserialization (null = rejected, as the C functions return false) ----

    /** busy_timer_snapshot_deserialize, including its validity check against [nowMs]. */
    fun parseSnapshot(json: JsonElement?, nowMs: Long): Snapshot? {
        val root = json as? JsonObject ?: return null
        val s = root["snapshot"] as? JsonObject ?: return null
        val state: SnapshotState = when (s.str("type")) {
            "NOT_STARTED" -> SnapshotState.NotStarted
            "INFINITE" -> {
                val (card, paused) = common(s) ?: return null
                SnapshotState.Infinite(card, paused)
            }
            "SIMPLE" -> {
                val (card, paused) = common(s) ?: return null
                SnapshotState.Simple(card, paused, s.u32("time_left_ms") ?: return null)
            }
            "INTERVAL" -> {
                val (card, paused) = common(s) ?: return null
                SnapshotState.Interval(
                    card, paused,
                    s.u32("current_interval") ?: return null,
                    s.u32("current_interval_time_total_ms") ?: return null,
                    s.u32("current_interval_time_left_ms") ?: return null,
                    parseInterval(s["interval_settings"]) ?: return null,
                )
            }
            else -> return null
        }
        // Missing or malformed settings fall back to defaults (marked TODO upstream).
        val app = parseAppConfig(s["busy_bar_settings"]) ?: AppConfig.SNAPSHOT_FALLBACK
        val ts = root.u64("snapshot_timestamp_ms") ?: return null
        return Snapshot(state, app, ts).takeIf { it.isValid(nowMs) }
    }

    /** busy_timer_profile_deserialize. Validity is checked later, when the timer applies it. */
    fun parseProfile(json: JsonElement?): Profile? {
        val o = json as? JsonObject ?: return null
        val sortOrder = o.num("sort_order")?.toLong()?.toInt() ?: return null
        val title = o.str("title")?.take(TimerLimits.TITLE_LEN) ?: return null
        val id = o.str("id")?.take(TimerLimits.CARD_ID_LEN) ?: return null
        val timer = parseTimerConfig(o["timer_settings"]) ?: return null
        val rawApp = o["busy_bar_settings"]
        val app = parseAppConfig(rawApp) ?: if (rawApp == null || rawApp is JsonNull) AppConfig.EMPTY else return null
        val ts = o.u64("profile_timestamp_ms") ?: return null
        return Profile(sortOrder, title, id, timer, app, ts)
    }

    private fun common(o: JsonObject): Pair<String, Boolean>? {
        val card = o.str("card_id")?.takeIf { it.length == TimerLimits.CARD_ID_LEN } ?: return null
        val paused = o.bool("is_paused") ?: return null
        return card to paused
    }

    private fun parseAppConfig(e: JsonElement?): AppConfig? {
        val o = e as? JsonObject ?: return null
        return AppConfig(
            o.str("theme")?.take(TimerLimits.THEME_LEN) ?: return null,
            o.bool("show_work_phase_only") ?: return null,
            o.bool("trigger_smart_home") ?: return null,
        )
    }

    private fun parseInterval(e: JsonElement?): IntervalConfig? {
        val o = e as? JsonObject ?: return null
        return IntervalConfig(
            o.u32("interval_work_ms") ?: return null,
            o.u32("interval_rest_ms") ?: return null,
            o.u32("interval_work_cycles_count") ?: return null,
            o.bool("is_autostart_enabled") ?: return null,
        )
    }

    private fun parseTimerConfig(e: JsonElement?): TimerConfig? {
        val o = e as? JsonObject ?: return null
        return when (o.str("type")) {
            "INFINITE" -> TimerConfig(TimerMode.INFINITE)
            "SIMPLE" -> TimerConfig(TimerMode.SIMPLE, simpleTotalMs = o.u32("total_time_ms") ?: return null)
            "INTERVAL" -> TimerConfig(TimerMode.INTERVAL, interval = parseInterval(o) ?: return null)
            else -> null
        }
    }

    /** busy_timer_common_is_valid_card_id: 8-4-4-4-12 hex digits. */
    fun isValidCardId(id: String): Boolean = id.length == TimerLimits.CARD_ID_LEN && id.withIndex().all { (i, c) ->
        if (i == 8 || i == 13 || i == 18 || i == 23) c == '-' else c.isDigit() || c.lowercaseChar() in 'a'..'f'
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** A cJSON number stored into a uint32_t field. */
    private fun JsonObject.u32(k: String) = num(k)?.let { it.toLong() and 0xFFFFFFFFL }

    /** A cJSON number stored into a time_t (int64) field. */
    private fun JsonObject.u64(k: String) = num(k)?.toLong()
}
