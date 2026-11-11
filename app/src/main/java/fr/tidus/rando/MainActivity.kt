package fr.tidus.rando

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import fr.tidus.rando.data.HikeStore
import fr.tidus.rando.ui.Pending
import fr.tidus.rando.ui.RandoApp
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    private val pending = mutableStateOf<Pending?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        fr.tidus.rando.importers.Http.initUserAgent(this)
        val store = HikeStore(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            RandoApp(
                store = store,
                pending = pending.value,
                onPendingConsumed = { pending.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT)
                val stream = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                when {
                    stream != null -> pending.value = Pending.File(stream)
                    !text.isNullOrBlank() -> pending.value = Pending.Link(text)
                }
            }
            Intent.ACTION_VIEW -> i.data?.let { pending.value = Pending.File(it) }
        }
    }
}
