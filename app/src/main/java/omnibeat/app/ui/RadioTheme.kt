package omnibeat.app.ui

import omnibeat.app.model.AccentPalette
import omnibeat.app.model.ThemeMode

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Immutable
data class RadioColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val primary: Color,
    /** 主色上的前景色（主按钮文字、徽标、图标）：按对比度在黑白之间自动选 */
    val onPrimary: Color,
    val danger: Color,
    val text: Color,
    val textMuted: Color,
)

private val DarkDanger = Color(0xFFFF4D5E)
private val LightDanger = Color(0xFFD83A4A)

/** 主色上用的深色前景：#15121C 带一点紫调，比纯黑柔和 */
private val PrimaryDarkContent = Color(0xFF15121C)

/** 白色前景至少要有这个对比度才可用，否则改用深色前景 */
private const val MIN_PRIMARY_CONTRAST = 3f

// 默认配色（紫罗兰）：沿用改动前的 16 个色值，保证升级后外观零变化
private val DarkRadioColors = RadioColors(
    background = Color(0xFF0D0D10),
    surface = Color(0xFF18181D),
    surfaceHigh = Color(0xFF24242B),
    outline = Color(0xFF353540),
    primary = AccentPalette.Violet.seedDarkColor,
    onPrimary = Color.White,
    danger = DarkDanger,
    text = Color(0xFFF4F1FA),
    textMuted = Color(0xFFB7B2C3),
)

private val LightRadioColors = RadioColors(
    background = Color(0xFFF2F0F6),
    surface = Color(0xFFE7E5E9),
    surfaceHigh = Color(0xFFDBD9DD),
    outline = Color(0xFFC6C1CE),
    primary = AccentPalette.Violet.seedLightColor,
    onPrimary = Color.White,
    danger = LightDanger,
    text = Color(0xFF16131D),
    textMuted = Color(0xFF6D667A),
)

private val AccentPalette.seedLightColor: Color
    get() = Color(seedLight)

private val AccentPalette.seedDarkColor: Color
    get() = Color(seedDark)

private val LocalRadioColors = staticCompositionLocalOf { DarkRadioColors }

val RadioBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalRadioColors.current.background

val RadioSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalRadioColors.current.surface

val RadioSurfaceHigh: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalRadioColors.current.surfaceHigh

val RadioOutline: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalRadioColors.current.outline

val RadioPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalRadioColors.current.primary

/** 画在主色容器（主按钮、FAB、徽标、进度）上的前景色 */
val RadioOnPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalRadioColors.current.onPrimary

val RadioDanger: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalRadioColors.current.danger

val RadioText: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalRadioColors.current.text

val RadioTextMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalRadioColors.current.textMuted

@Composable
fun OmniBeatTheme(
    themeMode: ThemeMode = ThemeMode.System,
    accentPalette: AccentPalette = AccentPalette.Default,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = shouldUseDarkTheme(themeMode)
    val radioColors = accentColors(accentPalette, useDarkTheme)
    CompositionLocalProvider(LocalRadioColors provides radioColors) {
        MaterialTheme(
            colorScheme = radioColors.toMaterialColorScheme(useDarkTheme),
            typography = RadioTypography,
            content = content,
        )
    }
}

@Composable
fun shouldUseDarkTheme(themeMode: ThemeMode): Boolean {
    return when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
}

/**
 * 配色 × 明暗 → 完整色阶。派生结果只在第一次用到时计算，之后命中缓存
 * （枚举值固定，总共 7 × 2 种组合）。
 */
private val derivedAccentColors = ConcurrentHashMap<Pair<AccentPalette, Boolean>, RadioColors>()

private fun accentColors(palette: AccentPalette, useDarkTheme: Boolean): RadioColors {
    if (palette == AccentPalette.Default) {
        return if (useDarkTheme) DarkRadioColors else LightRadioColors
    }
    return derivedAccentColors.getOrPut(palette to useDarkTheme) {
        deriveAccentColors(palette, useDarkTheme)
    }
}

