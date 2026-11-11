package fr.tidus.rando.importers

import android.text.Html
import fr.tidus.rando.data.Hike
import fr.tidus.rando.data.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Point d'entrée : un lien collé ou partagé → une rando enregistrable. */
object LinkImporter {
    private val urlRegex = Regex("""https?://[^\s"'<>]+""")

    fun extractUrl(text: String): String? = urlRegex.find(text)?.value?.trimEnd('.', ',', ')', ';')

    suspend fun import(input: String): Hike = withContext(Dispatchers.IO) {
        val url = extractUrl(input) ?: throw ImportException("Aucun lien trouvé. Colle l'adresse d'une rando Komoot ou Visorando.")
        val host = url.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val hike = when {
            "alltrails" in host -> throw ImportException(
                "AllTrails protège ses pages contre les robots : la rando s'ouvre dans le navigateur intégré.",
                needsBrowser = true,
            )
            "komoot" in host || "kmt.io" in host -> KomootImporter.import(url)
            "visorando" in host -> VisorandoImporter.import(url)
            else -> GenericGpx.import(url)
        }
        ElevationService.fillMissing(hike)
    }

    fun newHike(name: String, source: String, url: String?, points: List<TrackPoint>) = Hike(
        id = UUID.randomUUID().toString(),
        name = name.ifBlank { "Rando sans nom" },
        source = source,
        sourceUrl = url,
        points = points,
    )

    fun fromGpxText(text: String, source: String, url: String?, fallbackName: String): Hike {
        val parsed = GpxParser.parse(text)
        val detected = GpxParser.detectSource(text)
        val src = if (source == "GPX" && detected != null) detected.first else source
        return newHike(parsed.name ?: fallbackName, src, url ?: detected?.second, parsed.points)
    }
}

object KomootImporter {
    // ex. /tour/123456, /smarttour/e834133009, /fr-fr/smarttour/123
    private val idRegex = Regex("""/(smart)?tours?/([a-zA-Z]?\d+)""")

