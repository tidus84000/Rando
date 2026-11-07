package fr.tidus.rando.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Stockage local des randos : un fichier JSON par rando, tout reste sur le téléphone. */
class HikeStore(context: Context) {
    private val dir = File(context.filesDir, "hikes").apply { mkdirs() }

    fun all(): List<Hike> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { Hike.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun get(id: String): Hike? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching { Hike.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun save(hike: Hike) {
        File(dir, "${hike.id}.json").writeText(hike.toJson().toString())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
