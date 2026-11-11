package app.aafin.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val BrandPurple = Color(0xFFAA5CC3)
val BrandBlue = Color(0xFF00A4DC)
val BrandDark = Color(0xFF101018)

/** The logo's purple-to-blue sweep, for accents. */
val BrandGradient: Brush get() = Brush.horizontalGradient(listOf(BrandPurple, BrandBlue))

/** Dark-only theme built from the logo palette (#AA5CC3 → #00A4DC on #101018). */
private val AAFinColours = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFF00212E),
    primaryContainer = Color(0xFF004D68),
    onPrimaryContainer = Color(0xFFC4EBFF),
    secondary = BrandPurple,
    onSecondary = Color(0xFF25082F),
    secondaryContainer = Color(0xFF4A2A58),
    onSecondaryContainer = Color(0xFFF3DAFF),
    tertiary = BrandPurple,
    onTertiary = Color(0xFF25082F),
    background = BrandDark,
    onBackground = Color(0xFFECECF4),
    surface = BrandDark,
    onSurface = Color(0xFFECECF4),
    surfaceVariant = Color(0xFF262633),
    onSurfaceVariant = Color(0xFFC2C2D0),
    surfaceContainerLowest = Color(0xFF0B0B11),
    surfaceContainerLow = Color(0xFF15151E),
    surfaceContainer = Color(0xFF1A1A25),
    surfaceContainerHigh = Color(0xFF22222E),
    surfaceContainerHighest = Color(0xFF2B2B39),
    outline = Color(0xFF7C7C8E),
    outlineVariant = Color(0xFF3A3A49),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun AAFinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AAFinColours, content = content)
}
