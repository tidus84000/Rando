package fr.tidus.rando.offline

import android.content.Context
import fr.tidus.rando.data.Hike
import fr.tidus.rando.data.TrackPoint
import fr.tidus.rando.importers.Http
import fr.tidus.rando.ui.MapStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** État du téléchargement d'une rando : tuiles faites / total, et si c'est terminé. */
data class OfflineState(val done: Int, val total: Int, val failed: Int, val finished: Boolean) {
    val complete get() = finished && total > 0 && failed * 20 < total
}

/**
 * Cartes IGN hors ligne : quand une rando est ajoutée, les tuiles du Plan IGN qui couvrent
 * le tracé (zooms 11 à 17, avec une marge d'une tuile) sont téléchargées dans le cache de cartes
 * du téléphone. La carte de la fiche s'affiche ensuite même sans réseau.
 */
object OfflineMaps {
    private const val MIN_ZOOM = 11
    private const val MAX_ZOOM = 17
    private const val YEAR_MS = 365L * 24 * 3600 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableStateFlow<Map<String, OfflineState>>(emptyMap())
    val states: StateFlow<Map<String, OfflineState>> = _states

    private fun prefs(c: Context) = c.getSharedPreferences("offline_maps", Context.MODE_PRIVATE)

    /** État enregistré (survit au redémarrage de l'appli). */
    fun saved(context: Context, hikeId: String): OfflineState? {
        val raw = prefs(context).getString(hikeId, null) ?: return null
        val p = raw.split('/')
        if (p.size < 3) return null
        return OfflineState(p[0].toInt(), p[1].toInt(), p[2].toInt(), true)
    }

    fun start(context: Context, hike: Hike) {
        val app = context.applicationContext
        val current = _states.value[hike.id]
        if (current != null && !current.finished) return
        scope.launch { download(app, hike) }
    }

    fun forget(context: Context, hikeId: String) {
        prefs(context).edit().remove(hikeId).apply()
        _states.value = _states.value - hikeId
    }

    private suspend fun download(context: Context, hike: Hike) {
        val src = MapStyles.planIgn
        val tiles = tilesFor(hike.points)
        val total = tiles.size
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        fun publish(finished: Boolean) {
            _states.value = _states.value + (hike.id to OfflineState(done.get(), total, failed.get(), finished))
        }
        publish(false)
        val writer = SqlTileWriter()
        try {
            val sem = Semaphore(4)
            coroutineScope {
                tiles.map { index ->
                    async {
                        sem.withPermit {
                            val ok = try {
                                if (writer.exists(src, index)) true else {
                                    val bytes = Http.plain.newCall(
                                        Request.Builder().url(src.getTileURLString(index))
                                            .header("User-Agent", "Rando-Android/1.0 github.com/tidus84000/Rando")
                                            .build()
                                    ).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                                    bytes != null && bytes.isNotEmpty() &&
                                        writer.saveFile(src, index, ByteArrayInputStream(bytes), System.currentTimeMillis() + YEAR_MS)
                                }
                            } catch (_: Exception) {
                                false
                            }
                            if (!ok) failed.incrementAndGet()
                            if (done.incrementAndGet() % 10 == 0) publish(false)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            writer.onDetach()
        }
        publish(true)
        prefs(context).edit().putString(hike.id, "${done.get()}/$total/${failed.get()}").apply()
    }

    /** Tuiles couvrant le tracé : rectangle complet aux petits zooms, couloir autour du tracé ensuite. */
    fun tilesFor(points: List<TrackPoint>): List<Long> {
        if (points.isEmpty()) return emptyList()
        val out = LinkedHashSet<Long>()
        val dense = densify(points)
        for (z in MIN_ZOOM..MAX_ZOOM) {
            val n = 1 shl z
            if (z <= 14) {
                val xs = dense.map { tileX(it.lon, z) }
                val ys = dense.map { tileY(it.lat, z) }
                for (x in (xs.min() - 1)..(xs.max() + 1)) for (y in (ys.min() - 1)..(ys.max() + 1)) {
                    if (x in 0 until n && y in 0 until n) out.add(MapTileIndex.getTileIndex(z, x, y))
                }
            } else {
                for (p in dense) {
                    val tx = tileX(p.lon, z)
                    val ty = tileY(p.lat, z)
                    for (dx in -1..1) for (dy in -1..1) {
                        val x = tx + dx
                        val y = ty + dy
                        if (x in 0 until n && y in 0 until n) out.add(MapTileIndex.getTileIndex(z, x, y))
                    }
                }
            }
        }
        return out.toList()
    }

    /** Ajoute des points intermédiaires (≈ tous les 100 m) pour ne pas sauter de tuile. */
    private fun densify(points: List<TrackPoint>): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        for (i in points.indices) {
            val p = points[i]
            if (i > 0) {
                val a = points[i - 1]
                val d = fr.tidus.rando.data.Geo.haversineKm(a.lat, a.lon, p.lat, p.lon)
                val steps = (d / 0.1).toInt()
                for (k in 1 until steps) {
                    val t = k.toDouble() / steps
                    out.add(TrackPoint(a.lat + (p.lat - a.lat) * t, a.lon + (p.lon - a.lon) * t, null))
                }
            }
            out.add(p)
        }
        return out
    }

    private fun tileX(lon: Double, z: Int) = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()
    private fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat)
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt()
    }
}
