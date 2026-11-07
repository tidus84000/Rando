package fr.tidus.rando.weather

import fr.tidus.rando.importers.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** Un point du parcours où l'on demande la météo. */
data class SampleLoc(val index: Int, val km: Double, val lat: Double, val lon: Double, val ele: Double)

/** Prévision horaire pour un point. values[var][heure] (null = donnée absente). */
class LocationForecast(
    val times: List<LocalDateTime>,
    val values: Map<String, Array<Double?>>,
    val fromMeteoFrance: BooleanArray,
    val sunrise: Map<LocalDate, LocalDateTime>,
    val sunset: Map<LocalDate, LocalDateTime>,
) {
    fun indexOf(t: LocalDateTime): Int {
        val rounded = (if (t.minute >= 30) t.plusHours(1) else t).withMinute(0).withSecond(0).withNano(0)
        return times.indexOf(rounded)
    }

    fun v(name: String, i: Int): Double? = values[name]?.getOrNull(i)
}

/**
 * Météo via Open-Meteo (gratuit, sans clé).
 * Priorité au modèle Météo-France AROME 1,3 km / ARPEGE (« meteofrance_seamless »),
 * complété par le meilleur modèle disponible pour les échéances lointaines ou les variables manquantes.
 * L'altitude réelle de chaque point est transmise pour corriger la température.
 */
object WeatherService {
    val HOURLY = listOf(
        "temperature_2m", "apparent_temperature", "precipitation", "precipitation_probability",
        "weather_code", "wind_speed_10m", "wind_gusts_10m", "wind_direction_10m", "cloud_cover",
        "cape", "freezing_level_height", "visibility", "uv_index", "snowfall",
    )

    suspend fun fetch(points: List<SampleLoc>, start: LocalDate, end: LocalDate): List<LocationForecast> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val fr = async { runCatching { call(points, start, end, "meteofrance_seamless") } }
                val best = async { runCatching { call(points, start, end, "best_match") } }
                val a = fr.await()
                val b = best.await()
                val frList = a.getOrNull()
                val bestList = b.getOrNull()
                when {
                    frList == null && bestList == null ->
                        throw (b.exceptionOrNull() ?: a.exceptionOrNull() ?: Exception("Météo indisponible"))
                    frList == null -> bestList!!.map { it.toForecast(false) }
                    bestList == null -> frList.map { it.toForecast(true) }
                    else -> frList.indices.map { merge(frList[it], bestList.getOrNull(it)) }
                }
            }
        }

    private class Raw(
        val times: List<LocalDateTime>,
        val values: Map<String, Array<Double?>>,
        val sunrise: Map<LocalDate, LocalDateTime>,
        val sunset: Map<LocalDate, LocalDateTime>,
    ) {
        fun toForecast(isFr: Boolean) = LocationForecast(
            times, values,
            BooleanArray(times.size) { isFr && values["temperature_2m"]?.getOrNull(it) != null },
            sunrise, sunset,
        )
    }

    private fun merge(fr: Raw, best: Raw?): LocationForecast {
        if (best == null || best.times != fr.times) return fr.toForecast(true)
        val merged = HashMap<String, Array<Double?>>()
        for (name in HOURLY) {
            val a = fr.values[name]
            val b = best.values[name]
            merged[name] = Array(fr.times.size) { i -> a?.getOrNull(i) ?: b?.getOrNull(i) }
        }
        val isFr = BooleanArray(fr.times.size) { fr.values["temperature_2m"]?.getOrNull(it) != null }
        return LocationForecast(fr.times, merged, isFr, fr.sunrise.ifEmpty { best.sunrise }, fr.sunset.ifEmpty { best.sunset })
    }

    private fun call(points: List<SampleLoc>, start: LocalDate, end: LocalDate, model: String): List<Raw> {
        val lat = points.joinToString(",") { String.format(Locale.US, "%.5f", it.lat) }
        val lon = points.joinToString(",") { String.format(Locale.US, "%.5f", it.lon) }
        val ele = points.joinToString(",") { String.format(Locale.US, "%.0f", it.ele) }
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon&elevation=$ele" +
            "&hourly=${HOURLY.joinToString(",")}" +
            "&daily=sunrise,sunset" +
            "&models=$model&timezone=auto" +
            "&wind_speed_unit=kmh" +
            "&start_date=$start&end_date=$end"
        val body = Http.plain.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val reason = runCatching { JSONObject(txt).optString("reason") }.getOrNull()
                throw Exception(reason?.ifBlank { null } ?: "Erreur météo (${r.code})")
            }
            txt
        }
        val trimmed = body.trimStart()
        val objs = if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } else listOf(JSONObject(trimmed))
        return objs.map { parse(it) }
    }

    private fun parse(o: JSONObject): Raw {
        val hourly = o.getJSONObject("hourly")
        val t = hourly.getJSONArray("time")
        val times = (0 until t.length()).map { LocalDateTime.parse(t.getString(it)) }
        val values = HashMap<String, Array<Double?>>()
        for (name in HOURLY) {
            val arr = hourly.optJSONArray(name) ?: continue
            values[name] = Array(times.size) { i ->
                if (i >= arr.length() || arr.isNull(i)) null else arr.optDouble(i).takeUnless { it.isNaN() }
            }
        }
        val sunrise = HashMap<LocalDate, LocalDateTime>()
        val sunset = HashMap<LocalDate, LocalDateTime>()
        o.optJSONObject("daily")?.let { d ->
            val days = d.optJSONArray("time")
            val sr = d.optJSONArray("sunrise")
            val ss = d.optJSONArray("sunset")
            if (days != null) for (i in 0 until days.length()) {
                val day = LocalDate.parse(days.getString(i))
                sr?.optString(i)?.takeIf { it.isNotBlank() && it != "null" }?.let { sunrise[day] = LocalDateTime.parse(it) }
                ss?.optString(i)?.takeIf { it.isNotBlank() && it != "null" }?.let { sunset[day] = LocalDateTime.parse(it) }
            }
        }
        return Raw(times, values, sunrise, sunset)
    }
}

object WeatherCodes {
    fun label(code: Int?): String = when (code) {
        null -> "—"
        0 -> "Ciel clair"
        1 -> "Plutôt dégagé"
        2 -> "Partiellement nuageux"
        3 -> "Couvert"
        45, 48 -> "Brouillard"
        51, 53, 55 -> "Bruine"
        56, 57 -> "Bruine verglaçante"
        61 -> "Pluie faible"
        63 -> "Pluie"
        65 -> "Forte pluie"
        66, 67 -> "Pluie verglaçante"
        71 -> "Neige faible"
        73 -> "Neige"
        75 -> "Forte neige"
        77 -> "Grains de neige"
        80 -> "Averses faibles"
        81 -> "Averses"
        82 -> "Fortes averses"
        85, 86 -> "Averses de neige"
        95 -> "Orage"
        96, 99 -> "Orage avec grêle"
        else -> "Code $code"
    }

    fun emoji(code: Int?, night: Boolean = false): String = when (code) {
        null -> "❔"
        0 -> if (night) "🌙" else "☀️"
        1 -> if (night) "🌙" else "🌤️"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 80, 81 -> "🌦️"
        56, 57, 66, 67 -> "🧊"
        61, 63, 65, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        95, 96, 99 -> "⛈️"
        else -> "❔"
    }
}
