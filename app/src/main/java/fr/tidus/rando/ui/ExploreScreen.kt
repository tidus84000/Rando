package fr.tidus.rando.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import fr.tidus.rando.data.Geo
import fr.tidus.rando.data.Hike
import fr.tidus.rando.explore.Candidate
import fr.tidus.rando.explore.HiddenBrowser
import fr.tidus.rando.explore.JsonHarvester
import fr.tidus.rando.explore.KomootDirect
import fr.tidus.rando.explore.Source
import fr.tidus.rando.explore.VisorandoDiscovery
import fr.tidus.rando.importers.Geocoding
import fr.tidus.rando.importers.ImportException
import fr.tidus.rando.importers.LinkImporter
import fr.tidus.rando.importers.Place
import fr.tidus.rando.importers.SearchLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** État de la carte, conservé quand on part dans le navigateur ou une fiche. */
@Stable
class ExploreState {
    var mapCenter by mutableStateOf(GeoPoint(46.6, 2.4))
    var zoom by mutableStateOf(6.0)
    var recenter by mutableStateOf<Pair<GeoPoint, Double>?>(null)
    var myLocation by mutableStateOf<GeoPoint?>(null)
    var searchCenter by mutableStateOf<GeoPoint?>(null)
    var searchRadiusKm by mutableStateOf(10.0)
    var results by mutableStateOf<Map<Source, List<Candidate>>>(emptyMap())
    var status by mutableStateOf<Map<Source, String>>(emptyMap())
    var running by mutableStateOf<Set<Source>>(emptySet())
    var filter by mutableStateOf(Source.entries.toSet())
    var selected by mutableStateOf<Candidate?>(null)
    var started by mutableStateOf(false)
}

private val OpenTopoMap = XYTileSource(
    "OpenTopoMap", 0, 17, 256, ".png",
    arrayOf("https://a.tile.opentopomap.org/", "https://b.tile.opentopomap.org/", "https://c.tile.opentopomap.org/"),
    "© OpenTopoMap (CC-BY-SA), © OpenStreetMap",
)

