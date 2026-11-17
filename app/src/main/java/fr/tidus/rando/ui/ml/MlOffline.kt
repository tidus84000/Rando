package fr.tidus.rando.ui.ml

import android.content.Context
import fr.tidus.rando.data.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume

/**
 * Carte IGN Classique hors ligne (nouveau moteur) : région MapLibre couvrant le tracé
 * (style, tuiles vectorielles, polices et icônes), zooms 9 à 16.
 */
object MlOffline {
    suspend fun download(context: Context, points: List<TrackPoint>, name: String, onProgress: (Long, Long) -> Unit): Boolean =
        withContext(Dispatchers.Main) {
            if (points.size < 2) return@withContext false
            val pad = 0.01 // ≈ 1 km de marge
            val bounds = LatLngBounds.from(
                points.maxOf { it.lat } + pad, points.maxOf { it.lon } + pad,
                points.minOf { it.lat } - pad, points.minOf { it.lon } - pad,
            )
            val def = OfflineTilePyramidRegionDefinition(
                MlStyles.IGN_CLASSIQUE_URL, bounds, 9.0, 16.0, context.resources.displayMetrics.density,
            )
            val manager = OfflineManager.getInstance(context)
            suspendCancellableCoroutine { cont ->
                manager.createOfflineRegion(def, name.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                onProgress(status.completedResourceCount, status.requiredResourceCount)
                                if (status.isComplete) {
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    if (cont.isActive) cont.resume(true)
                                }
                            }

                            override fun onError(error: OfflineRegionError) {}

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                if (cont.isActive) cont.resume(false)
                            }
                        })
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }

                    override fun onError(error: String) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
            }
        }
}
