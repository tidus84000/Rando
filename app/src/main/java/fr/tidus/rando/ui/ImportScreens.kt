package fr.tidus.rando.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.tidus.rando.data.Hike
import fr.tidus.rando.importers.ElevationService
import fr.tidus.rando.importers.GpxParser
import fr.tidus.rando.importers.Http
import fr.tidus.rando.importers.ImportException
import fr.tidus.rando.importers.LinkImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AddScreen(
    prefill: String,
    auto: Boolean,
    onBack: () -> Unit,
    onImported: (Hike) -> Unit,
    onOpenBrowser: (String, Boolean) -> Unit,
    importFile: suspend (Uri) -> Hike,
    hidden: fr.tidus.rando.explore.HiddenBrowser? = null,
) {
    var text by remember { mutableStateOf(prefill) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsBrowser by remember { mutableStateOf(false) }
    var progressMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun run() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            needsBrowser = false
            try {
                onImported(fr.tidus.rando.explore.SmartImport.import(text, hidden, null) { progressMsg = it })
            } catch (e: ImportException) {
                error = e.message
                needsBrowser = e.needsBrowser
            } catch (e: Exception) {
                error = "Import impossible : ${e.message ?: e.javaClass.simpleName}"
                needsBrowser = true
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try {
                onImported(importFile(uri))
            } catch (e: Exception) {
                error = e.message ?: "Fichier GPX illisible"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { if (auto && text.isNotBlank()) run() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajouter une rando") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Lien Komoot, Visorando ou AllTrails", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://www.komoot.com/fr-fr/tour/…") },
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { text = it } }) { Text("Coller") }
                Button(onClick = { run() }, enabled = !busy && text.isNotBlank()) { Text("Récupérer la rando") }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                progressMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            error?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                        val url = LinkImporter.extractUrl(text)
                        if (needsBrowser && url != null) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { onOpenBrowser(url, false) }) { Text("Ouvrir dans le navigateur intégré") }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Ou depuis un fichier", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) {
                Text("Importer un fichier GPX")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Astuce : dans l'appli Komoot, Visorando ou AllTrails, touche « Partager » sur une rando et choisis Rando, " +
                    "l'import se lance tout seul.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

