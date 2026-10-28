package com.busys8.server

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** A minimal ustar writer for test packages. */
internal fun tarGz(vararg files: Pair<String, String>): ByteArray {
    val tar = ByteArrayOutputStream()
    for ((name, content) in files) {
        val data = content.toByteArray()
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "0000644\u0000".toByteArray().copyInto(h, 100)
        "%011o\u0000".format(data.size).toByteArray().copyInto(h, 124)
        "%011o\u0000".format(0).toByteArray().copyInto(h, 136)
        "        ".toByteArray().copyInto(h, 148)
        h[156] = '0'.code.toByte()
        "ustar\u000000".toByteArray().copyInto(h, 257)
        val sum = h.sumOf { it.toInt() and 0xFF }
        "%06o\u0000 ".format(sum).toByteArray().copyInto(h, 148)
        tar.write(h)
        tar.write(data)
        tar.write(ByteArray((512 - data.size % 512) % 512))
    }
    tar.write(ByteArray(1024))
    return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tar.toByteArray()) } }.toByteArray()
}
