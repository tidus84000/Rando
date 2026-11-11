package fr.tidus.rando.importers

import fr.tidus.rando.data.TrackPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader

data class ParsedTrack(val name: String?, val points: List<TrackPoint>)

/** Lecture des fichiers GPX (traces trk, routes rte, ou à défaut points wpt). */
object GpxParser {
    /** Site d'origine d'un GPX, d'après son en-tête (creator, liens, métadonnées). */
    fun detectSource(text: String): Pair<String, String?>? {
        val head = text.take(6000)
        val src = when {
            head.contains("alltrails", true) -> "AllTrails"
            head.contains("komoot", true) -> "Komoot"
            head.contains("visorando", true) -> "Visorando"
            else -> return null
        }
        val link = Regex("""<link[^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(head)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains(src, true) && it.startsWith("http") }
        return src to link
    }

    fun looksLikeGpx(text: String?): Boolean =
        text != null && text.contains("<gpx", ignoreCase = true)

    fun parse(text: String): ParsedTrack {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(StringReader(text))
        return parse(parser)
    }

    fun parse(stream: InputStream): ParsedTrack = parse(stream.bufferedReader().readText())

    private fun parse(parser: XmlPullParser): ParsedTrack {
        val trk = ArrayList<TrackPoint>()
        val rte = ArrayList<TrackPoint>()
        val wpt = ArrayList<TrackPoint>()
        var name: String? = null
        var trkName: String? = null

        var curLat: Double? = null
        var curLon: Double? = null
        var curEle: Double? = null
        var curKind: String? = null
        val stack = ArrayList<String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    stack.add(tag)
                    if (tag == "trkpt" || tag == "rtept" || tag == "wpt") {
                        curKind = tag
                        curLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        curLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        curEle = null
                    }
                }
                XmlPullParser.TEXT -> {
                    val tag = stack.lastOrNull()
                    val parent = stack.getOrNull(stack.size - 2)
                    val txt = parser.text?.trim().orEmpty()
                    if (txt.isNotEmpty()) {
                        when {
                            tag == "ele" && curKind != null -> curEle = txt.replace(',', '.').toDoubleOrNull()
                            tag == "name" && parent == "metadata" && name == null -> name = txt
                            tag == "name" && parent == "trk" && trkName == null -> trkName = txt
                            tag == "name" && parent == "rte" && trkName == null -> trkName = txt
                            tag == "name" && parent == "gpx" && name == null -> name = txt
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    if (tag == curKind) {
                        val la = curLat
                        val lo = curLon
                        if (la != null && lo != null) {
                            val p = TrackPoint(la, lo, curEle)
                            when (tag) {
                                "trkpt" -> trk.add(p)
                                "rtept" -> rte.add(p)
                                else -> wpt.add(p)
                            }
                        }
                        curKind = null
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
            }
            event = parser.next()
        }
        val pts = when {
            trk.size >= 2 -> trk
            rte.size >= 2 -> rte
            else -> wpt
        }
        if (pts.size < 2) throw ImportException("Ce fichier GPX ne contient pas de tracé.")
        return ParsedTrack(trkName ?: name, pts)
    }
}
