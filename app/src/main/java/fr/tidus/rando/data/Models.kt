package fr.tidus.rando.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class TrackPoint(val lat: Double, val lon: Double, val ele: Double?)

data class Hike(
    val id: String,
    val name: String,
    val source: String,
    val sourceUrl: String?,
    val points: List<TrackPoint>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (p in points) {
            val a = JSONArray()
            a.put(p.lat)
            a.put(p.lon)
            if (p.ele != null) a.put(p.ele) else a.put(JSONObject.NULL)
            arr.put(a)
        }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("source", source)
            .put("sourceUrl", sourceUrl ?: JSONObject.NULL)
            .put("createdAt", createdAt)
            .put("points", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Hike {
            val arr = o.getJSONArray("points")
            val pts = ArrayList<TrackPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                val ele = if (a.length() > 2 && !a.isNull(2)) a.getDouble(2) else null
                pts.add(TrackPoint(a.getDouble(0), a.getDouble(1), ele))
            }
            return Hike(
                id = o.getString("id"),
                name = o.getString("name"),
                source = o.optString("source", "GPX"),
                sourceUrl = if (o.isNull("sourceUrl")) null else o.optString("sourceUrl"),
                points = pts,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}

/** Profil du parcours : distance cumulée, altitude (complétée) et coordonnées, point par point. */
class Profile(
    val km: DoubleArray,
    val ele: DoubleArray,
    val lat: DoubleArray,
    val lon: DoubleArray,
) {
    val size get() = km.size
    val totalKm get() = if (km.isEmpty()) 0.0 else km.last()

    /** Indice du point le plus proche d'une distance donnée (recherche dichotomique). */
    fun indexAtKm(d: Double): Int {
        var lo = 0
        var hi = km.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (km[mid] < d) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && kotlin.math.abs(km[lo - 1] - d) < kotlin.math.abs(km[lo] - d)) return lo - 1
        return lo
    }
}

data class HikeStats(
    val distanceKm: Double,
    val ascent: Double,
    val descent: Double,
    val minEle: Double,
    val maxEle: Double,
    val durationHours: Double,
)

object Geo {
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun profile(points: List<TrackPoint>): Profile {
        val n = points.size
        val km = DoubleArray(n)
        val lat = DoubleArray(n) { points[it].lat }
        val lon = DoubleArray(n) { points[it].lon }
        for (i in 1 until n) {
            km[i] = km[i - 1] + haversineKm(lat[i - 1], lon[i - 1], lat[i], lon[i])
        }
        // Altitudes manquantes : interpolation entre les voisins connus
        val ele = DoubleArray(n)
        val prev = IntArray(n) { -1 }
        val next = IntArray(n) { -1 }
        var lastKnown = -1
        for (i in 0 until n) {
            if (points[i].ele != null) lastKnown = i
            prev[i] = lastKnown
        }
        lastKnown = -1
        for (i in n - 1 downTo 0) {
            if (points[i].ele != null) lastKnown = i
            next[i] = lastKnown
        }
        for (i in 0 until n) {
            val a = prev[i]
            val b = next[i]
            ele[i] = when {
                a == -1 && b == -1 -> 0.0
                a == -1 -> points[b].ele!!
                b == -1 -> points[a].ele!!
                a == b -> points[a].ele!!
                else -> {
                    val ea = points[a].ele!!
                    val eb = points[b].ele!!
                    val t = if (km[b] - km[a] > 0) (km[i] - km[a]) / (km[b] - km[a]) else 0.0
                    ea + (eb - ea) * t
                }
            }
        }
        return Profile(km, ele, lat, lon)
    }

    /**
     * Temps de marche cumulé (heures) à chaque point, méthode DIN 33466 :
     * 4 km/h à plat, 300 m/h en montée, 500 m/h en descente ; t = max + min/2.
     * Calculé par tronçons de 200 m pour lisser le bruit d'altitude.
     * pace = multiplicateur (1.0 = allure standard, 1.3 = plus lent…)
     */
    fun walkingHours(p: Profile, pace: Double): DoubleArray {
        val n = p.size
        val hours = DoubleArray(n)
        if (n < 2) return hours
        var last = 0
        var acc = 0.0
        for (i in 1 until n) {
            if (p.km[i] - p.km[last] >= 0.2 || i == n - 1) {
                val d = p.km[i] - p.km[last]
                val dz = p.ele[i] - p.ele[last]
                val up = max(dz, 0.0)
                val down = max(-dz, 0.0)
                val h = d / 4.0
                val v = up / 300.0 + down / 500.0
                val t = (max(h, v) + min(h, v) / 2.0) * pace
                for (j in last + 1..i) {
                    val frac = if (d > 1e-9) (p.km[j] - p.km[last]) / d else 1.0
                    hours[j] = acc + t * frac
                }
                acc += t
                last = i
            }
        }
        return hours
    }

    fun stats(p: Profile, pace: Double = 1.0): HikeStats {
        if (p.size == 0) return HikeStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        var up = 0.0
        var down = 0.0
        var ref = p.ele[0]
        for (i in 1 until p.size) {
            val e = p.ele[i]
            if (e - ref > 4) { up += e - ref; ref = e }
            else if (ref - e > 4) { down += ref - e; ref = e }
        }
        val h = walkingHours(p, pace)
        return HikeStats(
            distanceKm = p.totalKm,
            ascent = up,
            descent = down,
            minEle = p.ele.min(),
            maxEle = p.ele.max(),
            durationHours = h.last(),
        )
    }
}
