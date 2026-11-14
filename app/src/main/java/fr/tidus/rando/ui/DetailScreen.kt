package fr.tidus.rando.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.tidus.rando.data.Geo
import fr.tidus.rando.gear.GearAdvisor
import fr.tidus.rando.gear.GearOptions
import fr.tidus.rando.gear.GearSection
import fr.tidus.rando.importers.Geocoding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.clickable
import fr.tidus.rando.data.Hike
import fr.tidus.rando.data.Profile
import fr.tidus.rando.weather.Level
import fr.tidus.rando.weather.LocationForecast
import fr.tidus.rando.weather.PointWeather
import fr.tidus.rando.weather.RouteWeather
import fr.tidus.rando.weather.WeatherCodes
import fr.tidus.rando.weather.WeatherService
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val DAY = DateTimeFormatter.ofPattern("EEE d", Locale.FRANCE)

private data class PaceOption(val label: String, val factor: Double)

private val PACES = listOf(
    PaceOption("Rapide", 0.85),
    PaceOption("Normale", 1.0),
    PaceOption("Tranquille", 1.2),
    PaceOption("Famille / pauses", 1.5),
)

@Composable
fun DetailScreen(hike: Hike, onBack: () -> Unit, onDelete: () -> Unit, onRename: (String) -> Unit) {
    val context = LocalContext.current
    val profile = remember(hike.id) { Geo.profile(hike.points) }
    val samples = remember(hike.id) { RouteWeather.samples(profile) }
    var pace by remember { mutableStateOf(1.0) }
    val hours = remember(hike.id, pace) { Geo.walkingHours(profile, pace) }
    val stats = remember(hike.id, pace) { Geo.stats(profile, pace) }

    val now = LocalDateTime.now()
    var date by remember { mutableStateOf(if (now.hour >= 14) now.toLocalDate().plusDays(1) else now.toLocalDate()) }
    var startMin by remember {
        mutableIntStateOf(if (now.hour in 6..13) ((now.hour + 1) * 60) else 9 * 60)
    }
    var forecasts by remember { mutableStateOf<List<LocationForecast>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var startAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hike.id) {
        hike.points.firstOrNull()?.let { p -> startAddress = Geocoding.reverse(p.lat, p.lon)?.label }
    }
    val prefs = remember { context.getSharedPreferences("rando", android.content.Context.MODE_PRIVATE) }
    var withDog by remember { mutableStateOf(prefs.getBoolean("gear_dog", false)) }
    var withChild by remember { mutableStateOf(prefs.getBoolean("gear_child", false)) }

    LaunchedEffect(hike.id, date, reload) {
        loading = true
        error = null
        forecasts = null
        try {
            forecasts = WeatherService.fetch(samples, date, date.plusDays(1))
        } catch (e: Exception) {
            error = e.message ?: "Météo indisponible"
        }
        loading = false
    }

    val start = date.atTime(startMin / 60, startMin % 60)
    val route = remember(forecasts, start, hours) {
        forecasts?.let { RouteWeather.evaluate(samples, it, hours, start) }
    }
    val best = remember(forecasts, hours, date) {
        forecasts?.let { RouteWeather.bestStart(samples, it, hours, date) }
    }

    val srcColor = sourceColor(hike.source)
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + pad.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------- Carte en tête ----------
            item {
                Box(Modifier.fillMaxWidth().height(330.dp)) {
                    TrackMap(profile, route?.points.orEmpty())
                    Box(
                        Modifier.fillMaxWidth().height(110.dp).background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent)),
                        ),
                    )
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OverlayButton(Icons.AutoMirrored.Filled.ArrowBack, "Retour", onBack)
                        Spacer(Modifier.weight(1f))
                        hike.sourceUrl?.let { u ->
                            OverlayButton(RandoIcons.External, "Fiche") {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        OverlayButton(Icons.Default.Edit, "Renommer") { renaming = true }
                        Spacer(Modifier.width(8.dp))
                        OverlayButton(Icons.Default.Delete, "Supprimer") { confirmDelete = true }
                    }
                }
            }
            // ---------- Titre + chiffres ----------
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Box(Modifier.background(srcColor.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(hike.source, style = MaterialTheme.typography.labelMedium, color = srcColor, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(hike.name, style = MaterialTheme.typography.headlineSmall)
                    startAddress?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(RandoIcons.Route, km(stats.distanceKm).removeSuffix(" km"), "km", Modifier.weight(1f))
                        StatTile(RandoIcons.TrendUp, "${stats.ascent.roundToInt()}", "m D+", Modifier.weight(1f))
                        StatTile(RandoIcons.Clock, duration(stats.durationHours), "durée", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(RandoIcons.Mountain, "${stats.maxEle.roundToInt()}", "m alt. max", Modifier.weight(1f))
                        StatTile(RandoIcons.TrendDown, "${stats.descent.roundToInt()}", "m D-", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                    val first = hike.points.firstOrNull()
                    Button(
                        onClick = { first?.let { driveTo(context, it.lat, it.lon) } },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(50),
                    ) {
                        Icon(RandoIcons.Car, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Y aller en voiture", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            // ---------- Profil ----------
            item {
                SectionCard {
                    ElevationChart(profile, route?.points.orEmpty(), Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp))
                }
            }
            // ---------- Départ ----------
            item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    StartCard(
                        date = date,
                        onDate = { date = it },
                        startMin = startMin,
                        onStart = { startMin = it },
                        pace = pace,
                        onPace = { pace = it },
                        endText = route?.end?.format(RouteWeather.HM)
                            ?: start.plusMinutes((stats.durationHours * 60).toLong()).format(RouteWeather.HM),
                    )
                }
            }
            if (best != null && route != null && best.start != start && best.penalty < route.penalty - 5) {
                item {
                    Surface(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(RandoIcons.Sparkle, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Meilleur créneau", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text("Départ à ${best.start.format(RouteWeather.HM)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            TextButton(onClick = { startMin = best.start.hour * 60 + best.start.minute }) { Text("Appliquer") }
                        }
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(50))) }
            error?.let { e ->
                item {
                    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Météo : $e", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Réessayer") }
                        }
                    }
                }
            }
            if (forecasts != null && route == null && !loading) {
                item { Text("Prévisions indisponibles pour cet horaire (jusqu'à 15 jours).", Modifier.padding(horizontal = 20.dp)) }
            }
            route?.let { r ->
                item { SectionTitle("Météo du parcours", trailing = { IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Actualiser") } }) }
                items(r.alerts) { a ->
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                            .background(levelColor(a.level).copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(levelColor(a.level), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text(a.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        r.sunrise?.let { SunPill("Lever", it.format(RouteWeather.HM), RandoIcons.Sun) }
                        r.sunset?.let { SunPill("Coucher", it.format(RouteWeather.HM), RandoIcons.Moon) }
                    }
                }
                item {
                    SectionCard {
                        Column {
                            r.points.forEachIndexed { i, w ->
                                PointRow(w, isSummit = w.loc.ele >= stats.maxEle - 0.5, first = i == 0, last = i == r.points.lastIndex)
                            }
                        }
                    }
                }
                item {
                    val fr = r.points.count { it.meteoFrance }
                    val provider = forecasts?.firstOrNull()?.provider ?: "Open-Meteo"
                    Text(
                        (if (provider != "Open-Meteo") "Prévisions MET Norway (service de secours, sans clé)"
                        else if (fr > 0) "Modèle Météo-France AROME 1,3 km / ARPEGE via Open-Meteo"
                        else "Modèles globaux via Open-Meteo (échéance lointaine, moins précis)") +
                            ", corrigé selon l'altitude de chaque point. Heures de passage estimées (DIN 33466).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            item {
                val sections = remember(stats, route, start, withDog, withChild) {
                    GearAdvisor.advise(stats, route, start, GearOptions(withDog, withChild))
                }
                Box(Modifier.padding(horizontal = 16.dp)) {
                    GearCard(
                        hikeId = hike.id,
                        sections = sections,
                        hasForecast = route != null,
                        withDog = withDog,
                        withChild = withChild,
                        onDog = { withDog = it; prefs.edit().putBoolean("gear_dog", it).apply() },
                        onChild = { withChild = it; prefs.edit().putBoolean("gear_child", it).apply() },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cette rando ?") },
            text = { Text(hike.name) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(hike.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Renommer") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { renaming = false; if (name.isNotBlank()) onRename(name.trim()) }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun StartCard(
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    startMin: Int,
    onStart: (Int) -> Unit,
    pace: Double,
    onPace: (Double) -> Unit,
    endText: String,
) {
    val today = LocalDate.now()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Quand pars-tu ?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items((0L..13L).toList()) { d ->
                    val day = today.plusDays(d)
                    val selected = day == date
                    Surface(
                        onClick = { onDate(day) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                when (d) { 0L -> "Auj."; 1L -> "Dem."; else -> day.format(DateTimeFormatter.ofPattern("EEE", Locale.FRANCE)).replaceFirstChar { it.uppercase() } },
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text("${day.dayOfMonth}", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            val h = startMin / 60
            val mn = startMin % 60
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Départ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${h}h${mn.toString().padStart(2, '0')}", style = StatNumber)
                }
                Text("→", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Arrivée vers", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(endText, style = StatNumber)
                }
            }
            Slider(
                value = startMin.toFloat(),
                onValueChange = { onStart(((it / 15f).roundToInt() * 15)) },
                valueRange = (4 * 60f)..(20 * 60f),
                steps = 63,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text("Allure", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                PACES.forEach { p ->
                    FilterChip(
                        selected = pace == p.factor, onClick = { onPace(p.factor) },
                        label = { Text(p.label) },
                        shape = RoundedCornerShape(50),
                    )
                }
            }
        }
    }
}

@Composable
private fun PointRow(w: PointWeather, isSummit: Boolean, first: Boolean, last: Boolean) {
    val color = levelColor(w.level)
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // frise : heure + pastille + trait
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.Start) {
            Text(w.time.format(RouteWeather.HM), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2
                if (!first) drawLine(line, Offset(cx, 0f), Offset(cx, 14f), strokeWidth = 4f)
                if (!last) drawLine(line, Offset(cx, 14f), Offset(cx, size.height), strokeWidth = 4f)
                drawCircle(color, 9f, Offset(cx, 14f))
                drawCircle(Color.White, 4f, Offset(cx, 14f))
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = if (last) 0.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${WeatherCodes.emoji(w.code, w.night)}  ${RouteWeather.fmtT(w.temp)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                w.feels?.let {
                    Text("  ressenti ${it.roundToInt()}°", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "km ${RouteWeather.fmt1(w.loc.km)} · ${w.loc.ele.roundToInt()} m" + if (isSummit) " · point haut" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val parts = mutableListOf<String>()
            parts += WeatherCodes.label(w.code)
            w.wind?.let { wind ->
                parts += "vent ${wind.roundToInt()} km/h ${cardinal(w.windDir)}" + (w.gust?.let { " (raf. ${it.roundToInt()})" } ?: "")
            }
            val rain = w.precip ?: 0.0
            val prob = w.precipProb
            if (rain > 0.05 || (prob ?: 0.0) >= 20) {
                parts += "pluie ${RouteWeather.fmt1(rain)} mm" + (prob?.let { " · ${it.roundToInt()} %" } ?: "")
            }
            w.freezing?.let { parts += "0 °C à ${(it / 50).roundToInt() * 50} m" }
            if ((w.visibility ?: 99999.0) < 5000) parts += "visibilité ${w.visibility!!.roundToInt()} m"
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun cardinal(deg: Double?): String {
    if (deg == null) return ""
    val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
    return dirs[((deg / 45.0).roundToInt() % 8 + 8) % 8]
}

private val OpenTopo = XYTileSource(
    "OpenTopoMap", 0, 17, 256, ".png",
    arrayOf(
        "https://a.tile.opentopomap.org/",
        "https://b.tile.opentopomap.org/",
        "https://c.tile.opentopomap.org/",
    ),
    "© OpenTopoMap (CC-BY-SA), © OpenStreetMap",
)

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun TrackMap(profile: Profile, weather: List<PointWeather>) {
    val ctx = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val style = remember { MapStyles.load(ctx) }
    val geo = remember(profile) { (0 until profile.size).map { GeoPoint(profile.lat[it], profile.lon[it]) } }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(Unit) { onDispose { mapRef?.onDetach() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(MapStyles.source(style, dark))
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                // laisse la carte gérer ses gestes au lieu de faire défiler la page
                setOnTouchListener { v, e ->
                    if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    false
                }
                mapRef = this
            }
        },
        update = { map ->
            map.overlays.clear()
            if (geo.size >= 2) {
                map.overlays.add(Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = android.graphics.Color.WHITE
                    outlinePaint.strokeWidth = 16f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                })
                map.overlays.add(Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = 0xFF16A06A.toInt()
                    outlinePaint.strokeWidth = 9f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                })
                map.overlays.add(Marker(map).apply {
                    position = geo.first()
                    title = "Départ"
                    icon = MapStyles.pin(ctx, 0xFF16A06A.toInt(), 26, true)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
                weather.filter { it.level == Level.WARN || it.level == Level.DANGER }.forEach { w ->
                    map.overlays.add(Marker(map).apply {
                        icon = MapStyles.pin(ctx, levelColor(w.level).toArgb(), 22)
                        position = GeoPoint(w.loc.lat, w.loc.lon)
                        title = "${w.time.format(RouteWeather.HM)} – ${WeatherCodes.label(w.code)}"
                        snippet = "Rafales ${w.gust?.roundToInt() ?: "—"} km/h · ${RouteWeather.fmtT(w.temp)}"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                if (map.tag != profile) {
                    map.tag = profile
                    val box = BoundingBox.fromGeoPoints(geo).increaseByScale(1.35f)
                    if (map.width > 0) map.zoomToBoundingBox(box, false)
                    else map.addOnFirstLayoutListener { _, _, _, _, _ -> map.zoomToBoundingBox(box, false) }
                }
            }
            map.invalidate()
        },
    )
}

@Composable
private fun ElevationChart(profile: Profile, weather: List<PointWeather>, modifier: Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.28f)
    val minE = remember(profile) { if (profile.size > 0) profile.ele.min() else 0.0 }
    val maxE = remember(profile) { if (profile.size > 0) profile.ele.max() else 1.0 }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Profil", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${minE.roundToInt()} – ${maxE.roundToInt()} m",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(modifier) {
            if (profile.size < 2) return@Canvas
            val total = profile.totalKm.coerceAtLeast(0.01)
            val range = (maxE - minE).coerceAtLeast(20.0)
            fun x(km: Double) = (km / total * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - minE) / range * size.height * 0.9 - size.height * 0.05).toFloat()
            val step = maxOf(1, profile.size / 600)
            val path = Path()
            path.moveTo(x(profile.km[0]), y(profile.ele[0]))
            var i = step
            while (i < profile.size) {
                path.lineTo(x(profile.km[i]), y(profile.ele[i]))
                i += step
            }
            path.lineTo(x(profile.km.last()), y(profile.ele.last()))
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(fillColor, fillColor.copy(alpha = 0f))))
            drawPath(path, lineColor, style = Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            weather.forEach { w ->
                drawCircle(levelColor(w.level), radius = 9f, center = Offset(x(w.loc.km), y(w.loc.ele)))
                drawCircle(Color.White, radius = 9f, center = Offset(x(w.loc.km), y(w.loc.ele)), style = Stroke(2f))
            }
        }
    }
}


private fun driveTo(context: android.content.Context, lat: Double, lon: Double) {
    val coords = String.format(Locale.US, "%.6f,%.6f", lat, lon)
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$coords&mode=d"))
    try {
        context.startActivity(nav)
    } catch (e: Exception) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords(Départ de la rando)")))
        }
    }
}

@Composable
private fun GearCard(
    hikeId: String,
    sections: List<GearSection>,
    hasForecast: Boolean,
    withDog: Boolean,
    withChild: Boolean,
    onDog: (Boolean) -> Unit,
    onChild: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("gear_checks", android.content.Context.MODE_PRIVATE) }
    var checked by remember(hikeId) { mutableStateOf(prefs.getStringSet(hikeId, emptySet())!!.toSet()) }
    var expanded by remember { mutableStateOf(true) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Icon(RandoIcons.Backpack, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Sac à préparer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val total = sections.sumOf { it.items.size }
                val done = sections.sumOf { s -> s.items.count { it.key in checked } }
                Text("$done/$total  ${if (expanded) "▲" else "▼"}", style = MaterialTheme.typography.labelLarge)
            }
            if (!hasForecast) {
                Text(
                    "Sans prévision météo pour ce créneau : conseils basés sur la saison.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = withDog, onClick = { onDog(!withDog) }, label = { Text("Avec un chien") }, shape = RoundedCornerShape(50))
                    FilterChip(selected = withChild, onClick = { onChild(!withChild) }, label = { Text("Enfant porté") }, shape = RoundedCornerShape(50))
                }
                sections.filter { it.items.isNotEmpty() }.forEach { sec ->
                    Text(sec.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    sec.items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            checked = if (item.key in checked) checked - item.key else checked + item.key
                            prefs.edit().putStringSet(hikeId, checked).apply()
                        }) {
                            Checkbox(checked = item.key in checked, onCheckedChange = null)
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                                item.why?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun StatTile(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, unit: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, style = fr.tidus.rando.ui.StatNumber, maxLines = 1)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Box(Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun SectionTitle(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SunPill(label: String, time: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(8.dp))
        Text("$label $time", style = MaterialTheme.typography.labelLarge)
    }
}
