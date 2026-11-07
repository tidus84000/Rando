package fr.tidus.rando.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import fr.tidus.rando.weather.Level

private val Dark = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF0B2810),
    primaryContainer = Color(0xFF1E4D24),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFFFB74D),
    background = Color(0xFF111813),
    surface = Color(0xFF111813),
    surfaceVariant = Color(0xFF1F2A22),
    surfaceContainer = Color(0xFF19221B),
    surfaceContainerHigh = Color(0xFF212C23),
    onSurfaceVariant = Color(0xFFB9C7BB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B2810),
    secondary = Color(0xFFD84315),
    background = Color(0xFFF6F8F4),
    surface = Color(0xFFF6F8F4),
    surfaceVariant = Color(0xFFE3EAE1),
)

@Composable
fun RandoTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

fun levelColor(level: Level): Color = when (level) {
    Level.OK -> Color(0xFF43A047)
    Level.INFO -> Color(0xFF1E88E5)
    Level.WARN -> Color(0xFFFB8C00)
    Level.DANGER -> Color(0xFFE53935)
}
