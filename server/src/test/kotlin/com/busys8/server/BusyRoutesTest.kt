package com.busys8.server

import com.busys8.core.BusyDevice
import com.busys8.core.FileAssetSource
import com.busys8.core.ImageDecoder
import com.busys8.engine.Busybar
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BusyRoutesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val assets = FileAssetSource(File(System.getProperty("busys8.vendor")))

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val device = BusyDevice(assets, tmp.newFolder("ext"), ImageDecoder { null })
        application { module(Busybar(device, tmp.newFolder("data")), assets) }
        block()
    }

    @Test
    fun `snapshot get and put`() = app {
        val fresh = client.get("/api/busy/snapshot").bodyAsText()
        assertTrue(fresh, fresh.startsWith("""{"snapshot":{"type":"NOT_STARTED""""))

        val now = System.currentTimeMillis()
        val body = """{"snapshot":{"type":"SIMPLE","card_id":"12345678-1234-1234-1234-123456789abc","is_paused":true,"time_left_ms":240000,"busy_bar_settings":{"theme":"busy","show_work_phase_only":false,"trigger_smart_home":true}},"snapshot_timestamp_ms":$now}"""
        val put = client.put("/api/busy/snapshot") { setBody(body) }
        assertEquals("{\"result\":\"OK\"}\n", put.bodyAsText())
        assertEquals(body, client.get("/api/busy/snapshot").bodyAsText())

        val bad = client.put("/api/busy/snapshot") { setBody("""{"snapshot":{"type":"WHAT"}}""") }
        assertEquals(400, bad.status.value)
        assertEquals("{\"error\":\"Failed to parse snapshot\"}\n", bad.bodyAsText())
    }

    @Test
    fun `profiles by slot`() = app {
        val busy = client.get("/api/busy/profiles/busy").bodyAsText()
        assertTrue(busy, busy.contains("\"title\":\"BUSY\""))
        assertEquals(400, client.get("/api/busy/profiles/nope").status.value)

        val p = """{"sort_order":-1,"title":"study","id":"00000000-0000-0000-0000-000000000001","timer_settings":{"type":"SIMPLE","total_time_ms":300000},"busy_bar_settings":{"theme":"on_air","show_work_phase_only":false,"trigger_smart_home":true},"profile_timestamp_ms":1000}"""
        assertEquals(200, client.put("/api/busy/profiles/custom") { setBody(p) }.status.value)
        assertEquals(p, client.get("/api/busy/profiles/custom").bodyAsText())
    }

    @Test
    fun `input keys`() = app {
        assertEquals(200, client.post("/api/input?key=off").status.value)
        val bad = client.post("/api/input?key=jump")
        assertEquals(400, bad.status.value)
        assertEquals("{\"error\":\"Bad Request\"}\n", bad.bodyAsText())
    }
}
