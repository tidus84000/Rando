package fr.tidus.rando.ui.ml

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import fr.tidus.rando.ui.MapStyle
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/** Styles du nouveau moteur (MapLibre) : IGN Classique en vectoriel, les autres en images. */
object MlStyles {
    const val IGN_CLASSIQUE_URL = "https://data.geopf.fr/annexes/ressources/vectorTiles/styles/PLAN.IGN/classique.json"

    private fun ignWmts(layer: String, format: String) =
        "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=$layer" +
            "&STYLE=normal&TILEMATRIXSET=PM&FORMAT=$format&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}"

    fun builder(style: MapStyle, dark: Boolean): Style.Builder = when (style) {
        MapStyle.IGN_CLASSIQUE -> Style.Builder().fromUri(IGN_CLASSIQUE_URL)
        MapStyle.MODERN -> raster(
            listOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}"),
            19, "© Esri, HERE, Garmin, © OpenStreetMap", darken = dark,
        )
        MapStyle.PLAN_IGN -> raster(listOf(ignWmts("GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", "image/png")), 19, "© IGN")
        MapStyle.PHOTO_IGN -> raster(listOf(ignWmts("ORTHOIMAGERY.ORTHOPHOTOS", "image/jpeg")), 19, "© IGN")
        MapStyle.TOPO -> raster(
            listOf("https://a.tile.opentopomap.org/{z}/{x}/{y}.png", "https://b.tile.opentopomap.org/{z}/{x}/{y}.png"),
            17, "© OpenTopoMap (CC-BY-SA), © OpenStreetMap",
        )
        MapStyle.SATELLITE -> raster(
            listOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
            19, "© Esri, Maxar, Earthstar Geographics",
        )
    }

    private fun raster(tiles: List<String>, maxZoom: Int, attribution: String, darken: Boolean = false): Style.Builder {
        val paint = JSONObject()
        if (darken) {
            paint.put("raster-brightness-max", 0.42)
            paint.put("raster-saturation", -0.55)
            paint.put("raster-contrast", 0.15)
        }
        val json = JSONObject()
            .put("version", 8)
            .put(
                "sources", JSONObject().put(
                    "base", JSONObject()
                        .put("type", "raster")
                        .put("tiles", JSONArray(tiles))
                        .put("tileSize", 256)
                        .put("maxzoom", maxZoom)
                        .put("attribution", attribution)
                )
            )
            .put(
                "layers", JSONArray()
                    .put(JSONObject().put("id", "bg").put("type", "background").put("paint", JSONObject().put("background-color", if (darken) "#151a18" else "#e9ece8")))
                    .put(JSONObject().put("id", "base").put("type", "raster").put("source", "base").put("paint", paint))
            )
        return Style.Builder().fromJson(json.toString())
    }

    /** Cercle (zone de recherche) en polygone GeoJSON. */
    fun circle(lat: Double, lon: Double, radiusKm: Double, steps: Int = 72): Polygon {
        val pts = ArrayList<Point>()
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        for (i in 0..steps) {
            val a = 2 * Math.PI * i / steps
            pts.add(Point.fromLngLat(lon + dLon * cos(a), lat + dLat * sin(a)))
        }
        return Polygon.fromLngLats(listOf(pts))
    }

    fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}

/** MapView MapLibre reliée au cycle de vie de l'écran. */
@Composable
fun rememberMlMapView(): MapView {
    val context: Context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { MapView(context).apply { onCreate(null) } }
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        // l'écran peut apparaître alors que l'activité est déjà au premier plan
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }
    return view
}
