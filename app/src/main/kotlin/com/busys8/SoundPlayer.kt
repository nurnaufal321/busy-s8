package com.busys8

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import com.busys8.core.DeviceStorage

/**
 * Plays the firmware's short UI sounds (`.snd` files under `/ext/apps_assets`, bundled as WAV) through a
 * SoundPool. Sounds load on first use; the first play of each may be skipped while it loads.
 */
class SoundPlayer(private val assets: AssetManager, private val storage: DeviceStorage) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private val pending = HashSet<Int>()

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            synchronized(this) {
                if (status == 0) loaded += id
                if (pending.remove(id) && status == 0) pool.play(id, 1f, 1f, 1, 0, 1f)
            }
        }
    }

    @Synchronized
    fun play(devicePath: String) {
        val id = ids.getOrPut(devicePath) {
            val bundled = storage.bundledPath(devicePath) ?: return
            runCatching { assets.openFd(bundled).use { pool.load(it, 1) } }.getOrNull() ?: return
        }
        if (id in loaded) pool.play(id, 1f, 1f, 1, 0, 1f) else pending += id
    }
}
