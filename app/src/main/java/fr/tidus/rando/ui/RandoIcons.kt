package fr.tidus.rando.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icônes au trait, style des applis récentes (24 × 24). */
object RandoIcons {
    private fun build(name: String, fill: List<String> = emptyList(), stroke: List<String> = emptyList()): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        fill.forEach { d ->
            b.addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
        }
        stroke.forEach { d ->
            b.addPath(
                PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Moon = build(
        "moon",
        fill = listOf("M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36c-0.98,1.37 -2.58,2.26 -4.4,2.26c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z"),
    )
    val Sun = build(
        "sun",
        fill = listOf("M7.5,12a4.5,4.5 0 1,0 9,0a4.5,4.5 0 1,0 -9,0z"),
        stroke = listOf("M12,1.8V3.6 M12,20.4V22.2 M1.8,12H3.6 M20.4,12H22.2 M4.8,4.8L6.1,6.1 M17.9,17.9L19.2,19.2 M4.8,19.2L6.1,17.9 M17.9,6.1L19.2,4.8"),
    )
    val Layers = build("layers", stroke = listOf("M12,3L2,8.5L12,14L22,8.5Z", "M2,13L12,18.5L22,13", "M2,17.5L12,23L22,17.5"))
    val Car = build(
        "car",
        fill = listOf("M18.92,6.01C18.72,5.42 18.16,5 17.5,5h-11c-0.66,0 -1.21,0.42 -1.42,1.01L3,12v8c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-1h12v1c0,0.55 0.45,1 1,1h1c0.55,0 1,-0.45 1,-1v-8l-2.08,-5.99zM6.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,13 6.5,13s1.5,0.67 1.5,1.5S7.33,16 6.5,16zM17.5,16c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5s-0.67,1.5 -1.5,1.5zM5,11l1.5,-4.5h11L19,11H5z"),
    )
    val Mountain = build("mountain", stroke = listOf("M2.5,20L9.5,7.5L13.5,14L16,10.5L21.5,20Z"))
    val Clock = build("clock", stroke = listOf("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0", "M12,7V12L15,14"))
    val Route = build(
        "route",
        stroke = listOf("M3,19a2,2 0 1,0 4,0a2,2 0 1,0 -4,0", "M17,5a2,2 0 1,0 4,0a2,2 0 1,0 -4,0", "M7,19H15a3.5,3.5 0 0,0 0,-7H9a3.5,3.5 0 0,1 0,-7H17"),
    )
    val TrendUp = build("trendUp", stroke = listOf("M3,17L9,11L13,15L21,7", "M15,7H21V13"))
    val TrendDown = build("trendDown", stroke = listOf("M3,7L9,13L13,9L21,17", "M15,17H21V11"))
    val Target = build(
        "target",
        fill = listOf("M10,12a2,2 0 1,0 4,0a2,2 0 1,0 -4,0z"),
        stroke = listOf("M12,2V5 M12,19V22 M2,12H5 M19,12H22", "M6,12a6,6 0 1,0 12,0a6,6 0 1,0 -12,0"),
    )
    val External = build("external", stroke = listOf("M14,4H20V10", "M20,4L11,13", "M18,14V19a1,1 0 0,1 -1,1H5a1,1 0 0,1 -1,-1V7a1,1 0 0,1 1,-1H10"))
    val Map = build("map", stroke = listOf("M9,4L3,6V20L9,18L15,20L21,18V4L15,6Z", "M9,4V18", "M15,6V20"))
    val Bookmark = build("bookmark", stroke = listOf("M6,3H18V21L12,17L6,21Z"))
    val Backpack = build(
        "backpack",
        stroke = listOf("M6,9a4,4 0 0,1 4,-4H14a4,4 0 0,1 4,4V20a1,1 0 0,1 -1,1H7a1,1 0 0,1 -1,-1Z", "M9,5V3.5H15V5", "M9,13H15V17H9Z"),
    )
    val Sparkle = build("sparkle", fill = listOf("M12,2L14.2,9.8L22,12L14.2,14.2L12,22L9.8,14.2L2,12L9.8,9.8Z"))
}
