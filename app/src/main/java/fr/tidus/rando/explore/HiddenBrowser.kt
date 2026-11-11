package fr.tidus.rando.explore

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import fr.tidus.rando.importers.Http
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONTokener
import java.util.Collections
import kotlin.coroutines.resume

/** Capte les réponses JSON (fetch / XHR) des pages chargées. */
private const val JSON_HOOK = """
(function(){
  if (window.__randoJson) return; window.__randoJson = true;
  function send(t){ try { if (t && t.length > 80 && t.length < 6000000 && (/^\s*[\[{]/.test(t) || /<gpx|<kml|<coordinates/i.test(t.substring(0, 3000)))) RandoHidden.onJson(t); } catch(e){} }
  var of = window.fetch;
  if (of) window.fetch = function(){
    return of.apply(this, arguments).then(function(r){ try { r.clone().text().then(send); } catch(e){} return r; });
  };
  var os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(){
    var x = this;
    x.addEventListener('load', function(){ try { if (x.responseType === '' || x.responseType === 'text' || x.responseType === 'json') send(typeof x.response === 'string' ? x.response : JSON.stringify(x.response)); } catch(e){} });
    return os.apply(this, arguments);
  };
})();
"""

/** Fait défiler la page pour déclencher le chargement des cartes et contenus « paresseux ». */
private const val SCROLL_JS = """
(function(){
  var y = 0, n = 0;
  var t = setInterval(function(){
    y += 700; window.scrollTo(0, y); n++;
    document.querySelectorAll('.leaflet-container, .mapboxgl-map, .maplibregl-map, [class*="map" i]').forEach(function(m){
      try { m.scrollIntoView(); } catch(e){}
    });
    if (n > 12) { clearInterval(t); window.scrollTo(0, 0); }
  }, 350);
})();
"""

data class Collected(val texts: List<String>, val blocked: Boolean, val finalUrl: String?)

/**
 * Navigateur invisible : charge la page de recherche d'un site comme un vrai navigateur
 * (cookies, JavaScript, protections anti-robots) et récupère les données qu'elle reçoit.
 */
class HiddenBrowser {
    private var webView: WebView? = null
    private val mutex = Mutex()
    @Volatile private var sink: MutableList<String>? = null
    @Volatile private var lastActivity = 0L
    @Volatile private var blocked = false

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(w: WebView) {
        webView = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = Http.UA
        w.settings.blockNetworkImage = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(object {
            @JavascriptInterface
            fun onJson(t: String) {
                sink?.add(t)
                lastActivity = System.currentTimeMillis()
            }

        }, "RandoHidden")
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(JSON_HOOK, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(JSON_HOOK, null)
                view.evaluateJavascript(SCROLL_JS, null)
                lastActivity = System.currentTimeMillis()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (u.contains("captcha-delivery") || u.contains("geo.captcha") || u.contains("challenges.cloudflare")) blocked = true
                return null
            }
        }
    }

    fun detach() {
        webView = null
    }

    suspend fun collect(url: String, maxMs: Long = 16_000, quietMs: Long = 3_500): Collected = mutex.withLock {
        val w = webView ?: return Collected(emptyList(), false, null)
        val list = Collections.synchronizedList(ArrayList<String>())
        sink = list
        blocked = false
        lastActivity = System.currentTimeMillis()
        val start = System.currentTimeMillis()
        w.loadUrl(url)
        while (System.currentTimeMillis() - start < maxMs) {
            delay(400)
            val now = System.currentTimeMillis()
            if (now - start > 6_000 && now - lastActivity > quietMs) break
        }
        val html = suspendCancellableCoroutine<String?> { c ->
            w.evaluateJavascript("document.documentElement.outerHTML") { res ->
                if (c.isActive) c.resume(runCatching { JSONTokener(res).nextValue() as? String }.getOrNull())
            }
        }
        if (html != null) list.add(html)
        val finalUrl = w.url
        sink = null
        w.stopLoading()
        Collected(ArrayList(list), blocked, finalUrl)
    }
}