@Composable
fun ExploreScreen(
    state: ExploreState,
    hidden: HiddenBrowser,
    savedHikes: List<Hike>,
    dark: Boolean,
    onToggleDark: () -> Unit,
    onImported: (Hike) -> Unit,
    onOpenHike: (String) -> Unit,
    onOpenBrowser: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Place>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }

    fun search(center: GeoPoint, radiusKm: Double) {
        state.searchCenter = center
        state.searchRadiusKm = radiusKm
        state.selected = null
        state.results = emptyMap()
        state.status = emptyMap()
        runSearch(scope, state, hidden, center, radiusKm)
    }

    fun searchVisibleArea() {
        val map = mapRef ?: return
        val box = map.boundingBox
        val c = map.mapCenter
        val center = GeoPoint(c.latitude, c.longitude)
        val half = Geo.haversineKm(box.latNorth, box.lonWest, box.latSouth, box.lonEast) / 2
        search(center, half.coerceIn(2.0, 40.0))
    }

    fun locate(andSearch: Boolean) {
        scope.launch {
            val loc = currentLocation(context)
            if (loc == null) {
                message = "Position introuvable : active la localisation ou cherche une adresse."
                return@launch
            }
            val p = GeoPoint(loc.latitude, loc.longitude)
            state.myLocation = p
            state.recenter = p to 12.0
            if (andSearch) search(p, 10.0)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) locate(true) else message = "Sans localisation, cherche une adresse dans la barre du haut."
    }

    fun askLocation(andSearch: Boolean) {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (ok) locate(andSearch) else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(Unit) {
        if (!state.started) {
            state.started = true
            askLocation(true)
        }
    }

    fun importCandidate(c: Candidate) {
        scope.launch {
            importing = true
            message = null
            try {
                onImported(fr.tidus.rando.explore.SmartImport.import(c.url, hidden, c.lat to c.lon) { message = it })
            } catch (e: ImportException) {
                message = e.message
            } catch (e: Exception) {
                message = "Import impossible : ${e.message}"
            } finally {
                importing = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        DisposableEffect(Unit) { onDispose { state.running = emptySet() } }

        ExploreMap(
            state = state,
            savedHikes = savedHikes,
            onMap = { mapRef = it },
            onSelect = { state.selected = it },
            onOpenHike = onOpenHike,
        )

        // Barre du haut : recherche d'adresse + position
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp, start = 10.dp, end = 10.dp)) {
            Card(elevation = CardDefaults.cardElevation(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ville, adresse, lieu…") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                    IconButton(onClick = {
                        if (query.isNotBlank()) scope.launch {
                            suggestions = Geocoding.search(query)
                            if (suggestions.isEmpty()) message = "Aucun lieu trouvé."
                        }
                    }) { Icon(Icons.Default.Search, "Chercher") }
                    IconButton(onClick = { askLocation(true) }) { Icon(Icons.Default.LocationOn, "Ma position") }
                    IconButton(onClick = onToggleDark) { Text(if (dark) "☀️" else "🌙") }
                }
            }
            suggestions.forEach { s ->
                Card(Modifier.fillMaxWidth().padding(top = 4.dp).clickable {
                    suggestions = emptyList()
                    query = s.label
                    val p = GeoPoint(s.lat, s.lon)
                    state.recenter = p to 12.0
                    search(p, 10.0)
                }) { Text(s.label, Modifier.padding(12.dp)) }
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Source.entries.toList()) { src ->
                    val n = state.results[src]?.size
                    val running = src in state.running
                    val st = state.status[src]
                    FilterChip(
                        selected = src in state.filter,
                        onClick = {
                            if (st != null && st.startsWith("⚠") && src == Source.ALLTRAILS) {
                                state.searchCenter?.let { c ->
                                    onOpenBrowser(SearchLinks.allTrails(c.latitude, c.longitude, state.searchRadiusKm.roundToInt()), false)
                                }
                            } else {
                                state.filter = if (src in state.filter) state.filter - src else state.filter + src
                            }
                        },
                        label = {
                            Text(
                                when {
                                    running -> "${src.label} …"
                                    st != null -> "${src.label} $st"
                                    n != null -> "${src.label} ($n)"
                                    else -> src.label
                                }
                            )
                        },
                        leadingIcon = { Box(Modifier.size(10.dp).padding(0.dp)) { DotIcon(Color(src.color)) } },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
        }

        // Bas de l'écran : fiche de la rando choisie, ou bouton de recherche
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
            message?.let {
                Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable { message = null }) {
                    Column(Modifier.padding(10.dp)) {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                        if (!importing && fr.tidus.rando.explore.Diagnostics.hasData()) {
                            androidx.compose.material3.TextButton(onClick = { fr.tidus.rando.explore.Diagnostics.share(context) }) {
                                Text("📤 Envoyer le diagnostic")
                            }
                        }
                    }
                }
            }
            val sel = state.selected
            if (sel != null) {
                CandidateCard(
                    c = sel,
                    from = state.myLocation ?: state.searchCenter,
                    importing = importing,
                    onClose = { state.selected = null },
                    onImport = { importCandidate(sel) },
                    onView = { onOpenBrowser(sel.url, false) },
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { searchVisibleArea() }, enabled = state.running.isEmpty()) {
                        if (state.running.isNotEmpty()) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.size(8.dp))
                            Text("Recherche…")
                        } else Text("🔍 Rechercher dans cette zone")
                    }
                }
            }
        }
    }
}

@Composable
private fun DotIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(color) }
}

@Composable
private fun CandidateCard(
    c: Candidate,
    from: GeoPoint?,
    importing: Boolean,
    onClose: () -> Unit,
    onImport: () -> Unit,
    onView: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotIcon(Color(c.source.color))
                Spacer(Modifier.size(6.dp))
                Text(c.source.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Fermer") }
            }
            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val parts = listOfNotNull(
                c.distanceKm?.let { km(it) },
                c.ascent?.let { "↗ ${it.roundToInt()} m" },
                c.durationMin?.let { "⏱ ${it / 60}h${(it % 60).toString().padStart(2, '0')}" },
                c.difficulty,
                from?.let { "📍 à ${km(Geo.haversineKm(it.latitude, it.longitude, c.lat, c.lon))}" },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onImport, enabled = !importing) {
                    if (importing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Importer")
                }
                OutlinedButton(onClick = onView) { Text("Voir la fiche") }
            }
        }
    }
}

