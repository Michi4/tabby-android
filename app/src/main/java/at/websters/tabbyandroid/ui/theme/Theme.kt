package at.websters.tabbyandroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFFF0ABFC),
    onSecondary = Color(0xFF4A044E),
    tertiary = Color(0xFFFCD34D),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF11161D),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B2230),
    onSurfaceVariant = Color(0xFF9FB0C3),
    surfaceContainerLow = Color(0xFF0E1319),
    surfaceContainer = Color(0xFF151B25),
    surfaceContainerHigh = Color(0xFF1E2634),
    outline = Color(0xFF2A3444),
    outlineVariant = Color(0xFF1E2634),
    error = Color(0xFFF87171),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = Color(0xFFA21CAF),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8EEF5),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLow = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFFE8EEF5),
    surfaceContainerHigh = Color(0xFFDCE5F0),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
)

private val TabbyShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun TabbyTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = TabbyShapes,
        content = content,
    )
}

/** Connection-state dot colors (Termius-like presence). */
val StatusConnected = Color(0xFF4ADE80)
val StatusConnecting = Color(0xFFFBBF24)
val StatusError = Color(0xFFF87171)
val StatusIdle = Color(0xFF64748B)

@Composable
fun statusColor(state: at.websters.tabbyandroid.data.ssh.SshState): Color = when (state) {
    at.websters.tabbyandroid.data.ssh.SshState.CONNECTED -> StatusConnected
    at.websters.tabbyandroid.data.ssh.SshState.CONNECTING -> StatusConnecting
    at.websters.tabbyandroid.data.ssh.SshState.ERROR -> StatusError
    at.websters.tabbyandroid.data.ssh.SshState.DISCONNECTED -> StatusIdle
}

/** xterm-ish palette for TerminalBuffer fg/bg indices 0..7 */
fun termColor(index: Int, dark: Boolean = true): Color {
    val darkPalette = listOf(
        Color(0xFF1E1E1E), Color(0xFFF87171), Color(0xFF4ADE80), Color(0xFFFCD34D),
        Color(0xFF7DD3FC), Color(0xFFF0ABFC), Color(0xFF67E8F9), Color(0xFFE6EDF3),
    )
    val lightPalette = listOf(
        Color(0xFFFFFFFF), Color(0xFFDC2626), Color(0xFF16A34A), Color(0xFFB45309),
        Color(0xFF0369A1), Color(0xFFA21CAF), Color(0xFF0E7490), Color(0xFF0F172A),
    )
    val p = if (dark) darkPalette else lightPalette
    return p[index.coerceIn(0, 7)]
}

/** Parses '#rrggbb' / '#aarrggbb' profile colors, null when unparseable. */
fun parseHexColor(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    return try {
        val hex = raw.trim().removePrefix("#")
        val full = when (hex.length) {
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
        Color(full.toULong(16))
    } catch (_: Exception) {
        null
    }
}
