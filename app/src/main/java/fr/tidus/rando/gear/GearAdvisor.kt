package fr.tidus.rando.gear

import fr.tidus.rando.data.HikeStats
import fr.tidus.rando.weather.RouteForecast
import java.time.LocalDateTime
import java.time.Month
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class GearItem(val key: String, val text: String, val why: String? = null)
data class GearSection(val title: String, val items: List<GearItem>)

data class GearOptions(val dog: Boolean, val child: Boolean)

/**
 * Recommandations de sac selon la rando (durée, dénivelé, altitude) et la météo prévue à chaque passage.
 * Sans prévision (date trop lointaine), on se base sur la saison.
 */
object GearAdvisor {
    fun advise(stats: HikeStats, forecast: RouteForecast?, start: LocalDateTime, opt: GearOptions): List<GearSection> {
        val h = stats.durationHours
        val pts = forecast?.points.orEmpty()
        val month = start.month
        val summer = month in listOf(Month.JUNE, Month.JULY, Month.AUGUST)
        val winter = month in listOf(Month.DECEMBER, Month.JANUARY, Month.FEBRUARY)

        val tMax = pts.mapNotNull { it.temp }.maxOrNull() ?: if (summer) 27.0 else if (winter) 5.0 else 16.0
        val feelsMin = pts.mapNotNull { it.feels ?: it.temp }.minOrNull() ?: if (winter) -2.0 else if (summer) 14.0 else 6.0
        val rainProb = pts.mapNotNull { it.precipProb }.maxOrNull() ?: 0.0
        val rainMm = pts.sumOf { it.precip ?: 0.0 }
        val gust = pts.mapNotNull { it.gust }.maxOrNull() ?: 0.0
        val uv = pts.mapNotNull { it.uv }.maxOrNull() ?: if (summer) 7.0 else 3.0
        val snow = pts.any { (it.snowfall ?: 0.0) > 0 }
        val freezing = pts.mapNotNull { it.freezing }.minOrNull()
        val icy = (freezing != null && freezing < stats.maxEle && feelsMin <= 2) || snow || (winter && stats.maxEle > 1200)
        val end = forecast?.end ?: start.plusMinutes((h * 60).toLong())
        val sunset = forecast?.sunset
        val darkRisk = (sunset != null && end.isAfter(sunset.minusMinutes(45))) || h > 6
        val mountain = stats.maxEle > 1500 || stats.ascent > 800
        val coversLunch = start.toLocalTime().isBefore(java.time.LocalTime.of(12, 30)) &&
            end.toLocalTime().isAfter(java.time.LocalTime.of(13, 0))

        // ---------- Eau ----------
        val ratePerHour = when {
            tMax >= 30 -> 0.9
            tMax >= 25 -> 0.7
            tMax >= 18 -> 0.55
            else -> 0.4
        } + if (stats.ascent / max(h, 0.5) > 300) 0.1 else 0.0
        val water = roundQuarter(max(0.5, ratePerHour * h))
        val waterItems = mutableListOf(
            GearItem("eau", "${fmtL(water)} d'eau par adulte", "≈ ${fmtL(ratePerHour)}/h × ${fmtH(h)} de marche, ${tMax.roundToInt()} °C max"),
        )
        if (tMax >= 25 && h >= 3) waterItems += GearItem("electrolytes", "Pastilles ou boisson d'électrolytes", "Chaleur + effort long : compenser le sel perdu")
        if (opt.dog) {
            val dogWater = roundQuarter(max(0.5, (if (tMax >= 25) 0.35 else 0.2) * h))
            waterItems += GearItem("eau_chien", "${fmtL(dogWater)} d'eau pour le chien + gamelle pliable", if (tMax >= 25) "Par forte chaleur, le chien boit beaucoup : pauses à l'ombre" else null)
        }
        if (opt.child) waterItems += GearItem("eau_enfant", "Biberon / gourde de l'enfant (≈ 0,5 L)")
        if (h >= 5) waterItems += GearItem("filtre", "Pastilles de purification ou filtre, si points d'eau sur le parcours")

        // ---------- Nourriture ----------
        val kcal = (h * 250 + stats.ascent * 0.6).roundToInt() / 50 * 50
        val food = mutableListOf(
            GearItem("en_cas", "En-cas : ${snackCount(h)} (barres, fruits secs, banane, chocolat)", "Environ $kcal kcal dépensées : grignoter un peu toutes les heures"),
        )
        if (coversLunch || h >= 4) food += GearItem("repas", "Pique-nique complet (sandwich, fromage, fruit)", if (coversLunch) "La rando passe l'heure du déjeuner" else "Plus de 4 h d'effort")
        if (h >= 6) food += GearItem("reserve", "Réserve de secours (1 barre ou sachet de fruits secs en plus)")
        if (feelsMin <= 5) food += GearItem("thermos", "Thermos de boisson chaude", "Ressenti jusqu'à ${feelsMin.roundToInt()} °C")
        if (opt.child) food += GearItem("repas_enfant", "Repas / goûter de l'enfant, bavoir, lingettes")
        if (opt.dog && h >= 3) food += GearItem("croquettes", "Croquettes ou friandises pour le chien")

        // ---------- Vêtements ----------
        val clothes = mutableListOf<GearItem>()
        clothes += if (stats.ascent > 400 || stats.maxEle > 1000 || rainMm > 1)
            GearItem("chaussures", "Chaussures de randonnée montantes", "${stats.ascent.roundToInt()} m de D+, terrain potentiellement cassant ou humide")
        else GearItem("chaussures", "Chaussures de marche ou de trail confortables")
        clothes += GearItem("chaussettes", "Chaussettes de rando (+ une paire de rechange si > 4 h ou pluie)")
        if (rainProb >= 30 || rainMm > 0.2 || mountain) {
            clothes += GearItem("pluie", "Veste imperméable", if (rainProb > 0) "Pluie jusqu'à ${rainProb.roundToInt()} % de probabilité" else "En montagne, la météo change vite")
        } else if (gust >= 30) {
            clothes += GearItem("coupe_vent", "Coupe-vent", "Rafales jusqu'à ${gust.roundToInt()} km/h")
        }
        if (feelsMin < 12 || mountain) clothes += GearItem("polaire", "Couche chaude (polaire ou doudoune légère)", "Ressenti mini ${feelsMin.roundToInt()} °C, plus frais aux pauses et en altitude")
        if (feelsMin <= 3) clothes += GearItem("bonnet", "Bonnet et gants")
        if (uv >= 5 || tMax >= 24) clothes += GearItem("casquette", "Casquette ou chapeau", "UV ${uv.roundToInt()}")
        if (opt.child) clothes += GearItem("habits_enfant", "Couche chaude, chapeau et change pour l'enfant (il ne marche pas, il a froid plus vite)")

        // ---------- Matériel ----------
        val gear = mutableListOf(
            GearItem("sac", "Sac à dos ${if (h >= 5 || opt.child) "25-35 L" else "15-25 L"}"),
            GearItem("telephone", "Téléphone chargé, rando importée dans Rando" + if (h >= 4) " + batterie externe" else ""),
            GearItem("pharmacie", "Mini-trousse de secours (pansements, ampoules, désinfectant, antidouleur)"),
        )
        if (stats.ascent > 600 || stats.descent > 600 || opt.child) gear += GearItem("batons", "Bâtons de marche", "Soulagent les genoux à la descente (${stats.descent.roundToInt()} m)")
        if (uv >= 5 || stats.maxEle > 1500) gear += GearItem("solaire", "Crème solaire et lunettes de soleil", "UV ${uv.roundToInt()}" + if (stats.maxEle > 1500) ", altitude ${stats.maxEle.roundToInt()} m" else "")
        if (darkRisk) gear += GearItem("frontale", "Lampe frontale", sunset?.let { "Coucher du soleil à ${it.format(java.time.format.DateTimeFormatter.ofPattern("HH'h'mm"))}" } ?: "Rando longue")
        if (icy) gear += GearItem("crampons", "Microcrampons / chaînes à neige pour chaussures", "Gel ou neige possible sur la partie haute (${stats.maxEle.roundToInt()} m)")
        if (mountain || h >= 5) gear += GearItem("survie", "Couverture de survie et sifflet")
        if (opt.child) gear += GearItem("porte_bebe", "Porte-bébé, couches, crème solaire enfant")
        if (opt.dog) gear += GearItem("chien", "Laisse, sacs à déjections, pince à tiques")
        gear += GearItem("papiers", "Carte d'identité, un peu de liquide, clés de voiture")

        // ---------- Sécurité ----------
        val safety = mutableListOf<GearItem>()
        forecast?.alerts?.filter { it.level.ordinal >= fr.tidus.rando.weather.Level.WARN.ordinal }?.forEach {
            safety += GearItem("alerte_${it.text.hashCode()}", it.text)
        }
        if (mountain || h >= 5) safety += GearItem("prevenir", "Prévenir quelqu'un de l'itinéraire et de l'heure de retour prévue")
        safety += GearItem("urgence", "Numéro d'urgence : 112 (fonctionne même sans réseau de ton opérateur)")

        return listOf(
            GearSection("💧 Eau", waterItems),
            GearSection("🍫 Nourriture", food),
            GearSection("🧥 Vêtements", clothes),
            GearSection("🎒 Matériel", gear),
            GearSection("⚠️ Sécurité", safety),
        )
    }

    private fun roundQuarter(v: Double) = ceil(v * 4) / 4
    private fun fmtL(v: Double) = String.format(Locale.FRANCE, "%.2f", v).trimEnd('0').trimEnd(',') + " L"
    private fun fmtH(h: Double): String {
        val m = (h * 60).roundToInt()
        return "${m / 60}h${(m % 60).toString().padStart(2, '0')}"
    }

    private fun snackCount(h: Double): String {
        val n = max(1, ceil(h - 0.5).toInt())
        return if (n == 1) "1 en-cas" else "$n en-cas"
    }
}
