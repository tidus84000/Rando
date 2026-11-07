package fr.tidus.rando.importers

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class ImportException(message: String, val needsBrowser: Boolean = false) : Exception(message)

/**
 * Partage les cookies avec le navigateur intégré : si tu te connectes à Komoot ou Visorando
 * dans l'appli, les imports automatiques profitent de ta session.
 */
object WebViewCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return emptyList()
        val raw = cm.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

object Http {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; SM-S937B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** Client sans cookies pour les API publiques (météo). */
    val plain: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    fun get(url: String, accept: String? = null, referer: String? = null): Response {
        val b = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept-Language", "fr-FR,fr;q=0.9")
        if (accept != null) b.header("Accept", accept)
        if (referer != null) b.header("Referer", referer)
        return client.newCall(b.build()).execute()
    }

    /** Renvoie le texte de la réponse, ou null si erreur HTTP. */
    fun getText(url: String, accept: String? = null, referer: String? = null): String? =
        runCatching {
            get(url, accept, referer).use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
}
