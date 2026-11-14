package fr.tidus.rando.weather

import fr.tidus.rando.importers.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan

/**
 * Météo de secours : MET Norway (institut météorologique norvégien), gratuit et sans clé.
 * Il suffit d'identifier l'appli dans l'en-tête User-Agent, comme le demandent leurs conditions.
 * Prévisions horaires sur ~2,5 jours, puis par pas de 6 h jusqu'à ~9 jours.
 */
object MetNorway {
    private const val UA = "Rando-Android/0.9 github.com/tidus84000/Rando"

    suspend fun fetch(points: List<SampleLoc>, start: LocalDate, end: LocalDate): List<LocationForecast> =
        withContext(Dispatchers.IO) {
            coroutineScope { points.map { p -> async { one(p, start, end) } }.awaitAll() }
        }

    private class Entry(val time: LocalDateTime, val details: JSONObject, val next: JSONObject?, val stepHours: Int)

    private fun one(p: SampleLoc, start: LocalDate, end: LocalDate): LocationForecast {
        val url = String.format(
            Locale.US,
            "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=%.4f&lon=%.4f&altitude=%d",
            p.lat, p.lon, p.ele.toInt(),
        )
        val body = Http.plain.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("MET Norway : erreur ${r.code}")
            r.body!!.string()
        }
        val zone = ZoneId.systemDefault()
        val series = JSONObject(body).getJSONObject("properties").getJSONArray("timeseries")
        val entries = ArrayList<Entry>()
        for (i in 0 until series.length()) {
            val ts = series.getJSONObject(i)
            val t = LocalDateTime.ofInstant(Instant.parse(ts.getString("time")), zone)
            val data = ts.getJSONObject("data")
            val details = data.getJSONObject("instant").getJSONObject("details")
            val n1 = data.optJSONObject("next_1_hours")
            val n6 = data.optJSONObject("next_6_hours")
            entries.add(Entry(t, details, n1 ?: n6, if (n1 != null) 1 else 6))
        }

        // grille horaire des jours demandés
        val times = ArrayList<LocalDateTime>()
        var t = start.atStartOfDay()
        while (!t.isAfter(end.atTime(23, 0))) {
            times.add(t)
            t = t.plusHours(1)
        }
        val names = WeatherService.HOURLY
        val values = names.associateWith { arrayOfNulls<Double>(times.size) }
        for ((i, hour) in times.withIndex()) {
            val e = entries.lastOrNull { !it.time.isAfter(hour) } ?: continue
            if (ChronoUnit.HOURS.between(e.time, hour) >= e.stepHours.coerceAtLeast(1) + 1) continue
            val d = e.details
            val temp = d.optDouble("air_temperature").takeUnless { it.isNaN() }
            val windMs = d.optDouble("wind_speed").takeUnless { it.isNaN() }
            val gustMs = d.optDouble("wind_speed_of_gust").takeUnless { it.isNaN() }
            val rh = d.optDouble("relative_humidity").takeUnless { it.isNaN() }
            val fog = d.optDouble("fog_area_fraction").takeUnless { it.isNaN() }
            val nd = e.next?.optJSONObject("details")
            val symbol = e.next?.optJSONObject("summary")?.optString("symbol_code")
            val precip = nd?.optDouble("precipitation_amount")?.takeUnless { it.isNaN() }?.div(e.stepHours)
            val prob = nd?.optDouble("probability_of_precipitation")?.takeUnless { it.isNaN() }
            val thunder = nd?.optDouble("probability_of_thunder")?.takeUnless { it.isNaN() } ?: 0.0
            var code = symbolToCode(symbol)
            if (thunder >= 40 && (code ?: 0) < 95) code = 95

            values["temperature_2m"]!![i] = temp
            values["apparent_temperature"]!![i] = if (temp != null && rh != null && windMs != null) apparent(temp, rh, windMs) else temp
            values["precipitation"]!![i] = precip
            values["precipitation_probability"]!![i] = prob
            values["weather_code"]!![i] = code?.toDouble()
            values["wind_speed_10m"]!![i] = windMs?.times(3.6)
            values["wind_gusts_10m"]!![i] = gustMs?.times(3.6)
            values["wind_direction_10m"]!![i] = d.optDouble("wind_from_direction").takeUnless { it.isNaN() }
            values["cloud_cover"]!![i] = d.optDouble("cloud_area_fraction").takeUnless { it.isNaN() }
            values["uv_index"]!![i] = d.optDouble("ultraviolet_index_clear_sky").takeUnless { it.isNaN() }
            values["visibility"]!![i] = if (fog != null && fog >= 50) 500.0 else null
            values["snowfall"]!![i] = if (symbol?.contains("snow") == true && temp != null && temp <= 1) (precip ?: 0.0) else 0.0
        }

        val sunrise = HashMap<LocalDate, LocalDateTime>()
        val sunset = HashMap<LocalDate, LocalDateTime>()
        var day = start
        while (!day.isAfter(end)) {
            Sun.times(p.lat, p.lon, day, zone)?.let { (r, s) -> sunrise[day] = r; sunset[day] = s }
            day = day.plusDays(1)
        }
        return LocationForecast(times, values, BooleanArray(times.size), sunrise, sunset, provider = "MET Norway")
    }

    /** Température ressentie (formule de Steadman, prend en compte vent et humidité). */
    private fun apparent(t: Double, rh: Double, windMs: Double): Double {
        val e = rh / 100 * 6.105 * exp(17.27 * t / (237.7 + t))
        return t + 0.33 * e - 0.70 * windMs - 4.00
    }

    private fun symbolToCode(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val k = s.substringBefore('_')
        return when {
            "thunder" in k -> 95
            k == "clearsky" -> 0
            k == "fair" -> 1
            k == "partlycloudy" -> 2
            k == "cloudy" -> 3
            k == "fog" -> 45
            "sleet" in k -> 66
            "snowshowers" in k -> 85
            k == "lightsnow" -> 71
            k == "heavysnow" -> 75
            "snow" in k -> 73
            k == "lightrainshowers" -> 80
            k == "heavyrainshowers" -> 82
            "rainshowers" in k -> 81
            k == "lightrain" -> 61
            k == "heavyrain" -> 65
            "rain" in k -> 63
            else -> 3
        }
    }
}

/** Lever / coucher du soleil (algorithme NOAA simplifié, précision de l'ordre de la minute). */
object Sun {
    fun times(lat: Double, lon: Double, date: LocalDate, zone: ZoneId): Pair<LocalDateTime, LocalDateTime>? {
        val n = date.dayOfYear
        val gamma = 2 * Math.PI / 365 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latR = Math.toRadians(lat)
        val cosH = (cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl))) - tan(latR) * tan(decl)
        if (cosH < -1 || cosH > 1) return null
        val ha = Math.toDegrees(acos(cosH))
        val riseUtcMin = 720 - 4 * (lon + ha) - eqTime
        val setUtcMin = 720 - 4 * (lon - ha) - eqTime
        val midnight = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        val r = LocalDateTime.ofInstant(midnight.plusSeconds((riseUtcMin * 60).toLong()), zone)
        val s = LocalDateTime.ofInstant(midnight.plusSeconds((setUtcMin * 60).toLong()), zone)
        return r to s
    }
}
