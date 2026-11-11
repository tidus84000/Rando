package fr.tidus.rando.explore

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Garde ce que l'appli a reçu lors du dernier import raté, pour pouvoir l'envoyer et corriger l'extraction.
 */
object Diagnostics {
    @Volatile private var lastUrl: String? = null
    @Volatile private var lastTexts: List<String> = emptyList()
    private val notes = ArrayList<String>()

    @Synchronized
    fun record(url: String, texts: List<String>, note: String) {
        lastUrl = url
        lastTexts = texts.toList()
        notes.clear()
        notes.add(note)
    }

    @Synchronized
    fun note(n: String) {
        notes.add(n)
    }

    fun hasData() = lastUrl != null

    @Synchronized
    fun share(context: Context) {
        val dir = File(context.cacheDir, "diag").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date())
        val f = File(dir, "rando-diagnostic-$stamp.txt")
        f.bufferedWriter().use { w ->
            w.write("URL : $lastUrl\n")
            notes.forEach { w.write("NOTE : $it\n") }
            w.write("Réponses : ${lastTexts.size}\n\n")
            var budget = 4_000_000
            lastTexts.forEachIndexed { i, t ->
                if (budget <= 0) return@forEachIndexed
                val part = t.take(minOf(600_000, budget))
                budget -= part.length
                w.write("=============== RÉPONSE ${i + 1} (${t.length} caractères) ===============\n")
                w.write(part)
                w.write("\n\n")
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Diagnostic Rando")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Envoyer le diagnostic").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
