package com.busys8.engine.jsapps

import com.busys8.engine.input.InputEvent
import com.busys8.engine.input.InputKey
import com.busys8.engine.input.InputType
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class JsRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += "${ex.requestMethod} ${ex.requestURI} ${ex.requestHeaders.getFirst("X-busybar-fetch")} $body"
            val out = """{"ok":true,"echo":"ü"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(if (ex.requestURI.path == "/missing") 404 else 200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }

    @After fun stopServer() = server.stop(0)

    private val host = object : JsHost {
        override val localOrigin = "http://127.0.0.1:${server.address.port}"
        override fun console(appId: String, level: String, text: String) { logs += "$level: $text" }
    }

    private fun app(vararg scripts: Pair<String, String>): JsApp {
        val dir = tmp.newFolder()
        scripts.forEach { (name, code) -> File(dir, "scripts/$name").apply { parentFile.mkdirs() }.writeText(code) }
        val manifest = AppManifest(id = "org.test.app", name = "Test", version = "1.0.0", apiVersion = "27.0.0", author = "", description = "", heapSizeKib = 0, isDebug = false)
        return JsApp(manifest, dir, "/ext/user_assets/org.test.app")
    }

    private fun run(app: JsApp, during: (JsRun) -> Unit = {}): String? {
        val done = CompletableFuture<String?>()
        val run = JsRun(app, File(tmp.root, "ls.json"), host) { _, error -> done.complete(error) }
        run.start()
        during(run)
        return done.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `globals work and the app ends when nothing keeps it alive`() {
        val error = run(
            app(
                "util.js" to "export const twice = (x) => x * 2;",
                "main.js" to """
                    import { twice } from "./util.js";
                    console.log("twice", twice(21), {a: [1]});
                    const u = new URL("http://localhost/api/apps/settings?app_id=a%20b");
                    u.searchParams.set("x", "1");
                    console.log(u.href, u.searchParams.get("app_id"));
                    localStorage.setItem("n", String(Number(localStorage.getItem("n") ?? 0) + 1));
                    let ticks = 0;
                    const iv = setInterval(() => { if (++ticks === 3) { clearInterval(iv); console.log("ticks", ticks); } }, 10);
                    setTimeout((a) => console.log("timeout", a), 20, "arg");
                    clearTimeout(setTimeout(() => console.log("never"), 5));
                    (async () => {
                      const r = await fetch("http://localhost/api/display/draw", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ x: 1 }) });
                      console.log("fetch", r.ok, r.status, r.headers.get("content-type"), (await r.json()).echo);
                      const m = await fetch("http://localhost/missing");
                      console.log("missing", m.ok, m.status);
                      const c = new AbortController(); c.abort();
                      await fetch("http://localhost/x", { signal: c.signal }).catch((e) => console.log("aborted", e.name));
                      await fetch("http://127.0.0.1:1/").catch((e) => console.log("refused", e instanceof TypeError));
                    })();
                """.trimIndent(),
            ),
        )
        assertNull(error)
        val text = logs.joinToString("\n")
        assertTrue(text, "log: twice 42 {\"a\":[1]}" in logs)
        assertTrue(text, "log: http://localhost/api/apps/settings?app_id=a+b&x=1 a b" in logs)
        assertTrue(text, "log: ticks 3" in logs)
        assertTrue(text, "log: timeout arg" in logs)
        assertTrue(text, logs.none { "never" in it })
        assertTrue(text, "log: fetch true 200 application/json ü" in logs)
        assertTrue(text, "log: missing false 404" in logs)
        assertTrue(text, "log: aborted AbortError" in logs)
        assertTrue(text, "log: refused true" in logs)
        assertEquals("POST /api/display/draw js {\"x\":1}", requests.first())
        assertEquals("""{"format_version":1,"data":{"n":"1"}}""", File(tmp.root, "ls.json").readText())
    }

    @Test
    fun `input reaches the listener until it unbinds`() {
        val error = run(
            app(
                "main.js" to """
                    const unbind = listen("input", (e) => {
                      console.log(e.key, e.action, e.delta);
                      if (e.key === "back" && e.action === "short") unbind();
                    });
                    try { listen("input", () => {}); } catch (e) { console.log(e.message); }
                """.trimIndent(),
            ),
        ) { run ->
            while (!run.deliver(InputEvent(InputKey.OK, InputType.SHORT))) Thread.sleep(5)
            assertTrue(!run.deliver(InputEvent(InputKey.UP, InputType.RELEASE)))
            run.deliver(InputEvent(InputKey.UP, InputType.PRESS))
            assertTrue(!run.deliver(InputEvent(InputKey.BACK, InputType.LONG)))
            run.deliver(InputEvent(InputKey.BACK, InputType.SHORT))
        }
        assertNull(error)
        assertEquals(listOf("log: Handler override is forbidden", "log: ok short undefined", "log: encoder clockwise 1", "log: back short undefined"), logs)
    }

    @Test
    fun `stop ends an endless app and errors are reported`() {
        assertNull(run(app("main.js" to "setInterval(() => {}, 5); listen('input', () => {});")) { it.stop() })
        assertNull(run(app("main.js" to "for (;;) {}")) { Thread.sleep(200); it.stop() })
        val error = run(app("main.js" to "let = ;"))
        assertTrue(error, error!!.contains("SyntaxError"))
    }
}