private fun dot(context: Context, color: Int, sizeDp: Int): GradientDrawable {
    val px = (sizeDp * context.resources.displayMetrics.density).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((2.5f * context.resources.displayMetrics.density).toInt(), android.graphics.Color.WHITE)
        setSize(px, px)
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun ExploreMap(
    state: ExploreState,
    savedHikes: List<Hike>,
    onMap: (MapView) -> Unit,
    onSelect: (Candidate) -> Unit,
    onOpenHike: (String) -> Unit,
) {
    val context = LocalContext.current
    var map by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(Unit) { onDispose { map?.onDetach() } }
    val savedTracks = remember(savedHikes) {
        savedHikes.map { h ->
            val step = maxOf(1, h.points.size / 300)
            Triple(h.id, h.name, h.points.filterIndexed { i, _ -> i % step == 0 }.map { GeoPoint(it.lat, it.lon) })
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(OpenTopoMap)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                controller.setZoom(state.zoom)
                controller.setCenter(state.mapCenter)
                addMapListener(object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        val c = mapCenter
                        state.mapCenter = GeoPoint(c.latitude, c.longitude)
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        state.zoom = zoomLevelDouble
                        return false
                    }
                })
                map = this
                onMap(this)
            }
        },
        update = { m ->
            state.recenter?.let { (p, z) ->
                m.controller.setZoom(z)
                m.controller.animateTo(p)
                state.recenter = null
            }
            m.overlays.clear()
            // zone de recherche
            state.searchCenter?.let { c ->
                m.overlays.add(Polygon(m).apply {
                    setPoints(Polygon.pointsAsCircle(c, state.searchRadiusKm * 1000))
                    fillPaint.color = 0x142E7D32
                    outlinePaint.color = 0x882E7D32.toInt()
                    outlinePaint.strokeWidth = 3f
                    setOnClickListener { _, _, _ -> false }
                })
            }
            // randos déjà importées
            savedTracks.forEach { (id, name, pts) ->
                if (pts.size >= 2) m.overlays.add(Polyline(m).apply {
                    setPoints(pts)
                    outlinePaint.color = 0xFFB0179A.toInt()
                    outlinePaint.strokeWidth = 7f
                    title = name
                    setOnClickListener { _, _, _ -> onOpenHike(id); true }
                })
            }
            // ma position
            state.myLocation?.let { p ->
                m.overlays.add(Marker(m).apply {
                    position = p
                    icon = dot(context, 0xFF2962FF.toInt(), 16)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Ma position"
                })
            }
            // randos trouvées
            state.results.forEach { (src, list) ->
                if (src !in state.filter) return@forEach
                list.forEach { c ->
                    val selected = state.selected?.url == c.url
                    m.overlays.add(Marker(m).apply {
                        position = GeoPoint(c.lat, c.lon)
                        icon = dot(context, src.color, if (selected) 30 else 22)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ -> onSelect(c); true }
                    })
                }
            }
            m.invalidate()
        },
    )
}

/** Lance la recherche sur les trois sites en parallèle ; les repères arrivent au fur et à mesure. */
private fun runSearch(scope: CoroutineScope, state: ExploreState, hidden: HiddenBrowser, center: GeoPoint, radiusKm: Double) {
    val lat = center.latitude
    val lon = center.longitude
    fun publish(src: Source, list: List<Candidate>, status: String? = null) {
        val inZone = list.filter { Geo.haversineKm(lat, lon, it.lat, it.lon) <= radiusKm * 1.2 }
            .distinctBy { it.url }
        state.results = state.results + (src to inZone)
        state.status = if (status != null) state.status + (src to status) else state.status - src
        state.running = state.running - src
    }
    state.running = Source.entries.toSet()

    scope.launch {
        val list = runCatching { VisorandoDiscovery.around(lat, lon, radiusKm) }.getOrDefault(emptyList())
        publish(Source.VISORANDO, list, if (list.isEmpty()) "(0)" else null)
    }
    scope.launch {
        // Komoot : API directe, sinon page Discover dans le navigateur invisible
        var komoot = runCatching { KomootDirect.around(lat, lon, radiusKm) }.getOrDefault(emptyList())
        if (komoot.isEmpty()) {
            val got = hidden.collect(SearchLinks.komoot(lat, lon, radiusKm.roundToInt().coerceAtLeast(1)))
            komoot = JsonHarvester.harvest(got.texts, Source.KOMOOT)
        }
        publish(Source.KOMOOT, komoot, if (komoot.isEmpty()) "(0)" else null)

        val at = hidden.collect(SearchLinks.allTrails(lat, lon, radiusKm.roundToInt().coerceAtLeast(1)), maxMs = 20_000)
        val trails = JsonHarvester.harvest(at.texts, Source.ALLTRAILS)
        publish(
            Source.ALLTRAILS, trails,
            when {
                trails.isNotEmpty() -> null
                at.blocked -> "⚠ vérification"
                else -> "(0)"
            },
        )
    }
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (recent != null && System.currentTimeMillis() - recent.time < 10 * 60 * 1000) return recent
    val provider = when {
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> return recent
    }
    val fresh = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                if (cont.isActive) cont.resume(loc)
            }
        }
    }
    return fresh ?: recent
}

@Suppress("unused")
private fun fmt(v: Double) = String.format(Locale.FRANCE, "%.1f", v)
