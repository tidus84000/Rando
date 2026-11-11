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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import fr.tidus.rando.importers.PageScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener

/**
 * Script injecté dans chaque page : repère le GPX quel que soit le moyen utilisé par le site
 * (fetch, XMLHttpRequest, Blob généré en JavaScript, window.open) et le transmet à l'appli.
 */
private const val HOOK_JS = """
(function(){
  if (window.__randoHook) return; window.__randoHook = true;
  function chk(t){ try {
    if (!t || typeof t !== 'string') return;
    if (t.indexOf('<gpx') >= 0) RandoBridge.onGpx(t);
    else if (t.length > 200 && t.length < 6000000 && (/^\s*[\[{]/.test(t) || /<kml|<coordinates/i.test(t.substring(0, 3000)))) RandoBridge.onData(t);
  } catch(e){} }
  function abs(u){ try { return String(new URL(u, location.href)); } catch(e){ return String(u); } }
  var of = window.fetch;
  if (of) window.fetch = function(){
    return of.apply(this, arguments).then(function(r){ try { r.clone().text().then(chk); } catch(e){} return r; });
  };
  var os = XMLHttpRequest.prototype.send;
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
  document.addEventListener('click', function(e){
    try {
      var el = e.target.closest('a,button,[onclick],[data-href],[data-url]');
      if (!el) return;
      var s = (el.textContent || '') + ' ' + (el.getAttribute('href') || '') + ' ' + (el.className || '');
      if (/gpx|t[ée]l[ée]charg|cliquez/i.test(s)) {
        RandoBridge.onLog('Bouton : ' + el.outerHTML.substring(0, 160));
        var h = el.getAttribute('data-href') || el.getAttribute('data-url') || el.getAttribute('href');
        if (h && h !== '#' && h.indexOf('javascript') !== 0) RandoBridge.onCandidate(abs(h));
      }
    } catch(err){}
  }, true);
})();
"""

/** Sur une page de téléchargement : liste tous les liens qui pourraient mener au fichier. */
private const val SCAN_JS = """
(function(){
  var out = [];
  function add(u){ try { u = String(new URL(u, location.href)); if (out.indexOf(u) < 0) out.push(u); } catch(e){} }
  document.querySelectorAll('a[href],iframe[src],[data-href],[data-url]').forEach(function(el){
    var u = el.getAttribute('href') || el.getAttribute('src') || el.getAttribute('data-href') || el.getAttribute('data-url');
    var t = (el.textContent || '');
    if (!u || u === '#' || u.indexOf('javascript') === 0) return;
    if (/gpx|t[ée]l[ée]charg|download|export|getRando/i.test(u) || /cliquez|t[ée]l[ée]charg/i.test(t)) add(u);
  });
  var html = document.documentElement.innerHTML;
  var re = /["'(]((?:https?:\/\/[^"'()\s]+|\/[^"'()\s]+)(?:gpx|telecharg|download)[^"'()\s]*)["')]/gi, m;
  while ((m = re.exec(html)) !== null) add(m[1].replace(/&amp;/g, '&'));
  var meta = document.querySelector('meta[http-equiv="refresh" i]');
  if (meta) { var c = meta.getAttribute('content') || ''; var i = c.toLowerCase().indexOf('url='); if (i >= 0) add(c.substring(i + 4)); }
  return JSON.stringify(out.slice(0, 15));
})();
"""

/** Retire les fenêtres par-dessus la page (inscription, cookies, « ouvre l'appli ») qui grisent l'écran. */
private const val UNBLOCK_JS = """
(function(){
  var n = 0;
  document.querySelectorAll('body *').forEach(function(el){
    var cs = getComputedStyle(el);
    if ((cs.position === 'fixed' || cs.position === 'sticky') && (parseInt(cs.zIndex) || 0) >= 10) {
      var r = el.getBoundingClientRect();
      if (r.width >= innerWidth * 0.6 && r.height >= innerHeight * 0.3) { el.remove(); n++; }
    }
  });
  document.querySelectorAll('dialog[open]').forEach(function(d){ d.close(); n++; });
  ['html', 'body'].forEach(function(t){
    var e = document.querySelector(t); if (!e) return;
    e.style.setProperty('overflow', 'auto', 'important');
    e.style.setProperty('position', 'static', 'important');
    e.classList.remove('modal-open', 'no-scroll', 'noscroll', 'overflow-hidden', 'is-locked');
  });
  document.querySelectorAll('[inert]').forEach(function(e){ e.removeAttribute('inert'); });
  document.querySelectorAll('[aria-hidden="true"]').forEach(function(e){ if (e.children.length > 3) e.removeAttribute('aria-hidden'); });
  return n;
})();
"""

