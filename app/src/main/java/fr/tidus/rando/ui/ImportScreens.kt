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
    onOpenBrowser: (String) -> Unit,
    importFile: suspend (Uri) -> Hike,
) {
    var text by remember { mutableStateOf(prefill) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsBrowser by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun run() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            needsBrowser = false
            try {
                onImported(LinkImporter.import(text))
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
            Text("Lien Komoot ou Visorando", style = MaterialTheme.typography.titleMedium)
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
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                        val url = LinkImporter.extractUrl(text)
                        if (needsBrowser && url != null) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { onOpenBrowser(url) }) { Text("Ouvrir dans le navigateur intégré") }
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
                "Astuce : dans l'appli Komoot ou Visorando, touche « Partager » sur une rando et choisis Rando, " +
                    "l'import se lance tout seul.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Navigateur intégré : sert à se connecter à Komoot / Visorando quand le tracé n'est pas public.
 * Dès qu'un GPX est téléchargé sur la page, il est intercepté et importé.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(url: String, onBack: () -> Unit, onImported: (Hike) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }

    fun importGpxText(gpx: String, pageUrl: String?) {
        scope.launch {
            busy = true
            try {
                val hike = withContext(Dispatchers.IO) {
                    val source = when {
                        pageUrl?.contains("komoot") == true -> "Komoot"
                        pageUrl?.contains("visorando") == true -> "Visorando"
                        else -> "GPX"
                    }
                    val parsed = GpxParser.parse(gpx)
                    ElevationService.fillMissing(
                        LinkImporter.newHike(parsed.name ?: "Rando $source", source, pageUrl ?: url, parsed.points)
                    )
                }
                onImported(hike)
            } catch (e: Exception) {
                status = e.message ?: "GPX illisible"
            } finally {
                busy = false
            }
        }
    }

    fun retryAuto() {
        val current = webView?.url ?: url
        scope.launch {
            busy = true
            status = null
            try {
                onImported(LinkImporter.import(current))
            } catch (e: Exception) {
                status = e.message
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navigateur") },
                navigationIcon = {
                    IconButton(onClick = {
                        val w = webView
                        if (w != null && w.canGoBack()) w.goBack() else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                actions = {
                    TextButton(onClick = { retryAuto() }, enabled = !busy) { Text("Importer cette page") }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "Recharger") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Card(
                Modifier.fillMaxWidth().padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        status ?: "Connecte-toi si besoin, puis touche le bouton de téléchargement GPX de la page " +
                            "(ou « Importer cette page »).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = Http.UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onGpx(text: String) {
                                main.post { importGpxText(text, this@apply.url) }
                            }

                            @JavascriptInterface
                            fun onError(msg: String) {
                                main.post { status = "Téléchargement impossible : $msg" }
                            }
                        }, "RandoBridge")
                        setDownloadListener { dlUrl, _, _, _, _ ->
                            if (dlUrl.startsWith("blob:") || dlUrl.startsWith("data:")) {
                                val safe = dlUrl.replace("'", "%27")
                                evaluateJavascript(
                                    "fetch('$safe').then(r=>r.text()).then(t=>RandoBridge.onGpx(t))" +
                                        ".catch(e=>RandoBridge.onError(String(e)))",
                                    null,
                                )
                            } else {
                                val page = this.url
                                scope.launch {
                                    busy = true
                                    val text = withContext(Dispatchers.IO) {
                                        Http.getText(dlUrl, "application/gpx+xml, */*", page)
                                    }
                                    busy = false
                                    if (GpxParser.looksLikeGpx(text)) importGpxText(text!!, page)
                                    else status = "Le fichier téléchargé n'est pas un GPX."
                                }
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
            )
        }
    }
}
