package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

private val LocalWorkingAccent = staticCompositionLocalOf { Color(0xffd97757) }
val workingAccent: Color @Composable get() = LocalWorkingAccent.current

// Apple Music-like palette: plain white (black) canvas, grey secondary text, hairline
// separators, grouped greys for cards, and the music-app red as the single accent.
private val LightColors = lightColorScheme(
    primary = Color(0xfffa2d48), onPrimary = Color(0xffffffff),
    primaryContainer = Color(0xffffe4e8), onPrimaryContainer = Color(0xffb3102b),
    background = Color(0xffffffff), onBackground = Color(0xff000000),
    surface = Color(0xffffffff), onSurface = Color(0xff000000),
    surfaceVariant = Color(0xfff2f2f7), onSurfaceVariant = Color(0xff6c6c70),
    surfaceContainerLowest = Color(0xffffffff), surfaceContainerLow = Color(0xfff7f7fa),
    surfaceContainer = Color(0xfff2f2f7), surfaceContainerHigh = Color(0xffefeff4), surfaceContainerHighest = Color(0xffe5e5ea),
    surfaceBright = Color(0xffffffff), surfaceDim = Color(0xffe5e5ea),
    outline = Color(0xff8e8e93), outlineVariant = Color(0xffe3e3e8),
    error = Color(0xffff3b30), onError = Color(0xffffffff), errorContainer = Color(0xffffe5e3), onErrorContainer = Color(0xffc4170c),
    secondary = Color(0xff6c6c70), onSecondary = Color(0xffffffff),
    // Selected chips/segments use secondaryContainer.
    secondaryContainer = Color(0xffffe4e8), onSecondaryContainer = Color(0xffb3102b),
    tertiary = Color(0xff007aff), onTertiary = Color(0xffffffff),
    tertiaryContainer = Color(0xffe3efff), onTertiaryContainer = Color(0xff0056b3),
    surfaceTint = Color(0x00000000), inversePrimary = Color(0xffff6b7f), scrim = Color(0xff000000),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xfffc3c44), onPrimary = Color(0xffffffff),
    primaryContainer = Color(0xff4a1218), onPrimaryContainer = Color(0xffffb3ba),
    background = Color(0xff000000), onBackground = Color(0xffffffff),
    surface = Color(0xff000000), onSurface = Color(0xffffffff),
    surfaceVariant = Color(0xff1c1c1e), onSurfaceVariant = Color(0xff98989f),
    surfaceContainerLowest = Color(0xff000000), surfaceContainerLow = Color(0xff111113),
    surfaceContainer = Color(0xff1c1c1e), surfaceContainerHigh = Color(0xff2c2c2e), surfaceContainerHighest = Color(0xff3a3a3c),
    surfaceBright = Color(0xff2c2c2e), surfaceDim = Color(0xff000000),
    outline = Color(0xff8e8e93), outlineVariant = Color(0xff2c2c2e),
    error = Color(0xffff453a), onError = Color(0xffffffff), errorContainer = Color(0xff3d1411), onErrorContainer = Color(0xffffb4ab),
    secondary = Color(0xff98989f), onSecondary = Color(0xff000000),
    secondaryContainer = Color(0xff4a1218), onSecondaryContainer = Color(0xffffb3ba),
    tertiary = Color(0xff0a84ff), onTertiary = Color(0xffffffff),
    tertiaryContainer = Color(0xff0b2a4a), onTertiaryContainer = Color(0xffa8cfff),
    surfaceTint = Color(0x00000000), inversePrimary = Color(0xfffa2d48), scrim = Color(0xff000000),
)
private val NativeTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp, lineBreak = LineBreak.Paragraph),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp),
)

@Composable
fun AgentWebTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    CompositionLocalProvider(LocalWorkingAccent provides if (dark) Color(0xffe89a7e) else Color(0xffd97757)) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = NativeTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(20.dp)), content = content)
    }
}
