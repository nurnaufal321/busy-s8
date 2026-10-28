package com.busys8.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

class WeatherTest {
    /** Two days of hourly data, 20° + hour-of-day, code 3 except a storm (95) at 21:00 on day one. */
    private val forecast = run {
        val times = (0 until 48).map { "2026-10-${28 + it / 24}T%02d:00".format(it % 24) }
        fun arr(f: (Int) -> String) = times.indices.joinToString(",", "[", "]") { f(it) }
        """{"latitude":1.3,"longitude":103.8,"utc_offset_seconds":28800,"timezone":"Asia/Singapore",
           "current":{"time":"2026-10-28T19:00","temperature_2m":29.5,"weather_code":3,"is_day":0},
           "hourly":{"time":${times.joinToString(",", "[", "]") { "\"$it\"" }},
             "temperature_2m":${arr { "${20 + it % 24}.0" }},
             "weather_code":${arr { if (it == 21) "95" else "3" }},
             "is_day":${arr { if (it % 24 in 7..18) "1" else "0" }}}}"""
    }

    @Test
    fun `open-meteo forecast becomes the backend format`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val out = forecast.toByteArray()
                ex.sendResponseHeaders(200, out.size.toLong())
                ex.responseBody.use { it.write(out) }
            }
            start()
        }
        try {
            val o = OpenMeteo(forecastBase = "http://127.0.0.1:${server.address.port}/f").forecast(1.3, 103.8, "Here", "request")
            val loc = o.getValue("location").jsonObject
            assertEquals("Here", loc.getValue("name").jsonPrimitive.content)
            assertEquals(28800, loc.getValue("utc_offset_seconds").jsonPrimitive.int)
            val hourly = o.getValue("hourly").jsonArray
            assertEquals("2026-10-28T19:00", hourly.first().jsonObject.getValue("time").jsonPrimitive.content)
            assertEquals(48 - 19, hourly.size)
            val day1 = o.getValue("daily").jsonArray.single().jsonObject
            assertEquals("2026-10-28", day1.getValue("date").jsonPrimitive.content)
            assertEquals(37.0, day1.getValue("day").jsonObject.getValue("temperature").jsonPrimitive.double, 0.0)
            assertEquals(3, day1.getValue("day").jsonObject.getValue("weather_code").jsonPrimitive.int)
            assertEquals(20.0, day1.getValue("night").jsonObject.getValue("temperature").jsonPrimitive.double, 0.0)
            assertEquals(95, day1.getValue("night").jsonObject.getValue("weather_code").jsonPrimitive.int)
        } finally {
            server.stop(0)
        }
    }
}
