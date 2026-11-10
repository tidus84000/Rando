package fr.tidus.rando.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import fr.tidus.rando.importers.Geocoding
import fr.tidus.rando.importers.Place
import fr.tidus.rando.importers.SearchLinks
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

@Composable
fun SearchScreen(initialPlace: Place?, onPlace: (Place?) -> Unit, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rando", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var useGps by remember { mutableStateOf(initialPlace == null || initialPlace.label.startsWith("Ma position")) }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Place>>(emptyList()) }
    var place by remember { mutableStateOf(initialPlace) }
    androidx.compose.runtime.LaunchedEffect(place) { onPlace(place) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var radius by remember { mutableIntStateOf(prefs.getInt("radius", 15)) }

    fun locate() {
        scope.launch {
            busy = true
            error = null
            val loc = currentLocation(context)
            if (loc == null) {
                error = "Position introuvable. Active la localisation du téléphone, ou utilise une adresse."
            } else {
                val rev = Geocoding.reverse(loc.latitude, loc.longitude)
                place = Place(
                    "Ma position" + (rev?.city?.let { " ($it)" } ?: ""),
                    rev?.city, loc.latitude, loc.longitude,
                )
            }
            busy = false
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) locate() else error = "Sans autorisation de localisation, utilise une adresse."
    }

    fun askLocation() {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) locate() else permission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trouver une rando") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Autour de…", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = useGps, onClick = { useGps = true; place = null }, label = { Text("📍 Ma position") })
                FilterChip(selected = !useGps, onClick = { useGps = false; place = null }, label = { Text("🏠 Une adresse") })
            }
            if (useGps) {
                Button(onClick = { askLocation() }, enabled = !busy) { Text("Me localiser") }
            } else {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Adresse, ville, lieu…") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            suggestions = Geocoding.search(query)
                            if (suggestions.isEmpty()) error = "Aucun lieu trouvé."
                            busy = false
                        }
                    },
                    enabled = !busy && query.isNotBlank(),
                ) { Text("Chercher l'adresse") }
                suggestions.forEach { s ->
                    Card(Modifier.fillMaxWidth().clickable { place = s; suggestions = emptyList() }) {
                        Text(s.label, Modifier.padding(12.dp))
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            place?.let { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("✅ ${p.label}", fontWeight = FontWeight.SemiBold)
                        Text(
                            String.format(Locale.FRANCE, "%.5f, %.5f", p.lat, p.lon),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text("Rayon : $radius km", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = radius.toFloat(),
                    onValueChange = { radius = it.roundToInt(); prefs.edit().putInt("radius", radius).apply() },
                    valueRange = 2f..50f,
                )
                Text("Voir les randos sur…", style = MaterialTheme.typography.titleMedium)
                SiteCard("Komoot", "Randos à moins de $radius km, sur la carte Komoot") {
                    onOpen(SearchLinks.komoot(p.lat, p.lon, radius))
                }
                SiteCard("AllTrails", "Sentiers dans un carré de ${radius * 2} km autour du point") {
                    onOpen(SearchLinks.allTrails(p.lat, p.lon, radius))
                }
                SiteCard(
                    "Visorando",
                    if (p.city != null) "Randos autour de ${p.city} (Visorando ne filtre pas par rayon)"
                    else "Recherche Visorando",
                ) { onOpen(SearchLinks.visorando(p.city)) }
                Text(
                    "Ouvre une rando dans la liste puis touche « Importer cette rando » : elle arrive dans Rando avec sa météo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SiteCard(name: String, desc: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
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
            LocationManagerCompat.getCurrentLocation(
                lm, provider, signal, ContextCompat.getMainExecutor(context),
            ) { loc -> if (cont.isActive) cont.resume(loc) }
        }
    }
    return fresh ?: recent
}
