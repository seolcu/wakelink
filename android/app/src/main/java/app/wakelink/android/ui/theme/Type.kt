package app.wakelink.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wakelink.android.R

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = -0.01) = TextStyle(
    fontFamily = Pretendard,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    // Hangul reads denser than Latin; a hair of negative tracking keeps
    // large Korean headings from looking loose.
    letterSpacing = tracking.em,
)

internal val WakeTypography = Typography(
    displayLarge = style(56, 64, FontWeight.Bold, -0.02),
    displayMedium = style(44, 52, FontWeight.Bold, -0.02),
    displaySmall = style(36, 44, FontWeight.Bold, -0.02),
    headlineLarge = style(30, 38, FontWeight.Bold),
    headlineMedium = style(26, 34, FontWeight.Bold),
    headlineSmall = style(22, 30, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(17, 24, FontWeight.SemiBold),
    titleSmall = style(15, 22, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal, 0.0),
    bodyMedium = style(15, 22, FontWeight.Normal, 0.0),
    bodySmall = style(13, 19, FontWeight.Normal, 0.0),
    labelLarge = style(15, 20, FontWeight.SemiBold, 0.0),
    labelMedium = style(13, 18, FontWeight.Medium, 0.01),
    labelSmall = style(12, 16, FontWeight.Medium, 0.02),
)
