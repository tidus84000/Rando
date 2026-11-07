package fr.tidus.rando.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
    data class Browser(val url: String) : Screen
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
            is Screen.Browser -> Screen.Add(s.url)
            else -> Screen.Home
        }
    }

    RandoTheme(dark) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                hikes = hikes,
                dark = dark,
                snackbar = snackbar,
                onToggleDark = {
                    dark = !dark
                    prefs.edit().putBoolean("dark", dark).apply()
                },
                onAdd = { screen = Screen.Add() },
                onOpen = { screen = Screen.Detail(it.id) },
            )
            is Screen.Add -> AddScreen(
                prefill = s.prefill,
                auto = s.auto,
                onBack = { screen = Screen.Home },
                onImported = { saved(it) },
                onOpenBrowser = { screen = Screen.Browser(it) },
                importFile = { uri -> importUri(context, uri) },
            )
            is Screen.Browser -> BrowserScreen(
                url = s.url,
                onBack = { screen = Screen.Add(s.url) },
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

suspend fun importUri(context: Context, uri: Uri): Hike = withContext(Dispatchers.IO) {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.') ?: "Rando importée"
    val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        ?: throw Exception("Impossible de lire le fichier")
    val parsed = GpxParser.parse(text)
    ElevationService.fillMissing(LinkImporter.newHike(parsed.name ?: name, "GPX", null, parsed.points))
}

@Composable
private fun HomeScreen(
    hikes: List<Hike>,
    dark: Boolean,
    snackbar: SnackbarHostState,
    onToggleDark: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Hike) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rando", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onToggleDark) { Text(if (dark) "☀️" else "🌙") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Ajouter") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (hikes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🥾", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("Aucune rando pour l'instant", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Dans Komoot ou Visorando, touche « Partager » puis choisis Rando. " +
                            "Tu peux aussi coller un lien ou importer un fichier GPX avec le bouton Ajouter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(hikes, key = { it.id }) { h ->
                    val stats = remember(h.id, h.points.size) { Geo.stats(Geo.profile(h.points)) }
                    Card(Modifier.fillMaxWidth().clickable { onOpen(h) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                h.name, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${km(stats.distanceKm)} · ↗ ${m(stats.ascent)} · ⏱ ${duration(stats.durationHours)} · ${h.source}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

fun km(v: Double) = String.format(java.util.Locale.FRANCE, "%.1f km", v)
fun m(v: Double) = "${Math.round(v)} m"
fun duration(hours: Double): String {
    val total = Math.round(hours * 60)
    return "${total / 60}h${(total % 60).toString().padStart(2, '0')}"
}
