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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
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
    var style by mutableStateOf<MapStyle?>(null)
}

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

    var showStyles by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.style == null) state.style = MapStyles.load(context) }
    val style = state.style ?: MapStyle.MODERN

    Box(Modifier.fillMaxSize()) {
        DisposableEffect(Unit) { onDispose { state.running = emptySet() } }

        ExploreMap(
            state = state,
            style = style,
            dark = dark,
            savedHikes = savedHikes,
            onMap = { mapRef = it },
            onSelect = { state.selected = it },
            onOpenHike = onOpenHike,
        )

        // ---------- Haut : barre de recherche flottante + sites ----------
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 12.dp, end = 12.dp)) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 6.dp,
                tonalElevation = 2.dp,
            ) {
                Row(Modifier.height(56.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        if (query.isEmpty()) {
                            Text("Où veux-tu randonner ?", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                                if (query.isNotBlank()) scope.launch {
                                    suggestions = Geocoding.search(query)
                                    if (suggestions.isEmpty()) message = "Aucun lieu trouvé."
                                }
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; suggestions = emptyList() }) { Icon(Icons.Default.Close, "Effacer") }
                    }
                    IconButton(onClick = onToggleDark) {
                        Icon(if (dark) RandoIcons.Sun else RandoIcons.Moon, "Thème", modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                Surface(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        suggestions.forEach { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    suggestions = emptyList()
                                    query = s.label
                                    val p = GeoPoint(s.lat, s.lon)
                                    state.recenter = p to 12.0
                                    search(p, 10.0)
                                }.padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.size(12.dp))
                                Text(s.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Source.entries.toList()) { src ->
                    val n = state.results[src]?.size
                    val running = src in state.running
                    val st = state.status[src]
                    val on = src in state.filter
                    Surface(
                        onClick = {
                            if (st != null && st.startsWith("⚠") && src == Source.ALLTRAILS) {
                                state.searchCenter?.let { c ->
                                    onOpenBrowser(SearchLinks.allTrails(c.latitude, c.longitude, state.searchRadiusKm.roundToInt()), false)
                                }
                            } else {
                                state.filter = if (on) state.filter - src else state.filter + src
                            }
                        },
                        shape = RoundedCornerShape(50),
                        color = if (on) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                        shadowElevation = 3.dp,
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(10.dp).background(if (on) Color(src.color) else Color.Gray, CircleShape),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                src.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(6.dp))
                            when {
                                running -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color(src.color))
                                st != null && st.startsWith("⚠") -> Text("vérifier", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                n != null -> Box(
                                    Modifier.background(Color(src.color).copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
                                ) { Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                    }
                }
            }
        }

        // ---------- Bas : boutons flottants + fiche ----------
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                if (state.selected == null) {
                    Surface(
                        onClick = { if (state.running.isEmpty()) searchVisibleArea() },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 6.dp,
                    ) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.running.isNotEmpty()) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.size(10.dp))
                                Text("Recherche en cours…", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Rechercher dans cette zone", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showStyles) {
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp) {
                            Column(Modifier.padding(6.dp)) {
                                MapStyle.entries.forEach { s ->
                                    Row(
                                        Modifier.clip(RoundedCornerShape(12.dp))
                                            .background(if (s == style) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                            .clickable {
                                                state.style = s
                                                MapStyles.save(context, s)
                                                showStyles = false
                                            }.padding(horizontal = 14.dp, vertical = 10.dp),
                                    ) { Text(s.label, style = MaterialTheme.typography.labelLarge) }
                                }
                            }
                        }
                    }
                    MapFab(RandoIcons.Layers, "Fond de carte") { showStyles = !showStyles }
                    MapFab(RandoIcons.Target, "Ma position") { askLocation(true) }
                }
            }
            message?.let {
                Surface(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { message = null },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 4.dp,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
                        if (!importing && fr.tidus.rando.explore.Diagnostics.hasData()) {
                            Text(
                                "Envoyer le diagnostic",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.inversePrimary,
                                modifier = Modifier.padding(top = 8.dp).clickable { fr.tidus.rando.explore.Diagnostics.share(context) },
                            )
                        }
                    }
                }
            }
            state.selected?.let { sel ->
                CandidateCard(
                    c = sel,
                    from = state.myLocation ?: state.searchCenter,
                    importing = importing,
                    onClose = { state.selected = null },
                    onImport = { importCandidate(sel) },
                    onView = { onOpenBrowser(sel.url, false) },
                )
            }
        }
    }
}

