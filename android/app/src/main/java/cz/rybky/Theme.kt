package cz.rybky

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Zvýraznění pro shodu v kameře a cizí samolepky na mapě – v obou režimech stejné. */
val Amber = Color(0xFFFBBF24)

private val Light = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFFAFE),
    onPrimaryContainer = Color(0xFF083344),
    secondaryContainer = Color(0xFFFDE68A),
    onSecondaryContainer = Color(0xFF451A03),
    background = Color(0xFFF6F9FB),
    surface = Color(0xFFF6F9FB),
    surfaceContainerLow = Color(0xFFEFF5F8),
    surfaceContainer = Color(0xFFE7F0F4),
    surfaceContainerHigh = Color(0xFFDDE9EF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF67E8F9),
    onPrimary = Color(0xFF083344),
    primaryContainer = Color(0xFF155E75),
    onPrimaryContainer = Color(0xFFCFFAFE),
    secondaryContainer = Color(0xFF164E63),
    onSecondaryContainer = Color(0xFFCFFAFE),
    background = Color(0xFF0A1220),
    surface = Color(0xFF0A1220),
    surfaceContainerLow = Color(0xFF0F1A2B),
    surfaceContainer = Color(0xFF132136),
    surfaceContainerHigh = Color(0xFF1A2B44),
)

@Composable
fun RybkyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