    fun import(url: String): Hike {
        var finalUrl = url
        var m = idRegex.find(finalUrl)
        if (m == null) {
            // lien court (partage) : on suit les redirections
            finalUrl = runCatching { Http.get(url).use { it.request.url.toString() } }.getOrDefault(url)
            m = idRegex.find(finalUrl) ?: throw ImportException("Lien Komoot non reconnu (il faut un lien de tour).")
        }
        val smart = m.groupValues[1].isNotEmpty()
        val id = m.groupValues[2]
        val shareToken = finalUrl.toHttpUrlOrNull()?.queryParameter("share_token")
            ?: url.toHttpUrlOrNull()?.queryParameter("share_token")

        val kinds = if (smart) listOf("smart_tours", "tours") else listOf("tours", "smart_tours")
        for (kind in kinds) {
            val api = buildString {
                append("https://api.komoot.de/v007/").append(kind).append('/').append(id)
                append("?_embedded=coordinates")
                if (shareToken != null) append("&share_token=").append(shareToken)
            }
            val json = runCatching {
                Http.get(api, "application/hal+json, application/json").use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull() ?: continue
            val o = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val items = o.optJSONObject("_embedded")?.optJSONObject("coordinates")?.optJSONArray("items")
            if (items != null && items.length() >= 2) {
                val pts = ArrayList<TrackPoint>(items.length())
                for (i in 0 until items.length()) {
                    val c = items.getJSONObject(i)
                    val alt = c.optDouble("alt", Double.NaN)
                    pts.add(TrackPoint(c.getDouble("lat"), c.getDouble("lng"), if (alt.isNaN()) null else alt))
                }
                val name = o.optString("name").ifBlank { "Rando Komoot $id" }
                return LinkImporter.newHike(name, "Komoot", finalUrl, pts)
            }
        }

        // Plan B : le tracé est inclus dans la page web elle-même
        val html = Http.getText(finalUrl, "text/html")
        if (html != null) {
            val pts = PageScraper.coordinates(html)
            if (pts.size >= 10) {
                val name = PageScraper.title(html)?.substringBefore(" | ")?.substringBefore(" - komoot")
                    ?.trim()?.ifBlank { null } ?: "Rando Komoot $id"
                return LinkImporter.newHike(name, "Komoot", finalUrl, pts)
            }
        }

        // Rando privée : export GPX avec la session ouverte dans le navigateur intégré
        for (base in listOf("https://www.komoot.com", "https://www.komoot.de")) {
            val gpxUrl = "$base/api/v007/tours/$id.gpx" + (shareToken?.let { "?share_token=$it" } ?: "")
            val gpx = Http.getText(gpxUrl, "application/gpx+xml, */*", finalUrl)
            if (GpxParser.looksLikeGpx(gpx)) {
                return LinkImporter.fromGpxText(gpx!!, "Komoot", finalUrl, "Rando Komoot $id")
            }
        }
        throw ImportException(
            "Cette rando Komoot est privée ou inaccessible. Ouvre-la dans le navigateur intégré, connecte-toi, puis touche « Télécharger GPX ».",
            needsBrowser = true,
        )
    }
}

object VisorandoImporter {
    fun import(url: String): Hike {
        val html = Http.getText(url, "text/html")
            ?: throw ImportException("Impossible d'ouvrir la page Visorando.", needsBrowser = true)

        val title = metaContent(html, "og:title")
            ?: Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(html)?.groupValues?.get(1)
        val name = title?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            ?.substringBefore(" - Visorando")?.trim()
            ?.ifBlank { null } ?: "Rando Visorando"

        val id = Regex("""idRandonnee=(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""n°\s*(\d{3,})""").find(html)?.groupValues?.get(1)
            ?: Regex("""randonnee-(\d+)""").find(url)?.groupValues?.get(1)

        val candidates = LinkedHashSet<String>()
        // 1) liens présents dans la page
        Regex("""(?:href|data-href|data-url)=["']([^"']*gpx[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { candidates.add(absolute(it.groupValues[1])) }
        // 2) adresses de téléchargement connues
        if (id != null) {
            candidates.add("https://www.visorando.com/index.php?component=rando&task=getRandoGpx&idRandonnee=$id")
            candidates.add("https://www.visorando.com/clic-telecharger-gpx-$id.html")
            candidates.add("https://www.visorando.com/gpx-$id.html")
        }
        for (c in candidates) {
            if (c.startsWith("#") || c.endsWith("#")) continue
            val text = Http.getText(c, "application/gpx+xml, application/xml, */*", url)
            if (GpxParser.looksLikeGpx(text)) {
                val parsed = GpxParser.parse(text!!)
                return LinkImporter.newHike(name, "Visorando", url, parsed.points)
            }
        }
        val scraped = PageScraper.bestTrack(html)
        if (scraped.size >= 20) return LinkImporter.newHike(name, "Visorando", url, scraped)
        throw ImportException(
            "Visorando demande d'être connecté pour récupérer le tracé. Ouvre la page dans le navigateur intégré, connecte-toi (compte gratuit) puis touche « GPX ».",
            needsBrowser = true,
        )
    }

    private fun metaContent(html: String, prop: String): String? {
        val r1 = Regex("""<meta[^>]+(?:property|name)=["']${Regex.escape(prop)}["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val r2 = Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']${Regex.escape(prop)}["']""", RegexOption.IGNORE_CASE)
        return (r1.find(html) ?: r2.find(html))?.groupValues?.get(1)
    }

    private fun absolute(href: String): String {
        val h = Html.fromHtml(href, Html.FROM_HTML_MODE_LEGACY).toString()
        return when {
            h.startsWith("http") -> h
            h.startsWith("//") -> "https:$h"
            h.startsWith("/") -> "https://www.visorando.com$h"
            else -> "https://www.visorando.com/$h"
        }
    }
}

/** Extraction de coordonnées incluses dans le HTML/JSON d'une page (Komoot, etc.). */
object PageScraper {
    private val coord = Regex(
        """\\?"lat\\?"\s*:\s*(-?\d+\.\d+)\s*,\s*\\?"(?:lng|lon)\\?"\s*:\s*(-?\d+\.\d+)(?:\s*,\s*\\?"(?:alt|ele)\\?"\s*:\s*(-?\d+(?:\.\d+)?))?"""
    )

    fun coordinates(html: String): List<TrackPoint> {
        // on garde la plus longue suite de points consécutifs (le tracé, pas les points d'intérêt)
        var best = emptyList<TrackPoint>()
        var cur = ArrayList<TrackPoint>()
        var lastEnd = -1
        for (m in coord.findAll(html)) {
            if (lastEnd >= 0 && m.range.first - lastEnd > 80) {
                if (cur.size > best.size) best = cur
                cur = ArrayList()
            }
            cur.add(TrackPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDoubleOrNull()))
            lastEnd = m.range.last
        }
        if (cur.size > best.size) best = cur
        return best
    }

    /** Tracé le plus probable d'une page : suites de coordonnées ou polyline encodée (AllTrails…). */
    fun bestTrack(html: String): List<TrackPoint> {
        val coords = coordinates(html)
        val poly = polylines(html)
        return if (poly.size > coords.size) poly else coords
    }

    private val polyRegex = Regex(
        """\\?"(?:polyline|encodedPolyline|encoded_polyline|points|line|polylinePoints|pointsData|points_data|encodedPath|encoded_path|geometry)\\?"\s*:\s*\\?"((?:[^"\\]|\\.){40,})\\?""""
    )

    fun polylines(html: String): List<TrackPoint> {
        var best = emptyList<TrackPoint>()
        for (m in polyRegex.findAll(html)) {
            val enc = unescape(m.groupValues[1])
            for (precision in listOf(5, 6)) {
                val pts = runCatching { decodePolyline(enc, precision) }.getOrNull() ?: continue
                if (pts.size > best.size && plausible(pts)) best = pts
            }
        }
        return best
    }

    private fun unescape(s: String): String {
        var t = s
        // la chaîne peut être échappée une ou deux fois (JSON dans du JSON)
        repeat(2) {
            t = t.replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\\/", "/").replace("\u0000", "\\")
        }
        return t
    }

    fun decodePolyline(enc: String, precision: Int): List<TrackPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<TrackPoint>()
        var i = 0
        var lat = 0L
        var lng = 0L
        while (i < enc.length) {
            var shift = 0
            var result = 0L
            var b: Int
            do {
                b = enc[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && i < enc.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            if (i >= enc.length) break
            shift = 0
            result = 0L
            do {
                b = enc[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && i < enc.length)
            lng += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out.add(TrackPoint(lat / factor, lng / factor, null))
        }
        return out
    }

    private fun plausible(pts: List<TrackPoint>): Boolean {
        if (pts.size < 20) return false
        if (pts.any { it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0 }) return false
        var total = 0.0
        var maxStep = 0.0
        for (i in 1 until pts.size) {
            val d = fr.tidus.rando.data.Geo.haversineKm(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
            total += d
            if (d > maxStep) maxStep = d
        }
        return total in 0.3..300.0 && maxStep < 5.0
    }

    /** Polylines dans les URL d'images de carte (Mapbox « path-…(…) », Google « path=enc:… »). */
    fun staticMapPolylines(html: String): List<TrackPoint> {
        var best = emptyList<TrackPoint>()
        val encs = ArrayList<String>()
        Regex("""path-[^()/]*\(([^)]{40,})\)""").findAll(html).forEach { encs.add(it.groupValues[1]) }
        Regex("""path=[^"'&]*enc:([^"'&|]{40,})""").findAll(html).forEach { encs.add(it.groupValues[1]) }
        for (raw in encs) {
            val enc = runCatching { java.net.URLDecoder.decode(raw.replace("&amp;", "&"), "UTF-8") }.getOrDefault(raw)
            for (precision in listOf(5, 6)) {
                val pts = runCatching { decodePolyline(enc, precision) }.getOrNull() ?: continue
                if (pts.size > best.size && plausible(pts)) best = pts
            }
        }
        return best
    }

    private val jsPair = Regex("""\[\s*(-?\d{1,3}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})(?:\s*,\s*-?\d+(?:\.\d+)?)?\s*]""")

    /** Tableaux JavaScript de paires [a, b] ; l'ordre lat/lon est choisi grâce au point de repère. */
    fun jsPairs(text: String, hint: Pair<Double, Double>?): List<TrackPoint> {
        var bestRun = emptyList<Pair<Double, Double>>()
        var cur = ArrayList<Pair<Double, Double>>()
        var lastEnd = -10
        for (m in jsPair.findAll(text)) {
            if (m.range.first - lastEnd > 3) {
                if (cur.size > bestRun.size) bestRun = cur
                cur = ArrayList()
            }
            cur.add(m.groupValues[1].toDouble() to m.groupValues[2].toDouble())
            lastEnd = m.range.last
        }
        if (cur.size > bestRun.size) bestRun = cur
        if (bestRun.size < 20) return emptyList()
        val (a, b) = bestRun.first()
        val latFirst = if (hint != null) {
            fr.tidus.rando.data.Geo.haversineKm(a, b, hint.first, hint.second) <=
                fr.tidus.rando.data.Geo.haversineKm(b, a, hint.first, hint.second)
        } else !(b in 41.0..52.0 && a in -6.0..10.0)
        val pts = bestRun.map { (x, y) -> if (latFirst) TrackPoint(x, y, null) else TrackPoint(y, x, null) }
        return if (plausible(pts)) pts else emptyList()
    }

    fun title(html: String): String? {
        val og = Regex("""<meta[^>]+property=["']og:title["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        val t = og ?: Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1)
        return t?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
    }
}

object GenericGpx {
    fun import(url: String): Hike {
        val text = Http.getText(url, "application/gpx+xml, */*")
        if (!GpxParser.looksLikeGpx(text)) {
            throw ImportException("Site non pris en charge pour l'instant (Komoot et Visorando seulement). Tu peux importer le fichier GPX.", needsBrowser = true)
        }
        val fallback = url.substringAfterLast('/').substringBefore('?').removeSuffix(".gpx")
        return LinkImporter.fromGpxText(text!!, "GPX", url, fallback)
    }
}

/** Complète les altitudes absentes grâce au modèle de terrain d'Open-Meteo (90 m). */
object ElevationService {
    fun fillMissing(hike: Hike): Hike {
        val pts = hike.points
        val missing = pts.count { it.ele == null }
        if (missing < pts.size / 3) return hike
        val stride = maxOf(1, (pts.size + 299) / 300)
        val idx = (pts.indices step stride).toMutableList()
        if (idx.last() != pts.lastIndex) idx.add(pts.lastIndex)
        val elevations = HashMap<Int, Double>()
        for (chunk in idx.chunked(100)) {
            val lat = chunk.joinToString(",") { String.format(Locale.US, "%.5f", pts[it].lat) }
            val lon = chunk.joinToString(",") { String.format(Locale.US, "%.5f", pts[it].lon) }
            val body = runCatching {
                Http.plain.newCall(
                    okhttp3.Request.Builder()
                        .url("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lon")
                        .build()
                ).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull() ?: return hike
            val arr = JSONObject(body).optJSONArray("elevation") ?: return hike
            for (i in chunk.indices) elevations[chunk[i]] = arr.optDouble(i)
        }
        val newPts = pts.mapIndexed { i, p -> if (elevations.containsKey(i)) p.copy(ele = elevations[i]) else p.copy(ele = null) }
        return hike.copy(points = newPts)
    }
}