private val STATIC = Regex("""\.(js|css|png|jpe?g|svg|webp|gif|woff2?|ico|mp4)(\?|$)""", RegexOption.IGNORE_CASE)
private val NOISE = Regex("""matomo|analytics|googletag|google-analytics|doubleclick|facebook|sentry|datadome|hotjar|criteo|adservice""", RegexOption.IGNORE_CASE)
private fun usable(u: String) = u.startsWith("http") && !STATIC.containsMatchIn(u) && !NOISE.containsMatchIn(u)
private fun mentionsGpx(u: String) = usable(u) && u.contains("gpx", ignoreCase = true)
private fun isDownloadPage(u: String?) = u != null && Regex("""t[ée]l[ée]charg|download""", RegexOption.IGNORE_CASE).containsMatchIn(u)

/** Page d'une rando sur un des sites pris en charge (pour afficher « Importer cette rando »). */
fun isHikePage(u: String?): Boolean {
    if (u == null) return false
    return Regex("""komoot\.[a-z.]+/(?:[a-z]{2}-[a-z]{2}/)?(?:smart)?tour/""").containsMatchIn(u) ||
        Regex("""visorando\.com/(?:en/)?(?:randonnee|walk)-[^/]+/?$""").containsMatchIn(u.substringBefore('?')) &&
        !u.contains(".html") ||
        Regex("""alltrails\.com/(?:[a-z]{2}/)?(?:trail|explore/trail)/""").containsMatchIn(u)
}

