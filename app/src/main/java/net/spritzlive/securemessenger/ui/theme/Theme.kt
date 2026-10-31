package net.spritzlive.securemessenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Same senior-friendly scaling trick as smsmmsapp: floor the font scale so every `sp`
 * size everywhere (typography, buttons, bubbles, fields) is enlarged in one place.
 */
const val SENIOR_FONT_SCALE = 1.35f

val SecureGreen = Color(0xFF17A673)
val SecureGreenDark = Color(0xFF3FCB97)
val OutgoingBubble = Color(0xFF17A673)
val IncomingBubbleLight = Color(0xFFE9F3EF)
val IncomingBubbleDark = Color(0xFF1C2E28)

private val LightColors = lightColorScheme(
    primary = SecureGreen,
    onPrimary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF11151A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11151A),
    surfaceVariant = IncomingBubbleLight,
    onSurfaceVariant = Color(0xFF3A4450),
)

private val DarkColors = darkColorScheme(
    primary = SecureGreenDark,
    onPrimary = Color.Black,
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF11161D),
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = IncomingBubbleDark,
    onSurfaceVariant = Color(0xFFC3CDD9),
)

@Composable
fun SecureMessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = SENIOR_FONT_SCALE,
    content: @Composable () -> Unit,
) {
    val base = LocalDensity.current
    val enlarged = Density(
        density = base.density,
        fontScale = fontScale,
    )
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
    ) {
        CompositionLocalProvider(LocalDensity provides enlarged) {
            content()
        }
    }
}
