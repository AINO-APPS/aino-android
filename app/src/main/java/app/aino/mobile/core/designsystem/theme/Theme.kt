package app.aino.mobile.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
val AinoDarkSurface = Color(0xFF1B1B1C)
val AinoDarkElevated = Color(0xFF202021)
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
    background = Color(0xFFFBFBFA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF5F4F1),
    outline = Color(0x1F37352F),
    onBackground = Color(0xFF37352F),
    onSurface = Color(0xFF37352F),
    onSurfaceVariant = Color(0xA637352F),
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
    outline = AinoDarkBorder,
    onBackground = AinoText,
    onSurface = AinoText,
    onSurfaceVariant = AinoTextSecondary,
    error = AinoDanger,
)

private val AinoTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.7).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.35).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.3.sp),
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
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AinoTypography,
        shapes = AinoShapes,
        content = content,
    )
}
