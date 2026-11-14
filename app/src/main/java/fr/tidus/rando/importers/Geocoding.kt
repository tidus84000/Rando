package fr.tidus.rando.importers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.cos

data class Place(val label: String, val city: String?, val lat: Double, val lon: Double)

/** Géocodage d'adresses via Photon (OpenStreetMap, gratuit, sans clé). */
object Geocoding {
    private fun call(url: String): JSONObject? = runCatching {
        Http.plain.newCall(Request.Builder().url(url).header("User-Agent", "Rando-Android").build())
            .execute().use { if (it.isSuccessful) JSONObject(it.body!!.string()).takeIf { o -> o.has("features") } else null }
    }.getOrNull()

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "6")
            .addQueryParameter("lang", "fr")
            .build().toString()
        val photon = call(url)
        if (photon != null) parse(photon) else nominatimSearch(query)
    }

    suspend fun reverse(lat: Double, lon: Double): Place? = withContext(Dispatchers.IO) {
        val url = String.format(Locale.US, "https://photon.komoot.io/reverse?lat=%.6f&lon=%.6f&lang=fr", lat, lon)
        val photon = call(url)
        if (photon != null) parse(photon).firstOrNull() else nominatimReverse(lat, lon)
    }

    // ---------- Secours : Nominatim (OpenStreetMap), sans clé ----------
    private const val NOMINATIM_UA = "Rando-Android/0.9 github.com/tidus84000/Rando"

    private fun nominatim(url: String): String? = runCatching {
        Http.plain.newCall(Request.Builder().url(url).header("User-Agent", NOMINATIM_UA).build())
            .execute().use { if (it.isSuccessful) it.body!!.string() else null }
    }.getOrNull()

    private fun nominatimPlace(o: JSONObject): Place? {
        val lat = o.optString("lat").toDoubleOrNull() ?: return null
        val lon = o.optString("lon").toDoubleOrNull() ?: return null
        val a = o.optJSONObject("address")
        val city = a?.let { ad ->
            listOf("city", "town", "village", "municipality").firstNotNullOfOrNull { k -> ad.optString(k).takeIf { it.isNotBlank() } }
        }
        val label = o.optString("display_name").split(", ").take(3).joinToString(", ").ifBlank { city ?: "Lieu" }
        return Place(label, city, lat, lon)
    }

    private fun nominatimSearch(q: String): List<Place> {
        val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "6").addQueryParameter("addressdetails", "1")
            .addQueryParameter("accept-language", "fr").build().toString()
        val body = nominatim(url) ?: return emptyList()
        val arr = runCatching { org.json.JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { nominatimPlace(arr.getJSONObject(it)) }
    }

    private fun nominatimReverse(lat: Double, lon: Double): Place? {
        val url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?lat=%.6f&lon=%.6f&format=jsonv2&addressdetails=1&accept-language=fr", lat, lon)
        val body = nominatim(url) ?: return null
        return runCatching { nominatimPlace(JSONObject(body)) }.getOrNull()
    }

    private fun parse(o: JSONObject?): List<Place> {
        val feats = o?.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val name = p.optString("name").takeIf { it.isNotBlank() }
            val street = listOfNotNull(
                p.optString("housenumber").takeIf { it.isNotBlank() },
                p.optString("street").takeIf { it.isNotBlank() },
            ).joinToString(" ").ifBlank { null }
            val city = (p.optString("city").takeIf { it.isNotBlank() }
                ?: p.optString("town").takeIf { it.isNotBlank() }
                ?: p.optString("village").takeIf { it.isNotBlank() }
                ?: if (p.optString("type") == "city") name else null)
            val label = listOfNotNull(name ?: street, if (name != null) street else null,
                listOfNotNull(p.optString("postcode").takeIf { it.isNotBlank() }, city).joinToString(" ").ifBlank { null })
                .distinct().joinToString(", ")
            out.add(Place(label.ifBlank { "Lieu" }, city, c.getDouble(1), c.getDouble(0)))
        }
        return out
    }

    fun slug(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}

/** Adresses de recherche des sites, centrées sur un point et un rayon. */
object SearchLinks {
    fun komoot(lat: Double, lon: Double, radiusKm: Int): String =
        String.format(
            Locale.US,
            "https://www.komoot.com/fr-fr/discover/Position/@%.5f,%.5f/tours?sport=hike&map=true&max_distance=%d",
            lat, lon, radiusKm * 1000,
        )

    fun allTrails(lat: Double, lon: Double, radiusKm: Int): String {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        return String.format(
            Locale.US,
            "https://www.alltrails.com/fr/explore?b_tl_lat=%.5f&b_tl_lng=%.5f&b_br_lat=%.5f&b_br_lng=%.5f",
            lat + dLat, lon - dLon, lat - dLat, lon + dLon,
        )
    }

    /** Visorando : page des randos autour d'une commune. */
    fun visorando(city: String?): String =
        if (city.isNullOrBlank()) "https://www.visorando.com/"
        else "https://www.visorando.com/randonnee-${Geocoding.slug(city)}.html"
}
