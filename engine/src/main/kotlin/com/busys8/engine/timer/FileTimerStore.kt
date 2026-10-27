package com.busys8.engine.timer

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps profiles and the last snapshot as the API's own JSON, in the files the firmware uses
 * (`settings_busy.json`, `settings_custom.json`, `state.json` under the timer's data dir).
 * Writes go to a temp file first so a crash mid-write never leaves a torn file.
 */
class FileTimerStore(private val dir: File) : TimerStore {
    init {
        dir.mkdirs()
    }

    override fun loadProfile(slot: ProfileSlot): Profile? =
        read("settings_${slot.path}.json")?.let(TimerJson::parseProfile)?.takeIf { it.isValid() }

    override fun saveProfile(slot: ProfileSlot, profile: Profile) =
        write("settings_${slot.path}.json", TimerJson.profile(profile).toString())

    /** Stored snapshots skip the future-timestamp check: the clock may have been wrong when saved. */
    override fun loadSnapshot(): Snapshot? = read("state.json")?.let { TimerJson.parseSnapshot(it, Long.MAX_VALUE / 2) }

    override fun saveSnapshot(snapshot: Snapshot) = write("state.json", TimerJson.snapshot(snapshot).toString())

    private fun read(name: String) =
        File(dir, name).takeIf { it.isFile }?.let { runCatching { Json.parseToJsonElement(it.readText()) }.getOrNull() }

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }
}
