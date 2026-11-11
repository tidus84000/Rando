package fr.tidus.rando.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.tidus.rando.data.Geo
import fr.tidus.rando.data.Hike
import fr.tidus.rando.data.HikeStore
import fr.tidus.rando.importers.ElevationService
import fr.tidus.rando.importers.GpxParser
import fr.tidus.rando.importers.LinkImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Pending {
    data class Link(val text: String) : Pending
    data class File(val uri: Uri) : Pending
}

sealed interface Screen {
    data object Home : Screen
    data class Add(val prefill: String = "", val auto: Boolean = false) : Screen
    data class Browser(val url: String, val auto: Boolean = false, val fromSearch: Boolean = false) : Screen
    data class Detail(val id: String) : Screen
}

@Composable
fun RandoApp(store: HikeStore, pending: Pending?, onPendingConsumed: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rando", Context.MODE_PRIVATE) }
    val systemDark = isSystemInDarkTheme()
    var dark by remember { mutableStateOf(if (prefs.contains("dark")) prefs.getBoolean("dark", true) else systemDark) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var version by remember { mutableIntStateOf(0) }
    val hikes = remember(version) { store.all() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val explore = remember { ExploreState() }
    val hidden = remember { fr.tidus.rando.explore.HiddenBrowser() }
    var tab by remember { mutableIntStateOf(0) }

    fun saved(h: Hike) {
        store.save(h)
        version++
        screen = Screen.Detail(h.id)
    }

    LaunchedEffect(pending) {
        when (pending) {
            is Pending.Link -> screen = Screen.Add(pending.text, auto = true)
            is Pending.File -> {
                try {
                    saved(importUri(context, pending.uri))
                } catch (e: Exception) {
                    snackbar.showSnackbar(e.message ?: "Fichier illisible")
                }
            }
            null -> {}
        }
        if (pending != null) onPendingConsumed()
    }

    BackHandler(enabled = screen != Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Browser -> if (s.fromSearch) Screen.Home else Screen.Add(s.url)
            else -> Screen.Home
        }
    }

    RandoTheme(dark) {
      Box(Modifier.fillMaxSize()) {
        // Navigateur invisible partagé (sous les écrans) : imports et recherche en arrière-plan
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> android.webkit.WebView(ctx).also { hidden.attach(it) } },
        )
        when (val s = screen) {
            Screen.Home -> {
                val toggleDark = {
                    dark = !dark
                    prefs.edit().putBoolean("dark", dark).apply()
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp,
                        ) {
                            NavigationBarItem(
                                selected = tab == 0, onClick = { tab = 0 },
                                icon = { Icon(RandoIcons.Map, null) }, label = { Text("Explorer") },
                            )
                            NavigationBarItem(
                                selected = tab == 1, onClick = { tab = 1 },
                                icon = {
                                    androidx.compose.material3.BadgedBox(badge = {
                                        if (hikes.isNotEmpty()) androidx.compose.material3.Badge { Text("${hikes.size}") }
                                    }) { Icon(RandoIcons.Bookmark, null) }
                                },
                                label = { Text("Mes randos") },
                            )
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
                        if (tab == 0) {
                            ExploreScreen(
                                state = explore,
                                hidden = hidden,
                                savedHikes = hikes,
                                dark = dark,
                                onToggleDark = toggleDark,
                                onImported = { saved(it) },
                                onOpenHike = { screen = Screen.Detail(it) },
                                onOpenBrowser = { u, auto -> screen = Screen.Browser(u, auto, fromSearch = true) },
                            )
                        } else {
                            HomeScreen(
                                hikes = hikes,
                                dark = dark,
                                snackbar = snackbar,
                                onToggleDark = toggleDark,
                                onAdd = { screen = Screen.Add() },
                                onSearch = { tab = 0 },
                                onOpen = { screen = Screen.Detail(it.id) },
                            )
                        }
                    }
                }
            }
            is Screen.Add -> AddScreen(
                prefill = s.prefill,
                auto = s.auto,
                onBack = { screen = Screen.Home },
                onImported = { saved(it) },
                onOpenBrowser = { u, auto -> screen = Screen.Browser(u, auto) },
                importFile = { uri -> importUri(context, uri) },
                hidden = hidden,
            )
            is Screen.Browser -> BrowserScreen(
                url = s.url,
                autoImport = s.auto,
                hidden = hidden,
                onBack = { screen = if (s.fromSearch) Screen.Home else Screen.Add(s.url) },
                onImported = { saved(it) },
            )
            is Screen.Detail -> {
                val hike = hikes.firstOrNull { it.id == s.id } ?: store.get(s.id)
                if (hike == null) {
                    LaunchedEffect(s.id) { screen = Screen.Home }
                } else {
                    DetailScreen(
                        hike = hike,
                        onBack = { screen = Screen.Home },
                        onDelete = {
                            store.delete(hike.id)
                            version++
                            screen = Screen.Home
                        },
                        onRename = { name ->
                            store.save(hike.copy(name = name))
                            version++
                        },
                    )
                }
            }
        }
      }
    }
}

