package com.busys8.server

import com.busys8.core.BusyDevice
import com.busys8.core.FileAssetSource
import com.busys8.core.ImageDecoder
import com.busys8.engine.Busybar
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Phase3RoutesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val assets = FileAssetSource(File(System.getProperty("busys8.vendor")))

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val device = BusyDevice(assets, tmp.newFolder("ext"), ImageDecoder { null })
        application { module(Busybar(device, tmp.newFolder("data")), assets) }
        block()
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `system endpoints answer with schema fields`() = app {
        assertEquals("27.14.0", json(client.get("/api/version").bodyAsText())["api_semver"]!!.jsonPrimitive.content)
        val status = json(client.get("/api/status").bodyAsText())
        assertTrue(status.keys.containsAll(listOf("device", "firmware", "system", "power")))
        assertEquals(200, client.post("/api/name") { setBody("""{"name":"Desk bar"}""") }.status.value)
        assertEquals("Desk bar", json(client.get("/api/name").bodyAsText())["name"]!!.jsonPrimitive.content)
        assertEquals(400, client.post("/api/display/brightness?value=101").status.value)
        assertEquals(200, client.post("/api/display/brightness?value=40").status.value)
        assertEquals("40", json(client.get("/api/display/brightness").bodyAsText())["value"]!!.jsonPrimitive.content)
        assertTrue(json(client.get("/api/time").bodyAsText())["timestamp"]!!.jsonPrimitive.content.contains("T"))
        assertEquals(72 * 16 * 3, java.util.Base64.getDecoder().decode(client.get("/api/screen?display=0").bodyAsText()).size)
        assertEquals(400, client.get("/api/screen?display=2").status.value)
    }

    @Test
    fun `access tokens mint, list and revoke`() = app {
        val minted = json(client.post("/api/access/tokens") { setBody("""{"name":"ha"}""") }.bodyAsText())
        val token = minted["token"]!!.jsonPrimitive.content
        val shortId = minted["short_id"]!!.jsonPrimitive.content
        assertEquals(32, token.length)
        assertEquals(token.take(8), shortId)
        val list = json(client.get("/api/access/tokens").bodyAsText())["tokens"]!!.jsonArray
        assertEquals(1, list.size)
        assertTrue("token" !in list[0].jsonObject)
        assertEquals(200, client.delete("/api/access/tokens/$shortId").status.value)
        assertEquals(404, client.delete("/api/access/tokens/$shortId").status.value)
        assertEquals(400, client.post("/api/access?mode=key&key=12").status.value)
    }

    @Test
    fun `storage round trip and bundled assets are read-only`() = app {
        assertEquals(200, client.post("/api/storage/write?path=/ext/notes/a.txt") { setBody("hello") }.status.value)
        assertEquals("hello", client.get("/api/storage/read?path=/ext/notes/a.txt").bodyAsText())
        assertEquals(200, client.post("/api/storage/rename?path=/ext/notes/a.txt&new_path=/ext/notes/b.txt").status.value)
        val names = json(client.get("/api/storage/list?path=/ext/notes").bodyAsText())["list"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("b.txt"), names)
        assertEquals(200, client.delete("/api/storage/remove?path=/ext/notes").status.value)

        val themes = json(client.get("/api/storage/list?path=/ext/apps_assets/busy/themes").bodyAsText())["list"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("on_air" in themes)
        val theme = json(client.get("/api/storage/read?path=/ext/apps_assets/busy/themes/on_air/theme.json").bodyAsText())
        assertEquals("/ext/apps_assets/shared/animations/on_air_72x16.anim", theme["bg_path"]!!.jsonPrimitive.content)
        assertTrue(client.get("/api/storage/read?path=/ext/apps_assets/shared/animations/on_air_72x16.anim").bodyAsBytes().size > 1000)
        assertEquals(400, client.post("/api/storage/write?path=/ext/apps_assets/busy/themes/on_air/theme.json") { setBody("x") }.status.value)
        assertEquals(400, client.get("/api/storage/read?path=/ext/../etc").status.value)
    }

    @Test
    fun `apps stage, install, settings and remove`() = app {
        val manifest = """{"format_version":1,"id":"org.test.hello","name":"Hello","version":"1.0.0","api_version":"27.0.0"}"""
        val settings = """{"format_version":1,"version":2,"fields":{"greeting":{"type":"string","label":"Greeting","default":"hi"},"style":{"type":"group","label":"Style","fields":{"bold":{"type":"boolean","label":"Bold","default":false}}}}}"""
        val tgz = tarGz(
            "org.test.hello/appmeta/manifest.json" to manifest,
            "org.test.hello/appmeta/settings.json" to settings,
            "org.test.hello/scripts/main.js" to "console.log('hi')",
        )
        val staged = json(client.post("/api/apps/stage") { setBody(tgz) }.bodyAsText())
        assertEquals("OK", staged["result"]!!.jsonPrimitive.content)
        val key = staged["install_key"]!!.jsonPrimitive.content
        assertEquals(400, client.post("/api/apps/install?install_key=1").status.value)
        assertEquals(200, client.post("/api/apps/install?install_key=$key").status.value)

        val apps = json(client.get("/api/apps/list").bodyAsText())["apps"]!!.jsonArray
        assertEquals("org.test.hello", apps.single().jsonObject["id"]!!.jsonPrimitive.content)

        val s = json(client.get("/api/apps/settings?app_id=org.test.hello").bodyAsText())
        assertEquals(2, s["version"]!!.jsonPrimitive.int)
        assertEquals("""{"greeting":"hi","style":{"bold":false}}""", s["values"].toString())
        assertEquals(200, client.put("/api/apps/settings?app_id=org.test.hello") { setBody("""{"version":2,"values":{"greeting":"yo","style":{"bold":true}}}""") }.status.value)
        assertEquals(400, client.put("/api/apps/settings?app_id=org.test.hello") { setBody("""{"version":1,"values":{}}""") }.status.value)
        assertEquals("yo", json(client.get("/api/apps/settings?app_id=org.test.hello").bodyAsText())["values"]!!.jsonObject["greeting"]!!.jsonPrimitive.content)

        val bad = client.post("/api/apps/stage") { setBody(tarGz("x/appmeta/manifest.json" to """{"id":"y"}""")) }
        assertEquals(400, bad.status.value)
        assertEquals("manifest_error", json(bad.bodyAsText())["error_code"]!!.jsonPrimitive.content)

        assertEquals(200, client.delete("/api/apps?app_id=org.test.hello").status.value)
        assertEquals(0, json(client.get("/api/apps/list").bodyAsText())["apps"]!!.jsonArray.size)
    }

    @Test
    fun `status stream sends a protobuf snapshot`() = app {
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/api/status/ws") {
            send(Frame.Text("""{"enable":true}"""))
            send(Frame.Text("""{"send":"all"}"""))
            val frame = incoming.receive() as Frame.Binary
            val bytes = frame.readBytes()
            // State.timestamp is field 1, fixed64: tag 0x09.
            assertEquals(0x09, bytes[0].toInt())
            val text = String(bytes, Charsets.ISO_8859_1)
            assertTrue("device name present", "BUSY Bar" in text)
            assertTrue("timer snapshot JSON present", "\"snapshot\"" in text)
        }
    }

    @Test
    fun `pb encodes varints, zigzag and nested messages`() {
        val bytes = Pb().apply { uint(1, 300); sint(2, -1); msg(3) { string(1, "a") }; msg(4) {} }.toByteArray()
        assertArrayEquals(byteArrayOf(0x08, 0xAC.toByte(), 0x02, 0x10, 0x01, 0x1A, 0x03, 0x0A, 0x01, 0x61, 0x22, 0x00), bytes)
    }

}

