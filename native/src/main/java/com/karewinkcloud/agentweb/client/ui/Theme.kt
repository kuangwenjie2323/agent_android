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

private val LightColors = lightColorScheme(
    primary = Color(0xff4e613c), onPrimary = Color(0xfffffef8),
    primaryContainer = Color(0xffe8eddc), onPrimaryContainer = Color(0xff4e613c),
    background = Color(0xfff6f5f0), onBackground = Color(0xff292e25),
    surface = Color(0xfffdfcf8), onSurface = Color(0xff292e25),
    surfaceVariant = Color(0xffe0e6d5), onSurfaceVariant = Color(0xff565f4d),
    surfaceContainer = Color(0xffedeee6), surfaceContainerLow = Color(0xffecefe5),
    surfaceContainerHigh = Color(0xfffffefb), outline = Color(0xff737d67), outlineVariant = Color(0xffdaddd1),
    error = Color(0xff974c42), errorContainer = Color(0xfff4e6e1), onErrorContainer = Color(0xff974c42),
    secondary = Color(0xff565f4d), onSecondary = Color(0xfffffef8),
    // Selected chips/segments use secondaryContainer; leaving it unset shows Material's default purple.
    secondaryContainer = Color(0xffe0e6d5), onSecondaryContainer = Color(0xff292e25),
    tertiary = Color(0xff3c657a), onTertiary = Color(0xfffffef8),
    tertiaryContainer = Color(0xffe4edf1), onTertiaryContainer = Color(0xff3c657a),
    surfaceTint = Color(0xff4e613c), inversePrimary = Color(0xffc1cd9f),
    surfaceContainerLowest = Color(0xffffffff), surfaceContainerHighest = Color(0xffe6e9df),
    surfaceBright = Color(0xfffdfcf8), surfaceDim = Color(0xffe6e9df), scrim = Color(0xff1c2117),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xffc1cd9f), onPrimary = Color(0xff242c1d),
    primaryContainer = Color(0xff323a2a), onPrimaryContainer = Color(0xffc1cd9f),
    background = Color(0xff1e211d), onBackground = Color(0xffefeee6),
    surface = Color(0xff282c26), onSurface = Color(0xffefeee6),
    surfaceVariant = Color(0xff384031), onSurfaceVariant = Color(0xffc4c8ba),
    surfaceContainer = Color(0xff191c18), surfaceContainerLow = Color(0xff191d18),
    surfaceContainerHigh = Color(0xff2e332b), outline = Color(0xff85917a), outlineVariant = Color(0xff40473b),
    error = Color(0xffefada4), errorContainer = Color(0xff402b29), onErrorContainer = Color(0xffefada4),
    secondary = Color(0xffc4c8ba), onSecondary = Color(0xff242c1d),
    secondaryContainer = Color(0xff384031), onSecondaryContainer = Color(0xffefeee6),
    tertiary = Color(0xffaec9da), onTertiary = Color(0xff1e2a31),
    tertiaryContainer = Color(0xff29343a), onTertiaryContainer = Color(0xffaec9da),
    surfaceTint = Color(0xffc1cd9f), inversePrimary = Color(0xff4e613c),
    surfaceContainerLowest = Color(0xff161915), surfaceContainerHighest = Color(0xff363c32),
    surfaceBright = Color(0xff363c32), surfaceDim = Color(0xff1e211d), scrim = Color(0xff070a06),
)
private val NativeTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp, lineBreak = LineBreak.Paragraph),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
)

@Composable
fun AgentWebTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    CompositionLocalProvider(LocalWorkingAccent provides if (dark) Color(0xffe89a7e) else Color(0xffd97757)) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = NativeTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(24.dp)), content = content)
    }
}
