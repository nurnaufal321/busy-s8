package com.busys8.core

import java.io.File
import java.io.InputStream

/**
 * Read-only access to the bundled firmware assets (vendor/ in this repo), addressed by
 * relative paths such as `shared/fonts/busy_regular_7.font`.
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
 * The device's filesystem view, using firmware paths (storage.h). Read-only shared assets under
 * `/ext/apps_assets/shared/` come from the bundle; the rest of `/ext` is app-writable storage.
 *
 * The firmware build converts stock PNGs to `.image` and frame zips to `.anim` (`./fbt resources`);
 * we keep the sources and map the converted names back to them here.
 */
class DeviceStorage(private val assets: AssetSource, val extRoot: File) {
    fun read(path: String): ByteArray? = when {
        path.startsWith(SHARED) -> readShared(path.removePrefix(SHARED))
        path.startsWith(EXT) -> resolveExt(path)?.takeIf { it.isFile }?.readBytes()
        else -> null
    }

    private fun readShared(rel: String): ByteArray? {
        val bundled = when {
            rel.startsWith("images/") && rel.endsWith(".image") ->
                "shared/images/external/" + rel.removePrefix("images/").removeSuffix(".image") + ".png"
            rel.startsWith("animations/") && rel.endsWith(".anim") ->
                "shared/animations/" + rel.removePrefix("animations/").removeSuffix(".anim") + ".zip"
            else -> "shared/$rel"
        }
        return assets.open(bundled)?.use { it.readBytes() }
    }

    /** Maps `/ext/...` into [extRoot], refusing anything that escapes it. */
    fun resolveExt(path: String): File? {
        if (!path.startsWith(EXT)) return null
        val f = File(extRoot, path.removePrefix(EXT)).canonicalFile
        return f.takeIf { it.path.startsWith(extRoot.canonicalPath) }
    }

    companion object {
        const val EXT = "/ext/"
        const val SHARED = "${EXT}apps_assets/shared/"
        fun sharedFont(name: String) = "${SHARED}fonts/$name.font"
        fun sharedImage(name: String) = "${SHARED}images/$name"
        fun sharedAnim(name: String) = "${SHARED}animations/$name"
        fun userAssets(app: String, path: String) = "${EXT}user_assets/$app/$path"
    }
}
