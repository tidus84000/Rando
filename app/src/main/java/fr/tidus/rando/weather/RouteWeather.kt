package fr.tidus.rando.weather

import fr.tidus.rando.data.Geo
import fr.tidus.rando.data.Profile
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class Level { OK, INFO, WARN, DANGER }

data class Alert(val level: Level, val text: String)

data class PointWeather(
    val loc: SampleLoc,
    val time: LocalDateTime,
    val temp: Double?,
    val feels: Double?,
    val precip: Double?,
    val precipProb: Double?,
    val code: Int?,
    val wind: Double?,
    val gust: Double?,
    val windDir: Double?,
    val cloud: Double?,
    val cape: Double?,
    val freezing: Double?,
    val visibility: Double?,
    val uv: Double?,
    val snowfall: Double?,
    val meteoFrance: Boolean,
    val night: Boolean,
) {
    val level: Level
        get() = when {
            (code ?: 0) >= 95 || (gust ?: 0.0) >= 70 -> Level.DANGER
            (gust ?: 0.0) >= 50 || (precip ?: 0.0) >= 2 || (cape ?: 0.0) >= 1000 ||
                (visibility ?: 99999.0) < 1000 || (temp ?: 10.0) <= 0 || (snowfall ?: 0.0) > 0 -> Level.WARN
            (precip ?: 0.0) >= 0.3 || (precipProb ?: 0.0) >= 50 || (gust ?: 0.0) >= 35 -> Level.INFO
            else -> Level.OK
        }
}

data class RouteForecast(
    val points: List<PointWeather>,
    val alerts: List<Alert>,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val penalty: Double,
)

data class BestStart(val start: LocalDateTime, val penalty: Double)

object RouteWeather {
    val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH'h'mm", Locale.FRANCE)

    /** Points de contrôle : départ, arrivée, point culminant et un point tous les ~1 à 2 km (max ~16). */
    fun samples(p: Profile): List<SampleLoc> {
        if (p.size == 0) return emptyList()
        val total = p.totalKm
        val step = max(1.0, total / 14.0)
        val idx = sortedSetOf(0, p.size - 1, p.ele.indices.maxByOrNull { p.ele[it] } ?: 0)
        var d = step
        while (d < total - 0.3) {
            idx.add(p.indexAtKm(d))
            d += step
        }
        // retire les points trop proches (< 300 m) sauf départ / arrivée / sommet
        val summit = p.ele.indices.maxByOrNull { p.ele[it] } ?: 0
        val mandatory = setOf(0, p.size - 1, summit)
        val keep = ArrayList<Int>()
        for (i in idx) {
            val prev = keep.lastOrNull()
            if (prev != null && p.km[i] - p.km[prev] < 0.3) {
                when {
                    i !in mandatory -> continue
                    prev !in mandatory -> keep.removeAt(keep.size - 1)
                }
            }
            keep.add(i)
        }
        return keep.map { SampleLoc(it, p.km[it], p.lat[it], p.lon[it], p.ele[it]) }
    }

    fun evaluate(
        samples: List<SampleLoc>,
        forecasts: List<LocationForecast>,
        hours: DoubleArray,
        start: LocalDateTime,
    ): RouteForecast? {
        if (samples.isEmpty() || forecasts.size != samples.size) return null
        val pts = ArrayList<PointWeather>()
        for ((i, s) in samples.withIndex()) {
            val t = start.plusMinutes((hours[s.index] * 60).roundToLong())
            val f = forecasts[i]
            val h = f.indexOf(t)
            if (h < 0) return null
            val day = t.toLocalDate()
            val sr = f.sunrise[day]
            val ss = f.sunset[day]
            val night = (sr != null && t.isBefore(sr)) || (ss != null && t.isAfter(ss))
            pts.add(
                PointWeather(
                    loc = s, time = t,
                    temp = f.v("temperature_2m", h),
                    feels = f.v("apparent_temperature", h),
                    precip = f.v("precipitation", h),
                    precipProb = f.v("precipitation_probability", h),
                    code = f.v("weather_code", h)?.roundToInt(),
                    wind = f.v("wind_speed_10m", h),
                    gust = f.v("wind_gusts_10m", h),
                    windDir = f.v("wind_direction_10m", h),
                    cloud = f.v("cloud_cover", h),
                    cape = f.v("cape", h),
                    freezing = f.v("freezing_level_height", h),
                    visibility = f.v("visibility", h),
                    uv = f.v("uv_index", h),
                    snowfall = f.v("snowfall", h),
                    meteoFrance = f.fromMeteoFrance.getOrElse(h) { false },
                    night = night,
                )
            )
        }
        val end = pts.last().time
        val sunrise = forecasts.first().sunrise[start.toLocalDate()]
        val sunset = forecasts.last().sunset[end.toLocalDate()]
        val alerts = alerts(pts, start, end, sunrise, sunset)
        return RouteForecast(pts, alerts, start, end, sunrise, sunset, penalty(pts, start, end, sunrise, sunset))
    }

