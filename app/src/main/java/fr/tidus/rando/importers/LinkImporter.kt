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
        return newHike(parsed.name ?: fallbackName, source, url, parsed.points)
    }
}

object KomootImporter {
    private val idRegex = Regex("""/(smart)?tours?/(\d+)""")

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

        val api = buildString {
            append("https://api.komoot.de/v007/")
            append(if (smart) "smart_tours/" else "tours/")
            append(id)
            append("?_embedded=coordinates")
            if (shareToken != null) append("&share_token=").append(shareToken)
        }
        val json = runCatching {
            Http.get(api, "application/hal+json, application/json").use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()

        if (json != null) {
            val o = JSONObject(json)
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
