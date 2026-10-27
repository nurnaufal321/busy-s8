package com.busys8.server

import com.busys8.core.BusyDevice
import com.busys8.core.FileAssetSource
import com.busys8.core.ImageDecoder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DrawRoutesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val assets = FileAssetSource(File(System.getProperty("busys8.vendor")))

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val device = BusyDevice(assets, tmp.root, ImageDecoder { null })
        application { module(device, assets) }
        block()
    }

    @Test
    fun `draw then clear`() = app {
        val ok = client.post("/api/display/draw") {
            setBody("""{"application_name":"curl","elements":[{"id":"0","type":"text","text":"Hi","font":"normal"}]}""")
        }
        assertEquals(200, ok.status.value)
        assertEquals("{\"result\":\"OK\"}\n", ok.bodyAsText())
        assertEquals("*", ok.headers["Access-Control-Allow-Origin"])

        val wrongApp = client.delete("/api/display/draw?application_name=other")
        assertEquals(400, wrongApp.status.value)
        assertEquals("{\"error\":\"this application_name is currently not displaying anything\"}\n", wrongApp.bodyAsText())

        assertEquals(200, client.delete("/api/display/draw?application_name=curl").status.value)
    }

    @Test
    fun `invalid draw reports firmware error`() = app {
        val r = client.post("/api/display/draw") { setBody("""{"application_name":"x","elements":[{"type":"text"}]}""") }
        assertEquals(400, r.status.value)
        assertEquals("{\"error\":\"elements[0].id is required\"}\n", r.bodyAsText())
    }

    @Test
    fun `preflight and spec`() = app {
        assertEquals(200, client.options("/api/display/draw").status.value)
        val spec = client.get("/openapi.yaml")
        assertEquals(200, spec.status.value)
        assertEquals("openapi: 3.1.0", spec.bodyAsText().lineSequence().first())
    }
}