@Composable
private fun MapFab(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.size(50.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun StatPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(5.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
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
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 18.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(50)))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Box(
                    Modifier.background(Color(c.source.color).copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(c.source.label, style = MaterialTheme.typography.labelMedium, color = Color(c.source.color), fontWeight = FontWeight.Bold)
                }
                c.difficulty?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it.replaceFirstChar { ch -> ch.uppercase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fermer") }
            }
            Text(
                c.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp)) {
                c.distanceKm?.let { StatPill(RandoIcons.Route, km(it)) }
                c.ascent?.let { StatPill(RandoIcons.TrendUp, "${it.roundToInt()} m") }
                c.durationMin?.let { StatPill(RandoIcons.Clock, "${it / 60}h${(it % 60).toString().padStart(2, '0')}") }
            }
            from?.let {
                Text(
                    "À ${km(Geo.haversineKm(it.latitude, it.longitude, c.lat, c.lon))} de toi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(end = 8.dp)) {
                Button(
                    onClick = onImport, enabled = !importing,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(50),
                ) {
                    if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Importer", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = onView,
                    modifier = Modifier.height(50.dp),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(RandoIcons.External, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Fiche")
                }
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun ExploreMap(
    state: ExploreState,
    style: MapStyle,
    dark: Boolean,
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
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                isVerticalMapRepetitionEnabled = false
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
            val src = MapStyles.source(style, dark)
            if (m.tileProvider.tileSource.name() != src.name()) m.setTileSource(src)
            state.recenter?.let { (p, z) ->
                m.controller.setZoom(z)
                m.controller.animateTo(p)
                state.recenter = null
            }
            m.overlays.clear()
            state.searchCenter?.let { c ->
                m.overlays.add(Polygon(m).apply {
                    setPoints(Polygon.pointsAsCircle(c, state.searchRadiusKm * 1000))
                    fillPaint.color = android.graphics.Color.argb(18, 22, 160, 106)
                    outlinePaint.color = android.graphics.Color.argb(150, 22, 160, 106)
                    outlinePaint.strokeWidth = 4f
                    outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
                    setOnClickListener { _, _, _ -> false }
                })
            }
            savedTracks.forEach { (id, name, pts) ->
                if (pts.size >= 2) {
                    m.overlays.add(Polyline(m).apply {
                        setPoints(pts)
                        outlinePaint.color = android.graphics.Color.WHITE
                        outlinePaint.strokeWidth = 14f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    })
                    m.overlays.add(Polyline(m).apply {
                        setPoints(pts)
                        outlinePaint.color = 0xFF8B5CF6.toInt()
                        outlinePaint.strokeWidth = 8f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        title = name
                        setOnClickListener { _, _, _ -> onOpenHike(id); true }
                    })
                }
            }
            state.myLocation?.let { p ->
                m.overlays.add(Marker(m).apply {
                    position = p
                    icon = MapStyles.pin(context, 0xFF2563EB.toInt(), 20)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Ma position"
                })
            }
            state.results.forEach { (srcKey, list) ->
                if (srcKey !in state.filter) return@forEach
                list.forEach { c ->
                    val selected = state.selected?.url == c.url
                    m.overlays.add(Marker(m).apply {
                        position = GeoPoint(c.lat, c.lon)
                        icon = MapStyles.pin(context, srcKey.color, if (selected) 34 else 24, selected)
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
