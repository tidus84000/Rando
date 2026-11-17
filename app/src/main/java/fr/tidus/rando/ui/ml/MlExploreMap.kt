package fr.tidus.rando.ui.ml

import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import fr.tidus.rando.data.Geo
import fr.tidus.rando.data.Hike
import fr.tidus.rando.explore.Candidate
import fr.tidus.rando.ui.ExploreState
import fr.tidus.rando.ui.MapStyle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.osmdroid.util.GeoPoint

private const val ZONE = "rando-zone"
private const val SAVED = "rando-saved"
private const val RESULTS = "rando-results"
private const val ME = "rando-me"

/** Calques de la carte Explorer (ajoutés par-dessus n'importe quel style). */
private fun installExploreLayers(s: Style) {
    s.addSource(GeoJsonSource(ZONE))
    s.addSource(GeoJsonSource(SAVED))
    s.addSource(GeoJsonSource(RESULTS))
    s.addSource(GeoJsonSource(ME))
    s.addLayer(FillLayer("$ZONE-fill", ZONE).withProperties(PropertyFactory.fillColor("#16A06A"), PropertyFactory.fillOpacity(0.07f)))
    s.addLayer(
        LineLayer("$ZONE-line", ZONE).withProperties(
            PropertyFactory.lineColor("#16A06A"), PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineWidth(1.6f), PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
        )
    )
    s.addLayer(
        LineLayer("$SAVED-casing", SAVED).withProperties(
            PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    s.addLayer(
        LineLayer("$SAVED-line", SAVED).withProperties(
            PropertyFactory.lineColor("#8B5CF6"), PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    s.addLayer(
        CircleLayer("$RESULTS-circles", RESULTS).withProperties(
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleRadius(Expression.get("radius")),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        )
    )
    s.addLayer(
        CircleLayer("$ME-dot", ME).withProperties(
            PropertyFactory.circleColor("#2563EB"), PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
        )
    )
}

@Composable
fun MlExploreMap(
    state: ExploreState,
    style: MapStyle,
    dark: Boolean,
    savedHikes: List<Hike>,
    onArea: ((() -> Pair<GeoPoint, Double>?)) -> Unit,
    onSelect: (Candidate) -> Unit,
    onOpenHike: (String) -> Unit,
) {
    val view = rememberMlMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loaded by remember { mutableStateOf<Style?>(null) }
    val select by rememberUpdatedState(onSelect)
    val openHike by rememberUpdatedState(onOpenHike)

    LaunchedEffect(view) {
        view.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(state.mapCenter.latitude, state.mapCenter.longitude))
                .zoom(state.zoom - 1) // les tuiles vectorielles sont en 512 px
                .build()
            m.addOnCameraIdleListener {
                val c = m.cameraPosition
                c.target?.let { state.mapCenter = GeoPoint(it.latitude, it.longitude) }
                state.zoom = c.zoom + 1
            }
            m.addOnMapClickListener { ll ->
                val p: PointF = m.projection.toScreenLocation(ll)
                val r = 28f
                val box = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
                val hit = m.queryRenderedFeatures(box, "$RESULTS-circles").firstOrNull()
                val url = hit?.getStringProperty("url")
                if (url != null) {
                    state.results.values.flatten().firstOrNull { it.url == url }?.let { select(it) }
                    return@addOnMapClickListener true
                }
                val line = m.queryRenderedFeatures(box, "$SAVED-line").firstOrNull()?.getStringProperty("id")
                if (line != null) {
                    openHike(line)
                    return@addOnMapClickListener true
                }
                false
            }
            map = m
        }
    }

    // style (et ré-installation des calques à chaque changement de fond)
    LaunchedEffect(map, style, dark) {
        val m = map ?: return@LaunchedEffect
        loaded = null
        m.setStyle(MlStyles.builder(style, dark)) { s ->
            installExploreLayers(s)
            loaded = s
        }
    }

    // données
    LaunchedEffect(loaded, state.results, state.filter, state.selected, state.searchCenter, state.searchRadiusKm, state.myLocation, savedHikes) {
        val s = loaded ?: return@LaunchedEffect
        val zone = state.searchCenter?.let { c ->
            FeatureCollection.fromFeature(Feature.fromGeometry(MlStyles.circle(c.latitude, c.longitude, state.searchRadiusKm)))
        } ?: FeatureCollection.fromFeatures(emptyList())
        s.getSourceAs<GeoJsonSource>(ZONE)?.setGeoJson(zone)

        val saved = savedHikes.mapNotNull { h ->
            if (h.points.size < 2) return@mapNotNull null
            val step = maxOf(1, h.points.size / 300)
            val line = LineString.fromLngLats(h.points.filterIndexed { i, _ -> i % step == 0 }.map { Point.fromLngLat(it.lon, it.lat) })
            Feature.fromGeometry(line).apply { addStringProperty("id", h.id) }
        }
        s.getSourceAs<GeoJsonSource>(SAVED)?.setGeoJson(FeatureCollection.fromFeatures(saved))

        val results = state.results.filterKeys { it in state.filter }.values.flatten().map { c ->
            val sel = state.selected?.url == c.url
            Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply {
                addStringProperty("url", c.url)
                addStringProperty("color", MlStyles.hex(c.source.color))
                addNumberProperty("radius", if (sel) 11f else 7.5f)
            }
        }.sortedBy { it.getNumberProperty("radius").toFloat() }
        s.getSourceAs<GeoJsonSource>(RESULTS)?.setGeoJson(FeatureCollection.fromFeatures(results))

        val me = state.myLocation?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
            ?: FeatureCollection.fromFeatures(emptyList())
        s.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(me)
    }

    // recentrage demandé (position, adresse)
    LaunchedEffect(map, state.recenter) {
        val m = map ?: return@LaunchedEffect
        val (p, z) = state.recenter ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), z - 1), 700)
        state.recenter = null
    }

    // zone visible, pour « Rechercher dans cette zone »
    SideEffect {
        onArea {
            val m = map ?: return@onArea null
            val b = m.projection.visibleRegion.latLngBounds
            val c = m.cameraPosition.target ?: return@onArea null
            val half = Geo.haversineKm(b.latitudeNorth, b.longitudeWest, b.latitudeSouth, b.longitudeEast) / 2
            GeoPoint(c.latitude, c.longitude) to half
        }
    }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