    /** Teste les heures de départ de 5h à 15h et garde la plus favorable. */
    fun bestStart(
        samples: List<SampleLoc>,
        forecasts: List<LocationForecast>,
        hours: DoubleArray,
        date: LocalDate,
    ): BestStart? {
        var best: BestStart? = null
        for (h in 5..15) {
            val start = date.atTime(h, 0)
            if (start.isBefore(LocalDateTime.now().minusMinutes(30))) continue
            val r = evaluate(samples, forecasts, hours, start) ?: continue
            if (best == null || r.penalty < best.penalty - 0.5) best = BestStart(start, r.penalty)
        }
        return best
    }

    private fun penalty(pts: List<PointWeather>, start: LocalDateTime, end: LocalDateTime, sr: LocalDateTime?, ss: LocalDateTime?): Double {
        var p = 0.0
        for (w in pts) {
            if ((w.code ?: 0) >= 95) p += 100
            if ((w.cape ?: 0.0) >= 1000) p += 25
            val g = w.gust ?: 0.0
            if (g >= 70) p += 60 else if (g >= 50) p += 20 else if (g >= 35) p += 4
            p += (w.precip ?: 0.0) * 10 + (w.precipProb ?: 0.0) / 10
            if ((w.visibility ?: 99999.0) < 1000) p += 10
            if ((w.temp ?: 10.0) >= 30) p += 10
            if ((w.feels ?: 10.0) <= -5) p += 5
        }
        if (ss != null && end.isAfter(ss)) p += 50 + Duration.between(ss, end).toMinutes() / 3.0
        if (sr != null && start.isBefore(sr)) p += 30
        return p
    }

