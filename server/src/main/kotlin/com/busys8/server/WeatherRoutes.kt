package com.busys8.server

import com.busys8.engine.Busybar
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The BUSY apps backend's weather API (`/weather/v1`), answered locally from Open-Meteo.
 *
 * On a real bar, weather-app fetches it from Flipper's server, authenticated with the bar's
 * device certificate, which the phone does not have. Public builds of the app use a relative
 * URL, which the JS runtime resolves against the bar itself, so it lands here. The response
 * format is the backend's (weather-app `apiTypes.ts`), which is close to Open-Meteo's own.
 *
 * Without coordinates the backend locates the bar by IP; here the bar's time zone stands in,
 * the way the web UI picks a default city.
 */
fun Route.weatherRoutes(bar: Busybar, openMeteo: OpenMeteo = OpenMeteo()) {
    get("/weather/v1/forecast") {
        val q = call.request.queryParameters
        val lat = q["latitude"]?.toDoubleOrNull()
        val lon = q["longitude"]?.toDoubleOrNull()
        val result = runCatching {
            withContext(Dispatchers.IO) {
                if (lat != null && lon != null) {
                    openMeteo.forecast(lat, lon, null, "request")
                } else {
                    val place = openMeteo.placeForZone(bar.platform.timeZone())
                        ?: return@withContext null
                    openMeteo.forecast(place.first, place.second, place.third, "geoip")
                }
            }
        }
        val body = result.getOrNull()
        when {
            body != null -> call.respondText(body.toString(), ContentType.Application.Json)
            result.isSuccess -> call.weatherError(HttpStatusCode.UnprocessableEntity, 4201, "Cannot locate the device: set a location in the app settings")
            else -> call.weatherError(HttpStatusCode.BadGateway, 5000, "Weather service unavailable: ${result.exceptionOrNull()?.message}")
        }
    }

    get("/weather/v1/locations") {
        val query = call.request.queryParameters["query"]?.trim().orEmpty()
        if (query.length < 2) return@get call.weatherError(HttpStatusCode.BadRequest, 4001, "query is too short")
        val result = runCatching { withContext(Dispatchers.IO) { openMeteo.search(query, call.request.queryParameters["language"]) } }
        result.fold(
            onSuccess = { call.respondText(buildJsonObject { put("results", it) }.toString(), ContentType.Application.Json) },
            onFailure = { call.weatherError(HttpStatusCode.BadGateway, 5000, "Geocoding unavailable: ${it.message}") },
        )
    }
}

private suspend fun ApplicationCall.weatherError(status: HttpStatusCode, code: Int, message: String) = respondText(
    buildJsonObject { put("error", buildJsonObject { put("code", code); put("message", message) }) }.toString(),
    ContentType.Application.Json,
    status,
)

