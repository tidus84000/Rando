package fr.tidus.rando.explore

import android.text.Html
import fr.tidus.rando.data.Geo
import fr.tidus.rando.importers.Geocoding
import fr.tidus.rando.importers.Http
import fr.tidus.rando.importers.SearchLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos

enum class Source(val label: String, val color: Int) {
    KOMOOT("Komoot", 0xFF6AA127.toInt()),
    VISORANDO("Visorando", 0xFF1A7FC1.toInt()),
    ALLTRAILS("AllTrails", 0xFFE8731C.toInt()),
}

/** Une rando trouvée sur un site, avant import. */
data class Candidate(
    val source: Source,
    val name: String,
    val url: String,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double? = null,
    val ascent: Double? = null,
    val durationMin: Int? = null,
    val difficulty: String? = null,
)

/**
 * Extraction générique des randos dans les données JSON des sites (réponses d'API captées
 * dans le navigateur caché, ou JSON inclus dans la page).
 */
object JsonHarvester {
    fun harvest(texts: List<String>, source: Source): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        for (t in texts) {
            for (root in roots(t)) walk(root, source, out, 0)
        }
        return out.values.toList()
    }

    private val callRegex = Regex("""(?:JSON\.parse|setProps|hydrate)\(\s*("(?:[^"\\]|\\.)*")""")
    private val scriptJson = Regex(
        """<script[^>]*type=["']application/(?:ld\+)?json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private fun roots(text: String): List<Any> {
        val t = text.trim()
        val list = ArrayList<Any>()
        if (t.startsWith("{") || t.startsWith("[")) {
            runCatching { list.add(JSONTokener(t).nextValue()) }
            if (list.isNotEmpty()) return list
        }
        callRegex.findAll(t).forEach { m ->
            runCatching {
                val s = JSONTokener(m.groupValues[1]).nextValue() as String
                list.add(JSONTokener(s).nextValue())
            }
        }
        scriptJson.findAll(t).forEach { m -> runCatching { list.add(JSONTokener(m.groupValues[1].trim()).nextValue()) } }
        return list
    }

    private fun walk(node: Any?, source: Source, out: MutableMap<String, Candidate>, depth: Int) {
        if (depth > 40 || out.size > 400) return
        when (node) {
            is JSONObject -> {
                candidate(node, source)?.let { out.putIfAbsent(it.url, it) }
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), source, out, depth + 1)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), source, out, depth + 1)
        }
    }

    private fun num(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            val v = o.opt(k)
            when (v) {
                is Number -> return v.toDouble()
                is String -> v.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun latLng(o: JSONObject): Pair<Double, Double>? {
        val lat = num(o, "lat", "latitude") ?: return null
        val lng = num(o, "lng", "lon", "longitude") ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) return null
        return lat to lng
    }

    private fun coords(o: JSONObject): Pair<Double, Double>? {
        for (k in listOf("start_point", "startPoint", "startpoint", "_geoloc", "geoloc", "trailhead", "start", "location", "position")) {
            val c = o.optJSONObject(k) ?: continue
            latLng(c)?.let { return it }
        }
        return latLng(o)
    }

    private val komootLink = Regex("""/(smart_tours?|smarttour|tours?)/([a-zA-Z]?\d+)""")

    private fun komootUrl(o: JSONObject): String? {
        val href = o.optJSONObject("_links")?.optJSONObject("self")?.optString("href").orEmpty()
        komootLink.find(href)?.let { m ->
            val smart = m.groupValues[1].startsWith("smart")
            return "https://www.komoot.com/${if (smart) "smarttour" else "tour"}/${m.groupValues[2]}"
        }
        for (k in listOf("url", "link", "share_url")) {
            val u = o.optString(k)
            komootLink.find(u)?.let { m ->
                val smart = m.groupValues[1].startsWith("smart")
                return "https://www.komoot.com/${if (smart) "smarttour" else "tour"}/${m.groupValues[2]}"
            }
        }
        val id = o.opt("id")?.toString() ?: return null
        if (!Regex("""^[a-zA-Z]?\d{3,}$""").matches(id)) return null
        val isTour = o.has("sport") || o.has("distance") || o.has("elevation_up") || o.has("duration")
        if (!isTour) return null
        val smart = id.first().isLetter() || o.optString("type").contains("smart", true)
        return "https://www.komoot.com/${if (smart) "smarttour" else "tour"}/$id"
    }

    private fun allTrailsUrl(o: JSONObject): String? {
        val slug = o.optString("slug")
        if (slug.startsWith("trail/")) return "https://www.alltrails.com/$slug"
        for (k in listOf("url", "link", "href", "canonical_url")) {
            val u = o.optString(k)
            if (u.contains("/trail/")) return if (u.startsWith("http")) u else "https://www.alltrails.com/${u.trimStart('/')}"
        }
        return null
    }

    private fun candidate(o: JSONObject, source: Source): Candidate? {
        val name = (o.optString("name").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("title").takeIf { it.isNotBlank() && it != "null" }) ?: return null
        if (name.length > 160) return null
        val (lat, lon) = coords(o) ?: return null
        val url = when (source) {
            Source.KOMOOT -> komootUrl(o)
            Source.ALLTRAILS -> allTrailsUrl(o)
            Source.VISORANDO -> null
        } ?: return null
        val dist = num(o, "distance", "length", "distance_m")?.let { if (it > 200) it / 1000 else it }
        val up = num(o, "elevation_up", "elevation_gain", "elevationGain", "elevation_gain_m")
        val dur = num(o, "duration", "duration_minutes", "durationMinutes")?.let { if (it > 2000) (it / 60).toInt() else it.toInt() }
        val diff = o.optJSONObject("difficulty")?.optString("grade")?.ifBlank { null }
            ?: o.optString("difficulty_rating").ifBlank { null }
            ?: (o.opt("difficulty") as? String)
        return Candidate(source, Html.fromHtml(name, Html.FROM_HTML_MODE_LEGACY).toString(), url, lat, lon, dist, up, dur, diff)
    }
}

/** Visorando : listes des randos des communes voisines, puis position de départ lue sur chaque fiche. */
object VisorandoDiscovery {
    private val cache = ConcurrentHashMap<String, Candidate>()
    private val linkRegex = Regex("""(?:https://www\.visorando\.com)?/randonnee-[a-z0-9-]+/""")

    suspend fun around(lat: Double, lon: Double, radiusKm: Double): List<Candidate> = withContext(Dispatchers.IO) {
        val d = radiusKm * 0.6
        val dLat = d / 111.0
        val dLon = d / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        val probes = listOf(lat to lon, lat + dLat to lon, lat - dLat to lon, lat to lon + dLon, lat to lon - dLon)
        val cities = coroutineScope {
            probes.map { (a, b) -> async { Geocoding.reverse(a, b)?.city } }.awaitAll()
        }.filterNotNull().distinct().take(5)

        val links = LinkedHashSet<String>()
        for (city in cities) {
            val html = Http.getText(SearchLinks.visorando(city), "text/html") ?: continue
            linkRegex.findAll(html).forEach { m ->
                val u = m.value
                links.add(if (u.startsWith("http")) u else "https://www.visorando.com$u")
            }
            if (links.size >= 45) break
        }
        val sem = Semaphore(6)
        coroutineScope {
            links.take(45).map { url -> async { sem.withPermit { details(url) } } }.awaitAll()
        }.filterNotNull().filter { Geo.haversineKm(lat, lon, it.lat, it.lon) <= radiusKm * 1.15 }
    }

    private val metaLat = Regex("""place:location:latitude["'][^>]*content=["']([-\d.]+)""")
    private val metaLon = Regex("""place:location:longitude["'][^>]*content=["']([-\d.]+)""")
    private val textPos = Regex("""([NS])\s*([\d.]+)°\s*/\s*([EOW])\s*([\d.]+)°""")

    private fun details(url: String): Candidate? {
        cache[url]?.let { return it }
        val html = Http.getText(url, "text/html") ?: return null
        val text = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
        var lat = metaLat.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        var lon = metaLon.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        if (lat == null || lon == null) {
            val m = textPos.find(text) ?: return null
            lat = m.groupValues[2].toDouble() * if (m.groupValues[1] == "S") -1 else 1
            lon = m.groupValues[4].toDouble() * if (m.groupValues[3] == "E") 1 else -1
        }
        val title = Regex("""property=["']og:title["'][^>]*content=["']([^"']+)""").find(html)?.groupValues?.get(1)
            ?: Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: return null
        val name = Html.fromHtml(title, Html.FROM_HTML_MODE_LEGACY).toString().substringBefore(" - Visorando").trim()
        val dist = Regex("""Distance\s*:\s*([\d.,]+)\s*km""").find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val up = Regex("""Dénivelé positif\s*:\s*([\d\s]+)\s*m""").find(text)?.groupValues?.get(1)?.replace(" ", "")?.toDoubleOrNull()
        val dur = Regex("""Durée moyenne\s*:\s*(\d+)\s*h\s*(\d+)?""").find(text)?.let {
            it.groupValues[1].toInt() * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
        }
        val diff = Regex("""Difficulté\s*:\s*([A-Za-zéÉ]+)""").find(text)?.groupValues?.get(1)
        val c = Candidate(Source.VISORANDO, name, url, lat!!, lon!!, dist, up, dur, diff)
        cache[url] = c
        return c
    }
}

/** Komoot : essai direct de l'API de découverte (sans navigateur). */
object KomootDirect {
    suspend fun around(lat: Double, lon: Double, radiusKm: Double): List<Candidate> = withContext(Dispatchers.IO) {
        val urls = listOf(
            String.format(Locale.US, "https://api.komoot.de/v007/discover_tours/?lat=%.5f&lng=%.5f&max_distance=%d&sport=hike&limit=40", lat, lon, (radiusKm * 1000).toInt()),
            String.format(Locale.US, "https://www.komoot.com/api/v007/discover_tours/?lat=%.5f&lng=%.5f&max_distance=%d&sport=hike&limit=40", lat, lon, (radiusKm * 1000).toInt()),
        )
        for (u in urls) {
            val txt = Http.getText(u, "application/hal+json, application/json") ?: continue
            val found = JsonHarvester.harvest(listOf(txt), Source.KOMOOT)
            if (found.isNotEmpty()) return@withContext found
        }
        emptyList()
    }
}