/**
 * 以主色的色相为基准、按固定明度阶梯生成中性色：整体色调跟着主色走，
 * 但饱和度压得很低，底色仍然是「带一点点主色味道」的中性灰。
 * 无彩色配色（太极）把饱和度清零，得到纯灰阶梯。
 */
private fun deriveAccentColors(palette: AccentPalette, useDarkTheme: Boolean): RadioColors {
    val primary = if (useDarkTheme) palette.seedDarkColor else palette.seedLightColor
    val hue = primary.toHsl().hue
    val chroma = if (palette.achromatic) 0f else 1f
    fun tone(saturation: Float, lightness: Float) = hslColor(hue, saturation * chroma, lightness)

    return if (useDarkTheme) {
        RadioColors(
            background = tone(0.14f, 0.055f),
            surface = tone(0.14f, 0.10f),
            surfaceHigh = tone(0.16f, 0.155f),
            outline = tone(0.14f, 0.235f),
            primary = primary,
            onPrimary = onPrimaryColor(primary),
            danger = DarkDanger,
            text = tone(0.25f, 0.96f),
            textMuted = tone(0.12f, 0.73f),
        )
    } else {
        RadioColors(
            background = tone(0.35f, 0.965f),
            surface = tone(0.26f, 0.915f),
            surfaceHigh = tone(0.22f, 0.87f),
            outline = tone(0.16f, 0.79f),
            primary = primary,
            onPrimary = onPrimaryColor(primary),
            danger = LightDanger,
            text = tone(0.30f, 0.085f),
            textMuted = tone(0.12f, 0.42f),
        )
    }
}

/** 白字对比度够就用白，不够（琥珀这类亮主色）改用近黑 */
private fun onPrimaryColor(primary: Color): Color {
    val whiteLuminance = Color.White.luminance()
    val primaryLuminance = primary.luminance()
    val lighter = maxOf(whiteLuminance, primaryLuminance)
    val darker = minOf(whiteLuminance, primaryLuminance)
    val contrast = (lighter + 0.05f) / (darker + 0.05f)
    return if (contrast >= MIN_PRIMARY_CONTRAST) Color.White else PrimaryDarkContent
}

private data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

private fun Color.toHsl(): Hsl {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta <= 0.0001f) return Hsl(0f, 0f, lightness)
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    // 先判 g/b：纯黄（r=g）、纯青（g=b）这类相等通道能落回正确色相
    val hue = when {
        max == green -> 60f * (((blue - red) / delta) + 2f)
        max == blue -> 60f * (((red - green) / delta) + 4f)
        else -> 60f * (((green - blue) / delta) % 6f)
    }
    return Hsl((hue + 360f) % 360f, saturation, lightness)
}

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val normalizedHue = ((hue % 360f) + 360f) % 360f
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val second = chroma * (1f - abs((normalizedHue / 60f) % 2f - 1f))
    val match = lightness - chroma / 2f
    val rgb = when {
        normalizedHue < 60f -> floatArrayOf(chroma, second, 0f)
        normalizedHue < 120f -> floatArrayOf(second, chroma, 0f)
        normalizedHue < 180f -> floatArrayOf(0f, chroma, second)
        normalizedHue < 240f -> floatArrayOf(0f, second, chroma)
        normalizedHue < 300f -> floatArrayOf(second, 0f, chroma)
        else -> floatArrayOf(chroma, 0f, second)
    }
    return Color(rgb[0] + match, rgb[1] + match, rgb[2] + match)
}

private fun RadioColors.toMaterialColorScheme(useDarkTheme: Boolean): ColorScheme {
    return if (useDarkTheme) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            secondary = primary,
            onSecondary = onPrimary,
            background = background,
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = surfaceHigh,
            onSurfaceVariant = textMuted,
            outline = outline,
            error = danger,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            secondary = primary,
            onSecondary = onPrimary,
            background = background,
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = surfaceHigh,
            onSurfaceVariant = textMuted,
            outline = outline,
            error = danger,
            onError = Color.White,
        )
    }
}