/** A small Open-Meteo client with short-lived caches; blocking, so call it off the main thread. */
class OpenMeteo(
    private val forecastBase: String = "https://api.open-meteo.com/v1/forecast",
    private val geocodingBase: String = "https://geocoding-api.open-meteo.com/v1/search",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val forecasts = ConcurrentHashMap<String, Pair<Long, JsonObject>>()
    private val zones = ConcurrentHashMap<String, Triple<Double, Double, String>>()

    /** The backend's ForecastResponse for a place. */
    fun forecast(lat: Double, lon: Double, name: String?, source: String): JsonObject {
        val key = "%.3f,%.3f".format(lat, lon)
        val raw = forecasts[key]?.takeIf { clock() - it.first < FORECAST_TTL_MS }?.second ?: run {
            val vars = "temperature_2m,weather_code,is_day"
            val o = get("$forecastBase?latitude=$lat&longitude=$lon&current=$vars&hourly=$vars&timezone=auto&forecast_days=${DAYS + 1}")
            forecasts[key] = clock() to o
            o
        }
        return convert(raw, name, source)
    }

    /** Coordinates of the city a time zone is named after (`Asia/Kuala_Lumpur` → Kuala Lumpur). */
    fun placeForZone(zone: String): Triple<Double, Double, String>? {
        zones[zone]?.let { return it }
        val city = zone.substringAfterLast('/').replace('_', ' ')
        if (city.isEmpty() || zone.startsWith("Etc") || zone == "UTC") return null
        val match = search(city, null).map { it.jsonObject }
            .firstOrNull { it["timezone"]?.jsonPrimitive?.content == zone }
            ?: return null
        val place = Triple(match["latitude"]!!.jsonPrimitive.double, match["longitude"]!!.jsonPrimitive.double, match["name"]!!.jsonPrimitive.content)
        zones[zone] = place
        return place
    }

    /** The backend's LocationsResponse results (ApiPlace). */
    fun search(query: String, language: String?): JsonArray {
        val lang = language?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: "en"
        val o = get("$geocodingBase?name=${URLEncoder.encode(query, "UTF-8")}&count=10&language=$lang&format=json")
        return buildJsonArray {
            for (r in (o["results"] as? JsonArray).orEmpty()) {
                val p = r.jsonObject
                add(buildJsonObject {
                    put("name", p["name"] ?: JsonNull)
                    put("country", p["country"] ?: JsonNull)
                    put("country_code", p["country_code"] ?: JsonNull)
                    put("admin1", p["admin1"] ?: JsonNull)
                    put("latitude", p["latitude"] ?: JsonNull)
                    put("longitude", p["longitude"] ?: JsonNull)
                    put("timezone", p["timezone"] ?: JsonNull)
                })
            }
        }
    }

    private fun convert(o: JsonObject, name: String?, source: String): JsonObject {
        val current = o.getValue("current").jsonObject
        val hourly = o.getValue("hourly").jsonObject
        val times = hourly.getValue("time").jsonArray.map { it.jsonPrimitive.content }
        val temps = hourly.getValue("temperature_2m").jsonArray.map { it.jsonPrimitive.double }
        val codes = hourly.getValue("weather_code").jsonArray.map { it.jsonPrimitive.int }
        val isDay = hourly.getValue("is_day").jsonArray.map { it.jsonPrimitive.int == 1 }
        // Hourly from the current hour on, in local time like Open-Meteo (the app applies the offset).
        val nowHour = current.getValue("time").jsonPrimitive.content.take(13)
        val start = times.indexOfFirst { it.take(13) >= nowHour }.coerceAtLeast(0)

        fun reading(time: String, temp: Double, code: Int, day: Boolean) = buildJsonObject {
            put("time", time)
            put("temperature", temp)
            put("weather_code", code)
            put("is_day", day)
        }

        return buildJsonObject {
            put("location", buildJsonObject {
                put("latitude", o.getValue("latitude"))
                put("longitude", o.getValue("longitude"))
                put("name", name?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                put("timezone", o.getValue("timezone"))
                put("utc_offset_seconds", o.getValue("utc_offset_seconds"))
                put("source", source)
            })
            put(
                "current",
                reading(
                    current.getValue("time").jsonPrimitive.content,
                    current.getValue("temperature_2m").jsonPrimitive.double,
                    current.getValue("weather_code").jsonPrimitive.int,
                    current.getValue("is_day").jsonPrimitive.int == 1,
                ),
            )
            put("hourly", buildJsonArray {
                for (i in start until times.size) add(reading(times[i], temps[i], codes[i], isDay[i]))
            })
            // Day = 06:00–18:00 (warmest hour, most severe code); night = 18:00–06:00 after it
            // (coolest hour, most severe code), as Open-Meteo's own daily aggregates do.
            put("daily", buildJsonArray {
                val dates = times.map { it.take(10) }.distinct().take(DAYS)
                for (date in dates) {
                    val first = times.indexOf("${date}T00:00")
                    // Only days whose night is fully in the data (forecast_days asks for one extra).
                    if (first < 0 || first + 30 > times.size) continue
                    val day = first + 6 until first + 18
                    val night = first + 18 until first + 30
                    add(buildJsonObject {
                        put("date", date)
                        put("day", half(day.maxOf { temps[it] }, day.maxOf { codes[it] }))
                        put("night", half(night.minOf { temps[it] }, night.maxOf { codes[it] }))
                    })
                }
            })
        }
    }

    private fun half(temp: Double, code: Int) = buildJsonObject {
        put("temperature", temp)
        put("weather_code", code)
    }

    private fun get(url: String): JsonObject {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code != 200) error("HTTP $code")
            return Json.parseToJsonElement(text).jsonObject
        } finally {
            c.disconnect()
        }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private companion object {
        const val DAYS = 7
        const val FORECAST_TTL_MS = 10 * 60_000L
    }
}
