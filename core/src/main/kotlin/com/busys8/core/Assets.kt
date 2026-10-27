package com.busys8.core

import java.io.File
import java.io.InputStream

/**
 * Read-only access to the bundled firmware assets (vendor/ in this repo), addressed by
 * relative paths such as `apps_assets/shared/fonts/busy_regular_7.font`.
 */
fun interface AssetSource {
    fun open(path: String): InputStream?
}

class FileAssetSource(private val root: File) : AssetSource {
    override fun open(path: String): InputStream? = File(root, path).takeIf { it.isFile }?.inputStream()
}

/** Platform image decoding: ImageIO on the JVM, BitmapFactory on Android. */
fun interface ImageDecoder {
    fun decode(bytes: ByteArray): RawImage?
}

/**
 * The device's filesystem view, using firmware paths (storage.h). Built-in app assets under
 * `/ext/apps_assets/` come from the bundle (vendor/apps_assets); the rest of `/ext` is
 * app-writable storage.
 *
 * The firmware build converts PNG to `.image`, frame zips to `.anim` and WAV to `.snd`
 * (`./fbt resources`); the bundle keeps the sources and [bundledPath] maps the names back.
 */
class DeviceStorage(private val assets: AssetSource, val extRoot: File) {
    fun read(path: String): ByteArray? {
        bundledPath(path)?.let { bundled -> assets.open(bundled)?.use { return it.readBytes() } }
        return resolveExt(path)?.takeIf { it.isFile }?.readBytes()
    }

    /** Bundle path for a built-in asset, or null if [path] is not under `/ext/apps_assets/`. */
    fun bundledPath(path: String): String? {
        if (!path.startsWith(APPS_ASSETS)) return null
        val rel = "apps_assets/" + path.removePrefix(APPS_ASSETS)
        return CONVERTED.entries.firstOrNull { rel.endsWith(it.key) }
            ?.let { rel.removeSuffix(it.key) + it.value } ?: rel
    }

    /** Maps `/ext/...` into [extRoot], refusing anything that escapes it. */
    fun resolveExt(path: String): File? {
        if (!path.startsWith(EXT)) return null
        val f = File(extRoot, path.removePrefix(EXT)).canonicalFile
        return f.takeIf { it.path.startsWith(extRoot.canonicalPath) }
    }

    companion object {
        const val EXT = "/ext/"
        const val APPS_ASSETS = "${EXT}apps_assets/"
        const val SHARED = "${APPS_ASSETS}shared/"
        private val CONVERTED = mapOf(".image" to ".png", ".anim" to ".zip", ".snd" to ".wav")
        fun sharedImage(name: String) = "${SHARED}images/$name"
        fun sharedAnim(name: String) = "${SHARED}animations/$name"
        fun userAssets(app: String, path: String) = "${EXT}user_assets/$app/$path"
    }
}
