@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package jp.studycopi

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Bundle Japanese glyphs so an English emulator cannot choose Chinese CJK forms.
val JapaneseFont = FontFamily(*listOf(400, 500, 600, 700).map { weight ->
    Font(R.font.noto_sans_jp, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
}.toTypedArray())
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = JapaneseFont, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp,
    letterSpacing = 0.sp, localeList = LocaleList("ja-JP"))
private val StudyTypography = Typography(
    displayLarge = type(56, 64, FontWeight.SemiBold), displayMedium = type(44, 54, FontWeight.SemiBold), displaySmall = type(36, 46, FontWeight.SemiBold),
    headlineLarge = type(30, 40, FontWeight.Bold), headlineMedium = type(26, 36, FontWeight.Bold), headlineSmall = type(24, 34, FontWeight.Bold),
    titleLarge = type(20, 30, FontWeight.SemiBold), titleMedium = type(16, 25, FontWeight.SemiBold), titleSmall = type(14, 22, FontWeight.SemiBold),
    bodyLarge = type(16, 26), bodyMedium = type(14, 23), bodySmall = type(12, 20),
    labelLarge = type(14, 22, FontWeight.Medium), labelMedium = type(12, 20, FontWeight.Medium), labelSmall = type(11, 18, FontWeight.Medium))
private val LightColors = lightColorScheme(
    primary = Color(0xFF245D67), onPrimary = Color.White, primaryContainer = Color(0xFFDCECEE), onPrimaryContainer = Color(0xFF163F47),
    secondary = Color(0xFF52646A), secondaryContainer = Color(0xFFEBF0F1), onSecondaryContainer = Color(0xFF263C42),
    tertiary = Color(0xFF70613C), background = Color(0xFFF5F7F7), surface = Color.White,
    surfaceContainer = Color(0xFFEDF1F1), surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFE7EDED),
    surfaceContainerHighest = Color(0xFFE0E8E8), onBackground = Color(0xFF18272B),
    inverseSurface = Color(0xFF233338), inverseOnSurface = Color(0xFFEDF2F2), inversePrimary = Color(0xFF9BCDD3),
    onSurface = Color(0xFF18272B), onSurfaceVariant = Color(0xFF5D6B70), surfaceVariant = Color(0xFFEDF1F1),
    outline = Color(0xFFADBABB), outlineVariant = Color(0xFFDCE3E4))
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BCDD3), onPrimary = Color(0xFF10383F), primaryContainer = Color(0xFF25484F), onPrimaryContainer = Color(0xFFD2EEF1),
    secondary = Color(0xFFB5C9CE), secondaryContainer = Color(0xFF29383D), onSecondaryContainer = Color(0xFFD5E5E9),
    tertiary = Color(0xFFD5C39A), background = Color(0xFF10181B), surface = Color(0xFF192428),
    surfaceContainer = Color(0xFF202D32), surfaceContainerLow = Color(0xFF192428), surfaceContainerHigh = Color(0xFF29383D),
    surfaceContainerHighest = Color(0xFF304047), onBackground = Color(0xFFE4ECEE),
    inverseSurface = Color(0xFFE4ECEE), inverseOnSurface = Color(0xFF18272B), inversePrimary = Color(0xFF245D67),
    onSurface = Color(0xFFE4ECEE), onSurfaceVariant = Color(0xFFABBDC3), surfaceVariant = Color(0xFF202D32),
    outline = Color(0xFF65797F), outlineVariant = Color(0xFF35464C))

@Composable fun StudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = StudyTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}
