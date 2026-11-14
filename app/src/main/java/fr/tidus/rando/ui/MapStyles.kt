package fr.tidus.rando.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

/** Fonds de carte : moderne (clair / sombre), topographique, satellite. */
enum class MapStyle(val label: String) { MODERN("Moderne"), TOPO("Topo"), SATELLITE("Satellite") }

object MapStyles {
    /** Services de tuiles Esri (sans clé) : l'ordre des tuiles est z/y/x. */
    private fun esri(name: String, service: String, maxZoom: Int, copyright: String) = object : OnlineTileSourceBase(
        name, 0, maxZoom, 256, "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/"),
        copyright,
    ) {
        override fun getTileURLString(index: Long): String =
            baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index)
    }

    private val street = esri("EsriStreet", "World_Street_Map", 19, "© Esri, HERE, Garmin, © OpenStreetMap")

    private val topo = XYTileSource(
        "OpenTopoMap", 0, 17, 256, ".png",
        arrayOf("https://a.tile.opentopomap.org/", "https://b.tile.opentopomap.org/", "https://c.tile.opentopomap.org/"),
        "© OpenTopoMap (CC-BY-SA), © OpenStreetMap",
    )

    private val satellite = esri("EsriImagery", "World_Imagery", 19, "© Esri, Maxar, Earthstar Geographics")

    fun source(style: MapStyle, dark: Boolean): OnlineTileSourceBase = when (style) {
        MapStyle.MODERN -> street
        MapStyle.TOPO -> topo
        MapStyle.SATELLITE -> satellite
    }

    /**
     * En mode sombre, le fond « Moderne » est assombri sur le téléphone (inversion des couleurs
     * puis désaturation) : pas besoin d'un service de tuiles sombres payant.
     */
    fun applyDarkFilter(map: org.osmdroid.views.MapView, style: MapStyle, dark: Boolean) {
        val overlay = map.overlayManager.tilesOverlay
        if (style == MapStyle.MODERN && dark) {
            val invert = android.graphics.ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            val desat = android.graphics.ColorMatrix().apply { setSaturation(0.35f) }
            val dim = android.graphics.ColorMatrix(
                floatArrayOf(
                    0.85f, 0f, 0f, 0f, 4f,
                    0f, 0.88f, 0f, 0f, 8f,
                    0f, 0f, 0.86f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            invert.postConcat(desat)
            invert.postConcat(dim)
            overlay.setColorFilter(android.graphics.ColorMatrixColorFilter(invert))
        } else {
            overlay.setColorFilter(null)
        }
    }

    fun load(context: Context): MapStyle =
        runCatching { MapStyle.valueOf(context.getSharedPreferences("rando", Context.MODE_PRIVATE).getString("map_style", "MODERN")!!) }
            .getOrDefault(MapStyle.MODERN)

    fun save(context: Context, style: MapStyle) {
        context.getSharedPreferences("rando", Context.MODE_PRIVATE).edit().putString("map_style", style.name).apply()
    }

    /** Repère rond : anneau blanc, couleur du site, point blanc au centre. */
    fun pin(context: Context, color: Int, sizeDp: Int, selected: Boolean = false): LayerDrawable {
        val d = context.resources.displayMetrics.density
        val px = (sizeDp * d).toInt()
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
            setSize(px, px)
        }
        val inset = (if (selected) 3.5f else 2.5f) * d
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
        }
        val shadow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33000000)
        }
        val layer = LayerDrawable(arrayOf(shadow, ring, fill, dot))
        val sh = (1.5f * d).toInt()
        layer.setLayerInset(0, sh, sh * 2, 0, 0)
        layer.setLayerInset(1, 0, 0, sh, sh)
        val i = inset.toInt()
        layer.setLayerInset(2, i, i, i + sh, i + sh)
        val c = (px - sh) * (if (selected) 0.36f else 0.32f)
        val ci = ((px - sh) / 2f - c / 2f).toInt()
        layer.setLayerInset(3, ci, ci, ci + sh, ci + sh)
        return layer
    }
}