/**
 * Navigateur intégré : se connecter, chercher des randos sur Komoot / Visorando / AllTrails,
 * et importer la rando affichée. Tout GPX qui passe par la page est intercepté.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    url: String,
    autoImport: Boolean,
    onBack: () -> Unit,
    onImported: (Hike) -> Unit,
    hidden: fr.tidus.rando.explore.HiddenBrowser? = null,
) {
    val captured = remember { java.util.Collections.synchronizedList(ArrayList<String>()) }
    val fetched = remember { java.util.Collections.synchronizedList(ArrayList<String>()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf<String?>(url) }
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val tried = remember { HashSet<String>() }
    var done by remember { mutableStateOf(false) }
    var autoTried by remember { mutableStateOf(false) }

    fun addLog(s: String) {
        log.add(0, s.take(180))
        while (log.size > 4) log.removeAt(log.size - 1)
    }

    fun sourceOf(u: String?) = when {
        u == null -> "GPX"
        "komoot" in u -> "Komoot"
        "visorando" in u -> "Visorando"
        "alltrails" in u -> "AllTrails"
        else -> "GPX"
    }

    fun pageName(): String? = webView?.title
        ?.substringBefore(" | ")?.substringBefore(" - Visorando")?.substringBefore(" - komoot")
        ?.trim()?.ifBlank { null }

    fun importGpxText(gpx: String, pageUrl: String?) {
        if (done) return
        done = true
        scope.launch {
            busy = true
            try {
                val hike = withContext(Dispatchers.IO) {
                    val parsed = GpxParser.parse(gpx)
                    val src = sourceOf(pageUrl)
                    ElevationService.fillMissing(
                        LinkImporter.newHike(parsed.name ?: pageName() ?: "Rando $src", src, pageUrl ?: url, parsed.points)
                    )
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

    /** Une adresse candidate : on la télécharge nous-mêmes avec la session du navigateur. */
    fun tryUrl(candidate: String, pageUrl: String?, why: String) {
        if (done || !usable(candidate) || !tried.add(candidate)) return
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                Http.getText(candidate, "application/gpx+xml, application/xml, */*", pageUrl)
            }
            if (GpxParser.looksLikeGpx(text)) {
                addLog("GPX trouvé ($why)")
                importGpxText(text!!, pageUrl ?: currentUrl)
            } else if (text != null && text.contains("<html", true)) {
                if (fetched.size < 15) fetched.add("--- GET $candidate\n" + text.take(300_000))
                // page intermédiaire (« le téléchargement va démarrer… ») : on suit ses liens
                val next = fr.tidus.rando.importers.TrackExtractor.downloadLinks(text, candidate)
                addLog("Page intermédiaire, ${next.size} lien(s) à suivre")
                next.forEach { tryUrl(it, candidate, "lien de la page intermédiaire") }
            } else {
                addLog("$why, pas un GPX : $candidate")
            }
        }
    }

    fun scanPage(view: WebView) {
        view.evaluateJavascript(SCAN_JS) { res ->
            val json = runCatching { JSONTokener(res).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return@evaluateJavascript
            for (i in 0 until arr.length()) tryUrl(arr.getString(i), view.url, "lien de la page")
        }
    }

    /** Importer la page affichée : tracé contenu dans la page, sinon import automatique par lien. */
    fun importPage(silent: Boolean = false) {
        val w = webView ?: return
        val pageUrl = w.url ?: url
        busy = true
        if (!silent) status = "Recherche du tracé dans la page…"
        w.evaluateJavascript("document.documentElement.outerHTML") { res ->
            scope.launch {
                val html = runCatching { JSONTokener(res).nextValue() as? String }.getOrNull().orEmpty()
                val pts = withContext(Dispatchers.Default) {
                    fr.tidus.rando.importers.TrackExtractor.best(ArrayList(captured) + html)
                }
                if (pts.size >= 10) {
                    val src = sourceOf(pageUrl)
                    val name = PageScraper.title(html)?.substringBefore(" | ")?.substringBefore(" - ")?.trim()
                        ?.ifBlank { null } ?: pageName() ?: "Rando $src"
                    val hike = withContext(Dispatchers.IO) {
                        ElevationService.fillMissing(LinkImporter.newHike(name, src, pageUrl, pts))
                    }
                    busy = false
                    if (!done) {
                        done = true
                        onImported(hike)
                    }
                    return@launch
                }
                try {
                    val hike = fr.tidus.rando.explore.SmartImport.import(pageUrl, hidden, null) { if (!silent) status = it }
                    if (!done) {
                        done = true
                        onImported(hike)
                    }
                } catch (e: Exception) {
                    if (!silent) status = e.message
                } finally {
                    busy = false
                }
            }
        }
        scanPage(w)
    }

    androidx.activity.compose.BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onBack()
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
                    IconButton(onClick = {
                        val w = webView
                        if (w == null) return@IconButton
                        w.evaluateJavascript("document.documentElement.outerHTML") { res ->
                            val html = runCatching { JSONTokener(res).nextValue() as? String }.getOrNull().orEmpty()
                            fr.tidus.rando.explore.Diagnostics.record(
                                w.url ?: url,
                                ArrayList(fetched) + ArrayList(captured) + html,
                                "Navigateur. Journal : " + log.joinToString(" | "),
                            )
                            fr.tidus.rando.explore.Diagnostics.share(ctx)
                        }
                    }) { Text("📤") }
                    androidx.compose.material3.TextButton(onClick = {
                        webView?.evaluateJavascript(UNBLOCK_JS) { n -> addLog("Fenêtres retirées : $n") }
                    }) { Text("🧹 Débloquer") }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "Recharger") }
                },
            )
        },
        floatingActionButton = {
            if (isHikePage(currentUrl) || isDownloadPage(currentUrl)) {
                ExtendedFloatingActionButton(
                    onClick = { importPage() },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Importer cette rando") },
                )
            }
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
                            status ?: if (currentUrl?.contains("alltrails") == true && isHikePage(currentUrl))
                                "AllTrails : touche la petite carte pour l'agrandir (le tracé se charge), puis « Importer cette rando »."
                            else "Ouvre une rando puis touche « Importer cette rando », ou le bouton GPX du site.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        log.forEach {
                            Text(
                                it, maxLines = 2,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
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
                                    main.post { addLog("GPX généré par la page"); importGpxText(text, self.url) }
                                }

                                @JavascriptInterface
                                fun onData(t: String) {
                                    captured.add(t)
                                    while (captured.size > 30) captured.removeAt(0)
                                }

                                @JavascriptInterface
                                fun onCandidate(u: String) {
                                    main.post { tryUrl(u, self.url, "lien du bouton") }
                                }

                                @JavascriptInterface
                                fun onLog(msg: String) {
                                    main.post { addLog(msg) }
                                }
                            }, "RandoBridge")

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                                    currentUrl = u
                                    view.evaluateJavascript(HOOK_JS, null)
                                }

                                override fun onPageFinished(view: WebView, u: String?) {
                                    currentUrl = u
                                    view.evaluateJavascript(HOOK_JS, null)
                                    if (isDownloadPage(u)) {
                                        addLog("Page de téléchargement : $u")
                                        scanPage(view)
                                        // le site lance le fichier après quelques secondes : on re-scanne
                                        scope.launch { delay(3500); scanPage(view) }
                                    }
                                    if (autoImport && !autoTried && isHikePage(u)) {
                                        autoTried = true
                                        scope.launch { delay(2500); importPage(silent = true) }
                                    }
                                }

                                override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                                    currentUrl = u
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val u = request.url.toString()
                                    if (mentionsGpx(u)) {
                                        tryUrl(u, view.url, "navigation")
                                        return true
                                    }
                                    return false
                                }

                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val u = request.url.toString()
                                    if (mentionsGpx(u)) main.post { tryUrl(u, self.url, "requête") }
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
                                            if (mentionsGpx(u)) tryUrl(u, self.url, "nouvel onglet") else self.loadUrl(u)
                                            v.destroy()
                                            return true
                                        }
                                    }
                                    popup.setDownloadListener { u, _, _, _, _ -> tryUrl(u, self.url, "téléchargement") }
                                    (resultMsg.obj as WebView.WebViewTransport).webView = popup
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }

                            setDownloadListener { dlUrl, _, disposition, mime, _ ->
                                addLog("Téléchargement : ${mime ?: "?"} ${disposition ?: ""}")
                                if (dlUrl.startsWith("blob:") || dlUrl.startsWith("data:")) {
                                    val safe = dlUrl.replace("'", "%27")
                                    evaluateJavascript(
                                        "fetch('$safe').then(r=>r.text()).then(t=>RandoBridge.onGpx(t))" +
                                            ".catch(e=>RandoBridge.onLog('Erreur blob : '+e))",
                                        null,
                                    )
                                } else {
                                    tried.remove(dlUrl)
                                    tryUrl(dlUrl, this.url, "téléchargement")
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
}
