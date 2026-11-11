package fr.tidus.rando.explore

import fr.tidus.rando.data.Hike
import fr.tidus.rando.importers.ElevationService
import fr.tidus.rando.importers.GpxParser
import fr.tidus.rando.importers.Http
import fr.tidus.rando.importers.ImportException
import fr.tidus.rando.importers.LinkImporter
import fr.tidus.rando.importers.PageScraper
import fr.tidus.rando.importers.TrackExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Import « intelligent » d'un lien de rando :
 * 1) import direct (API Komoot, GPX Visorando…) ;
 * 2) sinon la page est ouverte dans le navigateur invisible, et le tracé est repris des
 *    données que la carte du site télécharge (aucun clic, les fenêtres d'inscription ne gênent pas) ;
 * 3) sinon, on suit les liens de téléchargement GPX (pages intermédiaires comprises).
 */
object SmartImport {
    suspend fun import(input: String, hidden: HiddenBrowser?, progress: (String) -> Unit = {}): Hike {
        val url = LinkImporter.extractUrl(input) ?: throw ImportException("Aucun lien trouvé.")
        val source = when {
            "komoot" in url || "kmt.io" in url -> "Komoot"
            "visorando" in url -> "Visorando"
            "alltrails" in url -> "AllTrails"
            else -> "GPX"
        }

        // 1) direct
        if (source != "AllTrails") {
            progress("Récupération directe…")
            try {
                return LinkImporter.import(url)
            } catch (e: ImportException) {
                if (!e.needsBrowser) throw e
            } catch (_: Exception) {
            }
        }

        // 2) navigateur invisible
        var blocked = false
        var pageHtml: String? = null
        if (hidden != null) {
            progress("Ouverture de la page en arrière-plan…")
            val got = hidden.collect(url, maxMs = 20_000, quietMs = 4_000)
            blocked = got.blocked
            pageHtml = got.texts.lastOrNull()
            val gpx = got.texts.firstOrNull { GpxParser.looksLikeGpx(it) }
            val pts = if (gpx != null) runCatching { GpxParser.parse(gpx).points }.getOrDefault(emptyList())
            else withContext(Dispatchers.Default) { TrackExtractor.best(got.texts) }
            if (pts.size >= 20) {
                val name = pageHtml?.let { PageScraper.title(it) }?.let { cleanTitle(it) } ?: "Rando $source"
                return withContext(Dispatchers.IO) {
                    ElevationService.fillMissing(LinkImporter.newHike(name, source, url, pts))
                }
            }
        }

        // 3) liens de téléchargement (Visorando : page « le téléchargement va démarrer »)
        val html = pageHtml ?: withContext(Dispatchers.IO) { Http.getText(url, "text/html") }
        if (html != null) {
            progress("Recherche du fichier GPX…")
            val hike = withContext(Dispatchers.IO) { followDownloads(html, url, source, depth = 0, seen = HashSet()) }
            if (hike != null) return hike
        }

        throw ImportException(
            if (blocked) "$source demande une vérification anti-robot. Ouvre la fiche, passe la vérification puis réessaie."
            else "Tracé introuvable automatiquement. Ouvre la fiche dans le navigateur pour l'importer.",
            needsBrowser = true,
        )
    }

    private fun followDownloads(html: String, base: String, source: String, depth: Int, seen: MutableSet<String>): Hike? {
        if (depth > 2) return null
        for (link in TrackExtractor.downloadLinks(html, base)) {
            if (!seen.add(link)) continue
            val text = Http.getText(link, "application/gpx+xml, application/xml, text/html, */*", base) ?: continue
            if (GpxParser.looksLikeGpx(text)) {
                val name = PageScraper.title(html)?.let { cleanTitle(it) } ?: "Rando $source"
                return ElevationService.fillMissing(LinkImporter.fromGpxText(text, source, base, name))
            }
            val kml = TrackExtractor.kml(text)
            if (TrackExtractor.plausible(kml)) {
                val name = PageScraper.title(html)?.let { cleanTitle(it) } ?: "Rando $source"
                return ElevationService.fillMissing(LinkImporter.newHike(name, source, base, kml))
            }
            if (text.contains("<html", true)) followDownloads(text, link, source, depth + 1, seen)?.let { return it }
        }
        return null
    }

    fun cleanTitle(t: String): String = t
        .substringBefore(" | ")
        .substringBefore(" - Visorando")
        .substringBefore(" - komoot")
        .replace(Regex("""^Téléchargement du (?:PDF|GPX) de\s*"?"""), "")
        .trim('"', ' ')
        .ifBlank { "Rando" }
}
