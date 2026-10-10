package app.wakelink.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// "Dawn" palette: night-sky navy for structure, sunrise amber for the one
// action that matters (waking someone). Neutrals lean slightly blue so
// they read as part of the night sky rather than as default grey.

val Night = Color(0xFF1F2B4D)
val NightDeep = Color(0xFF0E1324)
val Sunrise = Color(0xFFE8892E)
val SunriseSoft = Color(0xFFFFB45E)
val Dawn = Color(0xFFF7C78A)

internal val LightColors = lightColorScheme(
    primary = Night,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2F5),
    onPrimaryContainer = Night,
    secondary = Sunrise,
    onSecondary = Color(0xFF2A1700),
    secondaryContainer = Color(0xFFFFE3C4),
    onSecondaryContainer = Color(0xFF4A2800),
    tertiary = Sunrise,
    onTertiary = Color(0xFF2A1700),
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF151A2B),
    surface = Color.White,
    onSurface = Color(0xFF151A2B),
    surfaceVariant = Color(0xFFE9ECF4),
    onSurfaceVariant = Color(0xFF545B70),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFF0F2F8),
    surfaceContainerHigh = Color(0xFFE9ECF4),
    surfaceContainerHighest = Color(0xFFE2E6F0),
    outline = Color(0xFFC3C8D6),
    outlineVariant = Color(0xFFDDE1EB),
    error = Color(0xFFC4384A),
    onError = Color.White,
    errorContainer = Color(0xFFFCE4E6),
    onErrorContainer = Color(0xFF7A1424),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF15204A),
    primaryContainer = Color(0xFF2B3763),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = SunriseSoft,
    onSecondary = Color(0xFF2A1700),
    secondaryContainer = Color(0xFF5A3510),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = SunriseSoft,
    onTertiary = Color(0xFF2A1700),
    background = NightDeep,
    onBackground = Color(0xFFE4E7F2),
    surface = Color(0xFF161C31),
    onSurface = Color(0xFFE4E7F2),
    surfaceVariant = Color(0xFF222A44),
    onSurfaceVariant = Color(0xFFA9B0C6),
    surfaceContainerLowest = Color(0xFF0B1020),
    surfaceContainerLow = Color(0xFF131930),
    surfaceContainer = Color(0xFF161C31),
    surfaceContainerHigh = Color(0xFF1D243C),
    surfaceContainerHighest = Color(0xFF252D48),
    outline = Color(0xFF4A5372),
    outlineVariant = Color(0xFF2D3550),
    error = Color(0xFFFF8A95),
    onError = Color(0xFF4A0711),
    errorContainer = Color(0xFF5C1A25),
    onErrorContainer = Color(0xFFFFD9DC),
)

/** Status colors kept apart from the brand palette so "success" never looks like "the wake button". */
@Immutable
data class StatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightStatusColors = StatusColors(
    success = Color(0xFF1E8A5A),
    successContainer = Color(0xFFDDF4E8),
    onSuccessContainer = Color(0xFF0C4A2E),
    warning = Color(0xFFB7791F),
    warningContainer = Color(0xFFFFF0D6),
    onWarningContainer = Color(0xFF5C3B00),
)

internal val DarkStatusColors = StatusColors(
    success = Color(0xFF6FD6A3),
    successContainer = Color(0xFF123A28),
    onSuccessContainer = Color(0xFFC6F2DA),
    warning = Color(0xFFF2C46B),
    warningContainer = Color(0xFF45320C),
    onWarningContainer = Color(0xFFFFE6B5),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** Avatar fills: muted night/dawn hues, picked per name so a contact keeps its color. */
internal val AvatarPalette = listOf(
    Color(0xFF3E5BA9), Color(0xFFD9822B), Color(0xFF2F8F83),
    Color(0xFF8A5CB8), Color(0xFFC0566B), Color(0xFF4F7A3A),
)