class TimestampTest {
    @Test
    fun `offsets beyond java time limits parse`() {
        assertEquals(1750000000000L, parseTimestamp("2025-06-15T15:06:40Z"))
        assertEquals(1750000000000L - (22 * 60 + 10) * 60_000L, parseTimestamp("2025-06-15T15:06:40+22:10"))
        assertEquals(1750000000000L + 5 * 3_600_000L, parseTimestamp("2025-06-15T15:06:40-0500"))
        assertEquals(null, parseTimestamp("not-a-timestamp"))
    }
}

class RleTest {
    /** rle.py decompress. */
    private fun decompress(src: ByteArray, block: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < src.size) {
            val op = src[i++].toInt() and 0xFF
            val n = op and 0x7F
            if (op and 0x80 != 0) {
                out.write(src, i, n * block)
                i += n * block
            } else {
                repeat(n) { out.write(src, i, block) }
                i += block
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `rle round trips and shrinks a dark frame`() {
        val dark = ByteArray(72 * 16 * 3)
        val packed = rleCompress(dark, 3)!!
        assertTrue("${packed.size} bytes", packed.size < 64)
        assertArrayEquals(dark, decompress(packed, 3))

        val rnd = java.util.Random(7)
        val mixed = ByteArray(72 * 16 * 3) { i -> if ((i / 3) % 9 < 4) 0 else rnd.nextInt(256).toByte() }
        rleCompress(mixed, 3)?.let { assertArrayEquals(mixed, decompress(it, 3)) }
        val noise = ByteArray(300) { rnd.nextInt(256).toByte() }
        assertEquals(null, rleCompress(noise, 3))
    }
}
