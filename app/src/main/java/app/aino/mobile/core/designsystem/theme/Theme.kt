package app.aino.mobile.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

val AinoBlue = Color(0xFF2383E2)
val AinoBlueLight = Color(0xFF529CCA)
val AinoBlueDark = Color(0xFF1A6DBE)
val AinoCyan = Color(0xFF38BDF8)
val AinoSuccess = Color(0xFF4DAA57)
val AinoWarning = Color(0xFFCB912F)
val AinoDanger = Color(0xFFE03E3E)
val AinoDarkBackground = Color(0xFF131314)
val AinoDarkSurface = Color(0xFF161618)
val AinoDarkElevated = Color(0xFF1F1F22)
val AinoDarkBorder = Color.White.copy(alpha = 0.09f)
val AinoText = Color.White.copy(alpha = 0.81f)
val AinoTextSecondary = Color.White.copy(alpha = 0.53f)
val AinoTextMuted = Color.White.copy(alpha = 0.38f)

private val LightColors = lightColorScheme(
    primary = AinoBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEEFF),
    onPrimaryContainer = Color(0xFF073B68),
    secondary = AinoBlueLight,
    tertiary = AinoCyan,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7EEF5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F6F9),
    surfaceContainer = Color(0xFFEDF2F7),
    surfaceContainerHigh = Color(0xFFE6ECF2),
    surfaceContainerHighest = Color(0xFFDFE7EE),
    outline = Color(0xFF75808C),
    outlineVariant = Color(0xFFC5CDD5),
    onBackground = Color(0xD9000000),
    onSurface = Color(0xD9000000),
    onSurfaceVariant = Color(0x8C000000),
    error = AinoDanger,
)

private val DarkColors = darkColorScheme(
    primary = AinoBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF173A5C),
    onPrimaryContainer = Color(0xFFCFE8FF),
    secondary = AinoBlueLight,
    tertiary = AinoCyan,
    background = AinoDarkBackground,
    surface = AinoDarkSurface,
    surfaceVariant = AinoDarkElevated,
    surfaceContainerLowest = Color(0xFF0D0E10),
    surfaceContainerLow = Color(0xFF17191C),
    surfaceContainer = Color(0xFF1C1E22),
    surfaceContainerHigh = Color(0xFF25282D),
    surfaceContainerHighest = Color(0xFF30343A),
    outline = Color(0xFF8B949E),
    outlineVariant = Color(0xFF40464D),
    onBackground = AinoText,
    onSurface = AinoText,
    onSurfaceVariant = AinoTextSecondary,
    error = AinoDanger,
)

private val AinoTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)

private val AinoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun AinoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AinoTypography,
        shapes = AinoShapes,
        content = content,
    )
}
