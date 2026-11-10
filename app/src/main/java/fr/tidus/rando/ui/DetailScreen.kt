package fr.tidus.rando.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(hike.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                actions = {
                    hike.sourceUrl?.let { u ->
                        IconButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                        }) { Text("🔗") }
                    }
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Renommer") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Supprimer") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth().height(260.dp)) {
                    TrackMap(profile, route?.points.orEmpty())
                }
            }
            item {
                Text(
                    "${km(stats.distanceKm)} · ↗ ${m(stats.ascent)} · ↘ ${m(stats.descent)} · " +
                        "⛰ ${m(stats.maxEle)} · ⏱ ${duration(stats.durationHours)}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    ElevationChart(profile, route?.points.orEmpty(), Modifier.fillMaxWidth().height(130.dp).padding(12.dp))
                }
            }
            item {
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
            item {
                val first = hike.points.firstOrNull()
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Point de départ", style = MaterialTheme.typography.titleSmall)
                            Text(
                                startAddress ?: first?.let { String.format(Locale.FRANCE, "%.5f, %.5f", it.lat, it.lon) } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = { first?.let { driveTo(context, it.lat, it.lon) } }) { Text("🚗 Y aller") }
                    }
                }
            }
            if (best != null && route != null && best.start != start && best.penalty < route.penalty - 5) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "💡 Meilleur créneau ce jour-là : départ à ${best.start.format(RouteWeather.HM)}",
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            TextButton(onClick = { startMin = best.start.hour * 60 + best.start.minute }) { Text("Appliquer") }
                        }
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { e ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Météo : $e", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Réessayer") }
                        }
                    }
                }
            }
            if (forecasts != null && route == null && !loading) {
                item { Text("Prévisions indisponibles pour cet horaire (les prévisions vont jusqu'à 15 jours).") }
            }
            route?.let { r ->
                items(r.alerts) { a ->
                    Card(colors = CardDefaults.cardColors(containerColor = levelColor(a.level).copy(alpha = 0.16f))) {
                        Text(a.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    Text(
                        listOfNotNull(
                            r.sunrise?.let { "🌅 Lever ${it.format(RouteWeather.HM)}" },
                            r.sunset?.let { "🌇 Coucher ${it.format(RouteWeather.HM)}" },
                        ).joinToString("   ·   "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Le long du parcours", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Actualiser") }
                    }
                }
                items(r.points) { w -> PointRow(w, isSummit = w.loc.ele >= stats.maxEle - 0.5) }
                item {
                    val fr = r.points.count { it.meteoFrance }
                    Text(
                        (if (fr > 0) "Modèle Météo-France AROME 1,3 km / ARPEGE" else "Modèles globaux (échéance lointaine, moins précis)") +
                            " via Open-Meteo, température corrigée selon l'altitude de chaque point. " +
                            "Heures de passage estimées (méthode DIN 33466).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                val sections = remember(stats, route, start, withDog, withChild) {
                    GearAdvisor.advise(stats, route, start, GearOptions(withDog, withChild))
                }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Départ", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items((0L..13L).toList()) { d ->
                    val day = today.plusDays(d)
                    val label = when (d) {
                        0L -> "Aujourd'hui"
                        1L -> "Demain"
                        else -> day.format(DAY).replaceFirstChar { it.uppercase() }
                    }
                    FilterChip(selected = day == date, onClick = { onDate(day) }, label = { Text(label) })
                }
            }
            val h = startMin / 60
            val mn = startMin % 60
            Text(
                "Départ ${h}h${mn.toString().padStart(2, '0')}  →  arrivée vers $endText",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Slider(
                value = startMin.toFloat(),
                onValueChange = { onStart(((it / 15f).roundToInt() * 15)) },
                valueRange = (4 * 60f)..(20 * 60f),
                steps = 63,
            )
            Text("Allure", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PACES.forEach { p ->
                    FilterChip(selected = pace == p.factor, onClick = { onPace(p.factor) }, label = { Text(p.label) })
                }
            }
        }
    }
}

@Composable
private fun PointRow(w: PointWeather, isSummit: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(levelColor(w.level)))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    "${w.time.format(RouteWeather.HM)} · km ${RouteWeather.fmt1(w.loc.km)} · ${w.loc.ele.roundToInt()} m" +
                        if (isSummit) "  ⛰ point haut" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${WeatherCodes.emoji(w.code, w.night)}  ${WeatherCodes.label(w.code)} · ${RouteWeather.fmtT(w.temp)}" +
                        (w.feels?.let { " (ressenti ${it.roundToInt()} °C)" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val parts = mutableListOf<String>()
                w.wind?.let { wind ->
                    parts += "💨 ${wind.roundToInt()} km/h ${cardinal(w.windDir)}" +
                        (w.gust?.let { " (rafales ${it.roundToInt()})" } ?: "")
                }
                val rain = w.precip ?: 0.0
                val prob = w.precipProb
                if (rain > 0.05 || (prob ?: 0.0) >= 20) {
                    parts += "💧 ${RouteWeather.fmt1(rain)} mm" + (prob?.let { " · ${it.roundToInt()} %" } ?: "")
                }
                w.cloud?.let { parts += "☁️ ${it.roundToInt()} %" }
                w.freezing?.let { parts += "0 °C à ${(it / 50).roundToInt() * 50} m" }
                if ((w.visibility ?: 99999.0) < 5000) parts += "👁 ${w.visibility!!.roundToInt()} m"
                Text(parts.joinToString("   "), style = MaterialTheme.typography.bodySmall)
            }
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
    val geo = remember(profile) { (0 until profile.size).map { GeoPoint(profile.lat[it], profile.lon[it]) } }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(Unit) { onDispose { mapRef?.onDetach() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(OpenTopo)
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
                val line = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = 0xFFD84315.toInt()
                    outlinePaint.strokeWidth = 9f
                }
                map.overlays.add(line)
                map.overlays.add(Marker(map).apply {
                    position = geo.first()
                    title = "Départ"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
                weather.filter { it.level == Level.WARN || it.level == Level.DANGER }.forEach { w ->
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(w.loc.lat, w.loc.lon)
                        title = "${w.time.format(RouteWeather.HM)} – ${WeatherCodes.label(w.code)}"
                        snippet = "Rafales ${w.gust?.roundToInt() ?: "—"} km/h · ${RouteWeather.fmtT(w.temp)}"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                if (map.tag != profile) {
                    map.tag = profile
                    val box = BoundingBox.fromGeoPoints(geo).increaseByScale(1.25f)
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
    val fillColor = lineColor.copy(alpha = 0.18f)
    val minE = remember(profile) { if (profile.size > 0) profile.ele.min() else 0.0 }
    val maxE = remember(profile) { if (profile.size > 0) profile.ele.max() else 1.0 }
    Column {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp)) {
            Text("Profil", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${minE.roundToInt()} – ${maxE.roundToInt()} m", style = MaterialTheme.typography.labelMedium)
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
            drawPath(fill, fillColor)
            drawPath(path, lineColor, style = Stroke(width = 4f))
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text("🎒 Sac à préparer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                    FilterChip(selected = withDog, onClick = { onDog(!withDog) }, label = { Text("🐕 Avec un chien") })
                    FilterChip(selected = withChild, onClick = { onChild(!withChild) }, label = { Text("👶 Enfant porté") })
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
