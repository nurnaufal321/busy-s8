package com.busys8.server

import com.busys8.core.BusyDevice
import com.busys8.core.FileAssetSource
import com.busys8.core.ImageDecoder
import com.busys8.engine.Busybar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.Collections

/** Installs and runs a JS app against a real listening server, as busy-cli would. */
class JsAppsLiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val assets = FileAssetSource(File(System.getProperty("busys8.vendor")))
    private val port = ServerSocket(0).use { it.localPort }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var bar: Busybar
    private lateinit var server: BusyServer

    private fun start() {
        val device = BusyDevice(assets, tmp.newFolder("ext"), ImageDecoder { null })
        bar = Busybar(device, tmp.newFolder("data"))
        bar.jsConsole = { id, level, text -> logs += "$id $level: $text" }
        device.start(scope, 10)
        server = BusyServer(bar, assets, port).also { it.start() }
    }

    @After fun stop() {
        server.stop()
        scope.cancel()
    }

    private fun http(method: String, path: String, body: ByteArray? = null): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        return code to ((if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() } ?: "")
    }

    private fun install(id: String, main: String) {
        val manifest = """{"format_version":1,"id":"$id","name":"$id","version":"1.0.0","api_version":"27.0.0"}"""
        val settings = """{"format_version":1,"version":1,"fields":{"text":{"type":"string","label":"Text","default":"HI"}}}"""
        val tgz = tarGz("$id/appmeta/manifest.json" to manifest, "$id/appmeta/settings.json" to settings, "$id/scripts/main.js" to main)
        val (code, body) = http("POST", "/api/apps/stage", tgz)
        assertEquals(body, 200, code)
        val key = Json.parseToJsonElement(body).jsonObject["install_key"]!!.jsonPrimitive.content
        assertEquals(200, http("POST", "/api/apps/install?install_key=$key").first)
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; logs: $logs")
            Thread.sleep(20)
        }
    }

    @Test
    fun `launch, draw through the local API, quit`() {
        start()
        install("org.test.other", "")
        install(
            "org.test.hi",
            """
            const API = "http://localhost/api";
            (async () => {
              const s = await (await fetch(`${'$'}{API}/apps/settings?app_id=org.test.hi`)).json();
              const other = await fetch(`${'$'}{API}/apps/settings?app_id=org.test.other`);
              const off = await fetch(`${'$'}{API}/storage/list?path=/ext`);
              console.log("settings", s.values.text, "other", other.status, "storage", off.status);
              const r = await fetch(`${'$'}{API}/display/draw`, { method: "POST", body: JSON.stringify({
                application_name: "org.test.hi", priority: 50,
                elements: [{ id: "t", type: "text", text: s.values.text, font: "normal", x: 0, y: 0, display: "front", color: "#FFFFFFFF" }],
              }) });
              console.log("draw", r.status);
            })();
            setInterval(() => {}, 1000);
            """.trimIndent(),
        )
        assertEquals(409, http("POST", "/api/apps/quit").first)
        assertEquals(404, http("POST", "/api/apps/launch?app_id=org.test.none").first)
        assertEquals(200, http("POST", "/api/apps/launch?app_id=org.test.hi").first)
        waitFor("the draw") { logs.any { "draw 200" in it } }
        assertTrue(logs.toString(), "org.test.hi log: settings HI other 403 storage 403" in logs)
        assertEquals("org.test.hi", bar.launcher.runningAppId)
        assertTrue(bar.device.canvas.isOpen)

        assertEquals(200, http("POST", "/api/apps/quit").first)
        waitFor("the canvas to clear") { !bar.device.canvas.isOpen }
        assertEquals(null, bar.launcher.runningAppId)
        assertEquals(409, http("POST", "/api/apps/quit").first)
    }
}
