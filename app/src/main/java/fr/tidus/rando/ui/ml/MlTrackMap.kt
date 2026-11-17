package fr.tidus.rando.ui.ml

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import fr.tidus.rando.data.Profile
import fr.tidus.rando.ui.MapStyle
import fr.tidus.rando.ui.levelColor
import fr.tidus.rando.weather.Level
import fr.tidus.rando.weather.PointWeather
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Carte de la fiche d'une rando, avec le nouveau moteur. */
@Composable
fun MlTrackMap(profile: Profile, weather: List<PointWeather>, style: MapStyle, dark: Boolean) {
    val view = rememberMlMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loaded by remember { mutableStateOf<Style?>(null) }
    var framed by remember { mutableStateOf(false) }

    LaunchedEffect(view) {
        view.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            map = m
        }
    }

    LaunchedEffect(map, style, dark) {
        val m = map ?: return@LaunchedEffect
        loaded = null
        m.setStyle(MlStyles.builder(style, dark)) { s ->
            s.addSource(GeoJsonSource("track"))
            s.addSource(GeoJsonSource("points"))
            s.addLayer(
                LineLayer("track-casing", "track").withProperties(
                    PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(8f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            s.addLayer(
                LineLayer("track-line", "track").withProperties(
                    PropertyFactory.lineColor("#16A06A"), PropertyFactory.lineWidth(4.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            s.addLayer(
                CircleLayer("points-dots", "points").withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(Expression.get("radius")),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f),
                )
            )
            loaded = s
        }
    }

    LaunchedEffect(loaded, profile, weather) {
        val s = loaded ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (profile.size < 2) return@LaunchedEffect
        val step = maxOf(1, profile.size / 1500)
        val pts = (0 until profile.size step step).map { Point.fromLngLat(profile.lon[it], profile.lat[it]) }
        s.getSourceAs<GeoJsonSource>("track")?.setGeoJson(LineString.fromLngLats(pts))
        val dots = ArrayList<Feature>()
        dots.add(Feature.fromGeometry(Point.fromLngLat(profile.lon[0], profile.lat[0])).apply {
            addStringProperty("color", "#16A06A"); addNumberProperty("radius", 8f)
        })
        weather.filter { it.level == Level.WARN || it.level == Level.DANGER }.forEach { w ->
            dots.add(Feature.fromGeometry(Point.fromLngLat(w.loc.lon, w.loc.lat)).apply {
                addStringProperty("color", MlStyles.hex(levelColor(w.level).toArgb())); addNumberProperty("radius", 6.5f)
            })
        }
        s.getSourceAs<GeoJsonSource>("points")?.setGeoJson(FeatureCollection.fromFeatures(dots))
        if (!framed) {
            framed = true
            val b = LatLngBounds.Builder()
            (0 until profile.size step maxOf(1, profile.size / 200)).forEach { b.include(LatLng(profile.lat[it], profile.lon[it])) }
            b.include(LatLng(profile.lat[profile.size - 1], profile.lon[profile.size - 1]))
            val pad = (48 * view.resources.displayMetrics.density).toInt()
            val bounds = b.build()
            view.post { runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad)) } }
        }
    }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
