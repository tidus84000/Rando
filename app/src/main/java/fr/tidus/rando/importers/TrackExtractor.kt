package fr.tidus.rando.importers

import fr.tidus.rando.data.Geo
import fr.tidus.rando.data.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Trouve le tracé d'une rando dans n'importe quelles données reçues par une page :
 * GPX, KML, GeoJSON, listes de points lat/lng, polylines encodées.
 */
object TrackExtractor {
    fun best(texts: List<String>, hint: Pair<Double, Double>? = null): List<TrackPoint> {
        var best = emptyList<TrackPoint>()
        fun near(pts: List<TrackPoint>): Boolean {
            if (hint == null) return true
            return pts.any { Geo.haversineKm(it.lat, it.lon, hint.first, hint.second) < 3.0 }
        }
        fun consider(pts: List<TrackPoint>) {
            if (pts.size > best.size && plausible(pts) && near(pts)) best = pts
        }
        for (t in texts) {
            if (t.length < 100) continue
            if (t.contains("<gpx", true)) runCatching { consider(GpxParser.parse(t).points) }
            if (t.contains("<kml", true) || t.contains("<coordinates>", true)) consider(kml(t))
            consider(PageScraper.bestTrack(t))
            consider(PageScraper.staticMapPolylines(t))
            consider(PageScraper.jsPairs(t, hint))
            val trimmed = t.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                runCatching {
                    val pts = fromJson(JSONTokener(trimmed).nextValue())
                    consider(pts)
                    consider(pts.map { TrackPoint(it.lon, it.lat, it.ele) }) // ordre inversé
                }
            }
        }
        return best
    }

    private val kmlCoords = Regex("<coordinates>(.*?)</coordinates>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun kml(text: String): List<TrackPoint> {
        var best = emptyList<TrackPoint>()
        for (m in kmlCoords.findAll(text)) {
            val pts = m.groupValues[1].trim().split(Regex("\\s+")).mapNotNull { tuple ->
                val p = tuple.split(',')
                val lon = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                TrackPoint(lat, lon, p.getOrNull(2)?.toDoubleOrNull())
            }
            if (pts.size > best.size) best = pts
        }
        return best
    }

    /**
     * GeoJSON dont le tracé est une suite de points (Visorando : getRandoGeoJson),
     * avec l'altitude prise dans « chartdata » quand elle est fournie.
     */
    fun pointCollection(root: Any?): List<TrackPoint> {
        val obj = root as? JSONObject ?: return emptyList()
        val fc = obj.optJSONObject("geojson") ?: obj.takeIf { it.optString("type") == "FeatureCollection" } ?: return emptyList()
        val feats = fc.optJSONArray("features") ?: return emptyList()
        val pts = ArrayList<Pair<Int, TrackPoint>>()
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val g = f.optJSONObject("geometry") ?: continue
            if (g.optString("type") != "Point") continue
            val c = g.optJSONArray("coordinates") ?: continue
            val lon = c.optDouble(0)
            val lat = c.optDouble(1)
            if (lat.isNaN() || lon.isNaN()) continue
            val idx = f.optJSONObject("properties")?.optInt("index", i) ?: i
            val ele = c.optDouble(2).takeUnless { it.isNaN() }
            pts.add(idx to TrackPoint(lat, lon, ele))
        }
        if (pts.size < 10) return emptyList()
        pts.sortBy { it.first }
        var list = pts.map { it.second }
        val chart = obj.optJSONObject("chartdata")?.optJSONArray("data")
        if (chart != null && chart.length() == list.size) {
            list = list.mapIndexed { i, p ->
                val alt = chart.optJSONArray(i)?.optDouble(1)?.takeUnless { it.isNaN() }
                if (p.ele == null && alt != null) p.copy(ele = alt) else p
            }
        }
        return list
    }

    /** Tableaux de paires de nombres dans du JSON (GeoJSON : [lon, lat] ; sinon [lat, lon]). */
    fun fromJson(root: Any?): List<TrackPoint> {
        pointCollection(root).let { if (it.size >= 10) return it }
        var best = emptyList<TrackPoint>()
        fun walk(node: Any?, key: String?, depth: Int) {
            if (depth > 30) return
            when (node) {
                is JSONObject -> {
                    val it = node.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        walk(node.opt(k), k, depth + 1)
                    }
                }
                is JSONArray -> {
                    val pts = pairs(node, lonFirst = key != null && key.contains("coordinates", true) || key == "lnglats")
                    if (pts != null) {
                        if (pts.size > best.size && plausible(pts)) best = pts
                    } else {
                        // MultiLineString : on concatène les lignes
                        if (key != null && key.contains("coordinates", true)) {
                            val merged = ArrayList<TrackPoint>()
                            for (i in 0 until node.length()) {
                                (node.opt(i) as? JSONArray)?.let { sub -> pairs(sub, true)?.let { merged.addAll(it) } }
                            }
                            if (merged.size > best.size && plausible(merged)) best = merged
                        }
                        for (i in 0 until node.length()) walk(node.opt(i), key, depth + 1)
                    }
                }
            }
        }
        walk(root, null, 0)
        return best
    }

    private fun pairs(arr: JSONArray, lonFirst: Boolean): List<TrackPoint>? {
        if (arr.length() < 20) return null
        val first = arr.opt(0) as? JSONArray ?: return null
        if (first.length() < 2 || first.opt(0) !is Number) return null
        val out = ArrayList<TrackPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.opt(i) as? JSONArray ?: return null
            val a = (p.opt(0) as? Number)?.toDouble() ?: return null
            val b = (p.opt(1) as? Number)?.toDouble() ?: return null
            val e = (p.opt(2) as? Number)?.toDouble()
            out.add(if (lonFirst) TrackPoint(b, a, e) else TrackPoint(a, b, e))
        }
        return out
    }

    fun plausible(pts: List<TrackPoint>): Boolean {
        if (pts.size < 10) return false
        if (pts.any { it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0 }) return false
        var total = 0.0
        var maxStep = 0.0
        for (i in 1 until pts.size) {
            val d = Geo.haversineKm(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
            total += d
            if (d > maxStep) maxStep = d
        }
        return total in 0.3..400.0 && maxStep < 5.0
    }

    private val textPos = Regex("""([NS])\s*([\d.]+)°\s*/\s*([EOW])\s*([\d.]+)°""")

    /** Point de départ écrit dans la page (« N 44.83° / O 0.57° », Visorando). */
    fun startFromText(html: String): Pair<Double, Double>? {
        val m = textPos.find(html) ?: return null
        val lat = m.groupValues[2].toDouble() * if (m.groupValues[1] == "S") -1 else 1
        val lon = m.groupValues[4].toDouble() * if (m.groupValues[3] == "E") 1 else -1
        return lat to lon
    }

    /** Formulaires de la page qui pourraient renvoyer le fichier (action + champs). */
    fun downloadForms(html: String, base: String): List<Pair<String, Map<String, String>>> {
        val host = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: ""
        val out = ArrayList<Pair<String, Map<String, String>>>()
        Regex("""<form\b([^>]*)>(.*?)</form>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).forEach { m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            if (!Regex("""gpx|t[ée]l[ée]charg|download|export""", RegexOption.IGNORE_CASE).containsMatchIn(attrs + body)) return@forEach
            var action = Regex("""action=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1) ?: base
            action = android.text.Html.fromHtml(action, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            if (action.startsWith("/")) action = host + action else if (!action.startsWith("http")) action = base
            val fields = LinkedHashMap<String, String>()
            Regex("""<input\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(body).forEach { i ->
                val a = i.groupValues[1]
                val name = Regex("""name=["']([^"']+)["']""").find(a)?.groupValues?.get(1) ?: return@forEach
                val value = Regex("""value=["']([^"']*)["']""").find(a)?.groupValues?.get(1) ?: ""
                fields[name] = android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            }
            out.add(action to fields)
        }
        return out.take(4)
    }

    /** Liens d'une page HTML qui peuvent mener au fichier (page intermédiaire de téléchargement). */
    fun downloadLinks(html: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        val host = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: ""
        fun add(raw: String) {
            val u = android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            when {
                u.isBlank() || u == "#" || u.startsWith("javascript") || u.startsWith("mailto") -> {}
                u.startsWith("http") -> out.add(u)
                u.startsWith("//") -> out.add("https:$u")
                u.startsWith("/") -> out.add(host + u)
            }
        }
        // 1) liens directs vers un fichier .gpx (Visorando : « cliquez-ici » → visorando-XXXX.gpx)
        Regex("""(?:https?:)?//[^"'\s<>]+\.gpx(?:\?[^"'\s<>]*)?|(?<=["'])/[^"'\s<>]+\.gpx(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { add(it.value) }
        // 2) <a ... href="...">texte</a> : lien de téléchargement ou « cliquez-ici »
        Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(html).forEach { m ->
            val attrs = m.groupValues[1]
            val text = m.groupValues[2]
            val href = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1) ?: return@forEach
            if (Regex("""gpx""", RegexOption.IGNORE_CASE).containsMatchIn(href) ||
                Regex("""cliquez""", RegexOption.IGNORE_CASE).containsMatchIn(text)
            ) add(href)
        }
        Regex("""(?:src|data-href|data-url|content)=["']([^"']*gpx[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { add(it.groupValues[1].substringAfter("url=", it.groupValues[1])) }
        Regex("""(?:location(?:\.href)?\s*=|location\.replace\(|window\.open\()\s*["']([^"']+)["']""")
            .findAll(html).forEach { add(it.groupValues[1]) }
        return out.filter { !it.contains("matomo") && !it.contains("analytics") }.take(12)
    }
}
