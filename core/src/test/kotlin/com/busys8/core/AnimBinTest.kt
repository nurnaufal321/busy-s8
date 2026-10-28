package com.busys8.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class AnimBinTest {
    private val dir = File(System.getProperty("busys8.upstream"), "weather-app/src/animations")

    @Test
    fun `decodes every weather animation exactly`() {
        val files = dir.listFiles { f -> f.extension == "anim" }.orEmpty()
        org.junit.Assume.assumeTrue("needs upstream/fetch.sh", files.isNotEmpty())
        val previews = File("build/previews/anim").apply { mkdirs() }
        for (f in files) {
            val anim = AnimBin.parse(f.readBytes())
            assertNotNull(f.name, anim)
            anim!!
            assertEquals(f.name, anim.width * anim.height, anim.frames.first().argb.size)
            // A strip of every 10th frame, 4x, for eyeballing.
            val picks = anim.frames.indices.step(10).toList()
            val img = BufferedImage(anim.width * picks.size * 4, anim.height * 4, BufferedImage.TYPE_INT_ARGB)
            picks.forEachIndexed { k, fi ->
                val fr = anim.frames[fi]
                for (y in 0 until img.height) for (x in 0 until anim.width * 4) {
                    img.setRGB(k * anim.width * 4 + x, y, fr.argb[(y / 4) * anim.width + x / 4])
                }
            }
            ImageIO.write(img, "png", File(previews, f.nameWithoutExtension + ".png"))
        }
    }
}
