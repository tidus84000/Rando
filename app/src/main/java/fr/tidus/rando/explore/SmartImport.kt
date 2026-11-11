package fr.tidus.rando.explore

import fr.tidus.rando.data.Hike
import fr.tidus.rando.data.TrackPoint
import fr.tidus.rando.importers.ElevationService
import fr.tidus.rando.importers.GpxParser
import fr.tidus.rando.importers.ImportException
import fr.tidus.rando.importers.LinkImporter
import fr.tidus.rando.importers.PageScraper
import fr.tidus.rando.importers.TrackExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Import d'un lien de rando :
 * 1) import direct (API publique Komoot, GPX public) ;
 * 2) sinon la page est ouverte en arrière-plan et le tracé est repris de ce que la page affiche
 *    (carte, données de la page) ;
 * 3) sinon : la fiche s'ouvre dans le navigateur intégré, où tu télécharges le GPX toi-même
 *    (avec ton compte si le site l'exige) et l'appli le récupère.
 */
object SmartImport {
    suspend fun import(
        input: String,
        hidden: HiddenBrowser?,
        hint: Pair<Double, Double>? = null,
        progress: (String) -> Unit = {},
    ): Hike {
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

        // 2) tracé affiché par la page
        var blocked = false
        val texts = ArrayList<String>()
        var where = hint
        if (hidden != null) {
            progress("Lecture de la page en arrière-plan…")
            val got = hidden.collect(url, maxMs = 22_000, quietMs = 4_500)
            blocked = got.blocked
            texts.addAll(got.texts)
            val pageHtml = got.texts.lastOrNull()
            if (where == null) where = pageHtml?.let { TrackExtractor.startFromText(it) }
            val gpx = got.texts.firstOrNull { GpxParser.looksLikeGpx(it) }
            val pts = if (gpx != null) runCatching { GpxParser.parse(gpx).points }.getOrDefault(emptyList())
            else withContext(Dispatchers.Default) { TrackExtractor.best(got.texts, where) }
            if (pts.size >= 10) {
                val name = pageHtml?.let { PageScraper.title(it) }?.let { cleanTitle(it) } ?: "Rando $source"
                return withContext(Dispatchers.IO) { ElevationService.fillMissing(LinkImporter.newHike(name, source, url, pts)) }
            }
        }
        Diagnostics.record(url, texts, "Import auto raté ($source), vérification anti-robot=$blocked, repère=$where")

        throw ImportException(
            when (source) {
                "AllTrails" -> "AllTrails n'envoie le tracé que quand on ouvre sa carte. Touche « Voir la fiche », " +
                    "touche la petite carte pour l'agrandir, puis « Importer cette rando »."
                "Visorando" -> "Touche « Voir la fiche », connecte-toi à ton compte Visorando puis touche « GPX » : l'appli récupère le fichier."
                else -> "Tracé introuvable automatiquement. Ouvre la fiche pour télécharger le GPX."
            },
            needsBrowser = true,
        )
    }

    fun cleanTitle(t: String): String = t
        .substringBefore(" | ")
        .substringBefore(" - Visorando")
        .substringBefore(" - komoot")
        .replace(Regex("""^Téléchargement du (?:PDF|GPX) de\s*"?"""), "")
        .trim('"', ' ')
        .ifBlank { "Rando" }

    @Suppress("unused")
    private fun unused(p: List<TrackPoint>) = p
}
