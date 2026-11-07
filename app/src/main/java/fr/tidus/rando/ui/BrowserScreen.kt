package fr.tidus.rando.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.tidus.rando.data.Hike
import fr.tidus.rando.importers.ElevationService
import fr.tidus.rando.importers.GpxParser
import fr.tidus.rando.importers.Http
import fr.tidus.rando.importers.LinkImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Script injecté dans chaque page : repère le GPX quel que soit le moyen utilisé par le site
 * (fetch, XMLHttpRequest, Blob généré en JavaScript, window.open) et le transmet à l'appli.
 */
private const val HOOK_JS = """
(function(){
  if (window.__randoHook) return; window.__randoHook = true;
  function chk(t){ try { if (t && typeof t === 'string' && t.indexOf('<gpx') >= 0) RandoBridge.onGpx(t); } catch(e){} }
  function abs(u){ try { return String(new URL(u, location.href)); } catch(e){ return String(u); } }
  function isGpx(u){ return /gpx/i.test(String(u)) && !/\.(js|css|png|jpe?g|svg|webp|gif|woff2?)(\?|$)/i.test(String(u)); }
  var of = window.fetch;
  if (of) window.fetch = function(input){
    try { var u = (input && input.url) ? input.url : input; if (isGpx(u)) RandoBridge.onUrl(abs(u)); } catch(e){}
    return of.apply(this, arguments).then(function(r){ try { r.clone().text().then(chk); } catch(e){} return r; });
  };
  var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u){ try { if (isGpx(u)) RandoBridge.onUrl(abs(u)); } catch(e){} return oo.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(){
    var x = this;
    x.addEventListener('load', function(){
      try {
        if (x.responseType === '' || x.responseType === 'text') chk(x.responseText);
        else if (x.response && x.response.text) x.response.text().then(chk);
      } catch(e){}
    });
    return os.apply(this, arguments);
  };
  var oc = URL.createObjectURL;
  URL.createObjectURL = function(b){ try { if (b && b.text) b.text().then(chk); } catch(e){} return oc.apply(this, arguments); };
  var ow = window.open;
  window.open = function(u){ try { if (u && isGpx(u)) { RandoBridge.onUrl(abs(u)); return null; } } catch(e){} return ow.apply(this, arguments); };
  document.addEventListener('click', function(e){
    try {
      var el = e.target.closest('a,button,[onclick],[data-href],[data-url]');
      if (!el) return;
      var s = (el.textContent || '') + ' ' + (el.getAttribute('href') || '') + ' ' + (el.className || '');
      if (/gpx/i.test(s)) {
        RandoBridge.onClickInfo(el.outerHTML.substring(0, 300));
        var h = el.getAttribute('data-href') || el.getAttribute('data-url') || el.getAttribute('href');
        if (h && h !== '#' && h.indexOf('javascript') !== 0) RandoBridge.onUrl(abs(h));
      }
    } catch(err){}
  }, true);
})();
"""

private val STATIC = Regex("""\.(js|css|png|jpe?g|svg|webp|gif|woff2?|ico)(\?|$)""", RegexOption.IGNORE_CASE)
private fun looksLikeGpxUrl(u: String) = u.contains("gpx", ignoreCase = true) && !STATIC.containsMatchIn(u)

/**
 * Navigateur intégré : sert à se connecter à Komoot / Visorando quand le tracé n'est pas public.
 * Dès qu'un GPX passe par la page (lien, script, fichier généré, téléchargement), il est importé.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(url: String, onBack: () -> Unit, onImported: (Hike) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var diag by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val tried = remember { HashSet<String>() }
    var done by remember { mutableStateOf(false) }

    fun importGpxText(gpx: String, pageUrl: String?) {
        if (done) return
        done = true
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
                    val name = parsed.name ?: webView?.title?.substringBefore(" - ")?.ifBlank { null } ?: "Rando $source"
                    ElevationService.fillMissing(LinkImporter.newHike(name, source, pageUrl ?: url, parsed.points))
                }
                onImported(hike)
            } catch (e: Exception) {
                done = false
                status = e.message ?: "GPX illisible"
            } finally {
                busy = false
            }
        }
    }

    /** Une adresse qui ressemble à un GPX : on la récupère nous-mêmes avec la session du navigateur. */
    fun tryGpxUrl(gpxUrl: String, pageUrl: String?) {
        if (done || !tried.add(gpxUrl)) return
        diag = "Lien GPX repéré : $gpxUrl"
        scope.launch {
            busy = true
            val text = withContext(Dispatchers.IO) { Http.getText(gpxUrl, "application/gpx+xml, application/xml, */*", pageUrl) }
            busy = false
            if (GpxParser.looksLikeGpx(text)) {
                importGpxText(text!!, pageUrl)
            } else if (!done) {
                status = "Le site n'a pas renvoyé de GPX. Es-tu bien connecté ? Connecte-toi puis retouche « GPX »."
                tried.remove(gpxUrl)
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
                    Column {
                        Text(
                            status ?: "Connecte-toi si besoin, puis touche le bouton GPX de la page.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        diag?.let {
                            Text(
                                it, maxLines = 3,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        val self = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = Http.UA
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onGpx(text: String) {
                                main.post { importGpxText(text, self.url) }
                            }

                            @JavascriptInterface
                            fun onUrl(u: String) {
                                main.post { tryGpxUrl(u, self.url) }
                            }

                            @JavascriptInterface
                            fun onClickInfo(html: String) {
                                main.post { if (diag == null) diag = "Bouton : $html" }
                            }

                            @JavascriptInterface
                            fun onError(msg: String) {
                                main.post { status = "Téléchargement impossible : $msg" }
                            }
                        }, "RandoBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                                view.evaluateJavascript(HOOK_JS, null)
                            }

                            override fun onPageFinished(view: WebView, u: String?) {
                                view.evaluateJavascript(HOOK_JS, null)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                if (looksLikeGpxUrl(u)) {
                                    tryGpxUrl(u, view.url)
                                    return true
                                }
                                return false
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val u = request.url.toString()
                                if (looksLikeGpxUrl(u)) main.post { tryGpxUrl(u, self.url) }
                                return null
                            }
                        }

                        // Liens qui s'ouvrent dans un nouvel onglet : on récupère l'adresse visée
                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                val popup = WebView(view.context)
                                popup.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                        val u = request.url.toString()
                                        if (looksLikeGpxUrl(u)) tryGpxUrl(u, self.url) else self.loadUrl(u)
                                        v.destroy()
                                        return true
                                    }
                                }
                                popup.setDownloadListener { u, _, _, _, _ -> tryGpxUrl(u, self.url) }
                                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }
                        }

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
                                tried.remove(dlUrl)
                                tryGpxUrl(dlUrl, page)
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