suspend fun importUri(context: Context, uri: Uri): Hike = withContext(Dispatchers.IO) {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.') ?: "Rando importée"
    val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        ?: throw Exception("Impossible de lire le fichier")
    val parsed = GpxParser.parse(text)
    // provenance : d'abord l'en-tête du fichier, sinon la fiche ouverte récemment avec 🌐
    var source = "GPX"
    var url: String? = null
    GpxParser.detectSource(text)?.let { (s, l) -> source = s; url = l }
    val prefs = context.getSharedPreferences("rando", Context.MODE_PRIVATE)
    val extUrl = prefs.getString("external_url", null)
    val extAt = prefs.getLong("external_at", 0)
    if (extUrl != null && System.currentTimeMillis() - extAt < 60 * 60 * 1000) {
        val extSource = when {
            "alltrails" in extUrl -> "AllTrails"
            "komoot" in extUrl -> "Komoot"
            "visorando" in extUrl -> "Visorando"
            else -> null
        }
        if (source == "GPX" && extSource != null) source = extSource
        if (url == null && extSource == source) url = extUrl
        prefs.edit().remove("external_url").apply()
    }
    ElevationService.fillMissing(LinkImporter.newHike(parsed.name ?: name, source, url, parsed.points))
}

@Composable
private fun HomeScreen(
    hikes: List<Hike>,
    dark: Boolean,
    snackbar: SnackbarHostState,
    onToggleDark: () -> Unit,
    onAdd: () -> Unit,
    onSearch: () -> Unit,
    onOpen: (Hike) -> Unit,
) {
    val allStats = remember(hikes) { hikes.associate { it.id to Geo.stats(Geo.profile(it.points)) } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Ajouter", style = MaterialTheme.typography.labelLarge) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mes randos", style = MaterialTheme.typography.headlineLarge)
                        if (hikes.isNotEmpty()) {
                            val total = allStats.values.sumOf { it.distanceKm }
                            Text(
                                "${hikes.size} rando${if (hikes.size > 1) "s" else ""} · ${km(total)} au total",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    RoundIconButton(RandoIcons.Map, "Carte", onSearch)
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(if (dark) RandoIcons.Sun else RandoIcons.Moon, "Thème", onToggleDark)
                }
            }
            if (hikes.isEmpty()) {
                item {
                    Surface(
                        Modifier.fillMaxWidth().padding(top = 24.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(RandoIcons.Mountain, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Ta première rando t'attend", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Explore la carte pour trouver des randos Komoot, Visorando et AllTrails autour de toi, " +
                                    "ou partage une rando vers Rando depuis une autre appli.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = onSearch, shape = RoundedCornerShape(50)) { Text("Explorer la carte") }
                        }
                    }
                }
            }
            items(hikes, key = { it.id }) { h ->
                val stats = allStats[h.id] ?: return@items
                HikeCard(h, stats) { onOpen(h) }
            }
        }
    }
}

@Composable
private fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, modifier = Modifier.size(20.dp)) }
    }
}

fun sourceColor(source: String): androidx.compose.ui.graphics.Color = when (source) {
    "Komoot" -> androidx.compose.ui.graphics.Color(0xFF6AA127)
    "Visorando" -> androidx.compose.ui.graphics.Color(0xFF1A7FC1)
    "AllTrails" -> androidx.compose.ui.graphics.Color(0xFFE8731C)
    else -> androidx.compose.ui.graphics.Color(0xFF8B5CF6)
}

@Composable
private fun HikeCard(h: Hike, stats: fr.tidus.rando.data.HikeStats, onClick: () -> Unit) {
    val color = sourceColor(h.source)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteThumb(h, color, Modifier.size(84.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(h.source, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(h.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStat(RandoIcons.Route, km(stats.distanceKm))
                    MiniStat(RandoIcons.TrendUp, m(stats.ascent))
                    MiniStat(RandoIcons.Clock, duration(stats.durationHours))
                }
            }
        }
    }
}

@Composable
private fun MiniStat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vignette : la forme du tracé, dessinée sur un fond doux. */
@Composable
private fun RouteThumb(h: Hike, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val pts = remember(h.id) {
        val step = maxOf(1, h.points.size / 150)
        h.points.filterIndexed { i, _ -> i % step == 0 }
    }
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(18.dp)).background(bg)) {
        if (pts.size < 2) return@Canvas
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val cosLat = kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2))
        val w = ((maxLon - minLon) * cosLat).coerceAtLeast(1e-6)
        val hgt = (maxLat - minLat).coerceAtLeast(1e-6)
        val pad = size.minDimension * 0.16f
        val scale = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / hgt).toFloat()
        val ox = (size.width - (w * scale).toFloat()) / 2
        val oy = (size.height - (hgt * scale).toFloat()) / 2
        fun x(lon: Double) = ox + ((lon - minLon) * cosLat * scale).toFloat()
        fun y(lat: Double) = oy + ((maxLat - lat) * scale).toFloat()
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(x(pts[0].lon), y(pts[0].lat))
        pts.drop(1).forEach { path.lineTo(x(it.lon), y(it.lat)) }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(androidx.compose.ui.graphics.Color.White, 7f, androidx.compose.ui.geometry.Offset(x(pts[0].lon), y(pts[0].lat)))
        drawCircle(color, 4.5f, androidx.compose.ui.geometry.Offset(x(pts[0].lon), y(pts[0].lat)))
    }
}

fun km(v: Double) = String.format(java.util.Locale.FRANCE, "%.1f km", v)
fun m(v: Double) = "${Math.round(v)} m"
fun duration(hours: Double): String {
    val total = Math.round(hours * 60)
    return "${total / 60}h${(total % 60).toString().padStart(2, '0')}"
}
