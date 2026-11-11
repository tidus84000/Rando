package fr.tidus.rando.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.tidus.rando.weather.Level

/** Couleurs de la marque : vert émeraude vif + accent lime, surfaces neutres légèrement teintées. */
object Brand {
    val Green = Color(0xFF16A06A)
    val GreenDark = Color(0xFF0E7A50)
    val Lime = Color(0xFFB8F36B)
    val Ink = Color(0xFF0D1311)
}

private val Dark = darkColorScheme(
    primary = Color(0xFF4ADE9A),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF14533A),
    onPrimaryContainer = Color(0xFFB9F6D6),
    secondary = Brand.Lime,
    onSecondary = Color(0xFF1C3300),
    secondaryContainer = Color(0xFF2A3A20),
    onSecondaryContainer = Color(0xFFDDF7BF),
    tertiary = Color(0xFFFFB86B),
    background = Color(0xFF0B0F0E),
    onBackground = Color(0xFFE6ECE9),
    surface = Color(0xFF0B0F0E),
    onSurface = Color(0xFFE6ECE9),
    surfaceVariant = Color(0xFF1E2725),
    onSurfaceVariant = Color(0xFFA9B6B1),
    surfaceContainerLowest = Color(0xFF070A09),
    surfaceContainerLow = Color(0xFF121816),
    surfaceContainer = Color(0xFF161D1B),
    surfaceContainerHigh = Color(0xFF1C2421),
    surfaceContainerHighest = Color(0xFF242E2B),
    outline = Color(0xFF3A4743),
    outlineVariant = Color(0xFF2A3431),
)

private val Light = lightColorScheme(
    primary = Brand.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F8E4),
    onPrimaryContainer = Color(0xFF002112),
    secondary = Color(0xFF4F7A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6F9CC),
    onSecondaryContainer = Color(0xFF1C3300),
    tertiary = Color(0xFFE07A1F),
    background = Color(0xFFF6F8F7),
    onBackground = Brand.Ink,
    surface = Color(0xFFF6F8F7),
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFE7ECEA),
    onSurfaceVariant = Color(0xFF5B6764),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFC),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEFF3F1),
    surfaceContainerHighest = Color(0xFFE6ECE9),
    outline = Color(0xFFC3CCC9),
    outlineVariant = Color(0xFFE0E6E3),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val base = Typography()
private val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
)

/** Gros chiffres des statistiques. */
val StatNumber = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)

@Composable
fun RandoTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}

fun levelColor(level: Level): Color = when (level) {
    Level.OK -> Color(0xFF22C55E)
    Level.INFO -> Color(0xFF3B82F6)
    Level.WARN -> Color(0xFFF59E0B)
    Level.DANGER -> Color(0xFFEF4444)
}