    private fun alerts(pts: List<PointWeather>, start: LocalDateTime, end: LocalDateTime, sr: LocalDateTime?, ss: LocalDateTime?): List<Alert> {
        val out = ArrayList<Alert>()
        fun where(w: PointWeather) = "vers ${w.time.format(HM)} (km ${fmt1(w.loc.km)}, ${w.loc.ele.roundToInt()} m)"

        pts.firstOrNull { (it.code ?: 0) >= 95 }?.let {
            out.add(Alert(Level.DANGER, "⛈️ ${WeatherCodes.label(it.code)} prévu ${where(it)}. Éviter les crêtes et sommets."))
        } ?: pts.maxByOrNull { it.cape ?: 0.0 }?.takeIf { (it.cape ?: 0.0) >= 1000 }?.let {
            out.add(Alert(Level.WARN, "⚡ Atmosphère instable (CAPE ${it.cape!!.roundToInt()} J/kg) ${where(it)} : orages possibles l'après-midi."))
        }

        pts.maxByOrNull { it.gust ?: 0.0 }?.let { w ->
            val g = w.gust ?: 0.0
            when {
                g >= 70 -> out.add(Alert(Level.DANGER, "💨 Rafales jusqu'à ${g.roundToInt()} km/h ${where(w)}. Dangereux sur les crêtes."))
                g >= 50 -> out.add(Alert(Level.WARN, "💨 Rafales jusqu'à ${g.roundToInt()} km/h ${where(w)}."))
                g >= 35 -> out.add(Alert(Level.INFO, "🍃 Vent sensible, rafales ${g.roundToInt()} km/h ${where(w)}."))
            }
        }

        val maxEle = pts.maxOf { it.loc.ele }
        pts.filter { it.freezing != null }.minByOrNull { it.freezing!! }?.let { w ->
            val f = w.freezing!!
            if (f < maxEle + 300) {
                out.add(
                    Alert(
                        if (f < maxEle) Level.WARN else Level.INFO,
                        "🧊 Isotherme 0 °C vers ${f.roundToInt()} m (point haut ${maxEle.roundToInt()} m) : gel ou verglas possible en altitude.",
                    )
                )
            }
        }
        pts.minByOrNull { it.feels ?: 99.0 }?.let { w ->
            val t = w.temp ?: 99.0
            val fe = w.feels ?: 99.0
            if (t <= 0 || fe <= -5) {
                out.add(Alert(Level.WARN, "🥶 Froid : ${fmtT(w.temp)} (ressenti ${fmtT(w.feels)}) ${where(w)}."))
            }
        }
        pts.firstOrNull { (it.snowfall ?: 0.0) > 0 }?.let {
            out.add(Alert(Level.WARN, "🌨️ Neige ${where(it)} (${fmt1(it.snowfall!!)} cm/h)."))
        }

        val totalRain = pts.sumOf { it.precip ?: 0.0 }
        pts.maxByOrNull { it.precip ?: 0.0 }?.let { w ->
            val r = w.precip ?: 0.0
            val prob = pts.maxOf { it.precipProb ?: 0.0 }
            when {
                r >= 2 -> out.add(Alert(Level.WARN, "🌧️ Pluie soutenue, jusqu'à ${fmt1(r)} mm/h ${where(w)}."))
                r >= 0.3 -> out.add(Alert(Level.INFO, "🌦️ Pluie faible possible (${fmt1(totalRain)} mm sur le parcours)."))
                prob >= 50 -> out.add(Alert(Level.INFO, "☂️ Probabilité de pluie jusqu'à ${prob.roundToInt()} %."))
            }
        }

        pts.minByOrNull { it.visibility ?: 99999.0 }?.let { w ->
            val v = w.visibility ?: 99999.0
            if (v < 1000) out.add(Alert(Level.WARN, "🌫️ Brouillard : visibilité ${v.roundToInt()} m ${where(w)}. Attention à l'orientation."))
        }

        pts.maxByOrNull { it.temp ?: -99.0 }?.let { w ->
            val t = w.temp ?: -99.0
            when {
                t >= 30 -> out.add(Alert(Level.WARN, "🥵 Forte chaleur : ${fmtT(w.temp)} ${where(w)}. Prévoir beaucoup d'eau."))
                t >= 26 -> out.add(Alert(Level.INFO, "🌡️ Chaud : jusqu'à ${fmtT(w.temp)}. Pense à l'eau."))
            }
        }
        pts.maxByOrNull { it.uv ?: 0.0 }?.let { w ->
            val u = w.uv ?: 0.0
            if (u >= 6) out.add(Alert(Level.INFO, "😎 Indice UV ${u.roundToInt()} : crème solaire et casquette."))
        }

        if (ss != null && end.isAfter(ss)) {
            out.add(Alert(Level.WARN, "🌙 Arrivée prévue à ${end.format(HM)}, après le coucher du soleil (${ss.format(HM)}). Prends une frontale."))
        } else if (ss != null && Duration.between(end, ss).toMinutes() < 45) {
            out.add(Alert(Level.INFO, "🌇 Arrivée à ${end.format(HM)}, peu avant le coucher du soleil (${ss.format(HM)})."))
        }
        if (sr != null && start.isBefore(sr)) {
            out.add(Alert(Level.INFO, "🔦 Départ avant le lever du soleil (${sr.format(HM)})."))
        }

        if (out.none { it.level == Level.WARN || it.level == Level.DANGER }) {
            out.add(0, Alert(Level.OK, "✅ Conditions favorables sur tout le parcours."))
        }
        return out.sortedByDescending { it.level.ordinal }
    }

    fun fmt1(v: Double) = String.format(Locale.FRANCE, "%.1f", v)
    fun fmtT(v: Double?) = if (v == null) "—" else "${v.roundToInt()} °C"

    fun walkingHours(p: Profile, pace: Double) = Geo.walkingHours(p, pace)
}
