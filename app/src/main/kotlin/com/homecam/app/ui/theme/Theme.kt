package com.homecam.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.homecam.app.R

/**
 * The one place the design system's tokens become Compose values (its "framework seam").
 * Everything a screen draws comes from here or from `ui/kit`; the rules those tokens serve
 * — density, disclosure, iconography, copy — are in `../ethan_design_system/readme.md`, and
 * a screen designed from this file alone will look right and behave wrong.
 */
data class EthanColors(
    val bgApp: Color,
    val bgSunken: Color,
    val surfaceCard: Color,
    val surfaceRaised: Color,
    val surfaceActive: Color,
    val surfaceAccentSoft: Color,
    val textHeading: Color,
    val textBody: Color,
    val textMuted: Color,
    val textFaint: Color,
    val textAccent: Color,
    val textDanger: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val inkRule: Color,
    val inkWash: Color,
    val accent: Color,
    val accentQuiet: Color,
    val accentRing: Color,
    val onAccent: Color,
    val onAccentSoft: Color,
    val knob: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onDanger: Color,
    val success: Color,
    val successSoft: Color,
    val successText: Color,
    val warning: Color,
    val warningSoft: Color,
    val warningText: Color,
    val info: Color,
    val infoSoft: Color,
    val infoText: Color,
    val tintGrey: Color,
    val tintGreySoft: Color,
    val scrim: Color,
)

private val Light = EthanColors(
    bgApp = Color(0xFFF8F6F1),
    bgSunken = Color(0xFFF1EDE5),
    surfaceCard = Color(0xFFFDFCF9),
    surfaceRaised = Color(0xFFFDFCF9),
    surfaceActive = Color(0xFFE8E2D8),
    surfaceAccentSoft = Color(0xFFFDF5EE),
    textHeading = Color(0xFF211D18),
    textBody = Color(0xFF3E3830),
    textMuted = Color(0xFF574F45),
    textFaint = Color(0xFF70685B),
    textAccent = Color(0xFF944A17),
    textDanger = Color(0xFF3A16F5),
    borderSubtle = Color(0xFFE8E2D8),
    borderDefault = Color(0xFFD8D1C5),
    borderStrong = Color(0xFFB5AC9C),
    inkRule = Color(0x24211D18),
    inkWash = Color(0x0F211D18),
    accent = Color(0xFFDD7A32),
    accentQuiet = Color(0xFFFBE8D6),
    accentRing = Color(0xFFF4D2AC),
    onAccent = Color(0xFFFFF8F1),
    onAccentSoft = Color(0xFF944A17),
    knob = Color(0xFFFDFCF9),
    danger = Color(0xFF5326FF),
    dangerSoft = Color(0xFFE8E5FF),
    onDanger = Color(0xFFFDFCFF),
    success = Color(0xFF0E8A73),
    successSoft = Color(0xFFDCF0EB),
    successText = Color(0xFF0B6E5D),
    warning = Color(0xFFF0C41A),
    warningSoft = Color(0xFFFDF4CB),
    warningText = Color(0xFF8A6403),
    info = Color(0xFF1A7296),
    infoSoft = Color(0xFFDCEDF4),
    infoText = Color(0xFF145A76),
    tintGrey = Color(0xFF574F45),
    tintGreySoft = Color(0xFFF2EFE8),
    scrim = Color(0x52211D18),
)

private val Dark = EthanColors(
    bgApp = Color(0xFF15130E),
    bgSunken = Color(0xFF0E0C09),
    surfaceCard = Color(0xFF2B271F),
    surfaceRaised = Color(0xFF312D24),
    surfaceActive = Color(0xFF3F3A2F),
    surfaceAccentSoft = Color(0xFF3A3029),
    textHeading = Color(0xFFF7F4ED),
    textBody = Color(0xFFD6D0C5),
    textMuted = Color(0xFFB4AEA2),
    textFaint = Color(0xFFA59E90),
    textAccent = Color(0xFFF6C08B),
    textDanger = Color(0xFFBCA9FF),
    borderSubtle = Color(0xFF3A352C),
    borderDefault = Color(0xFF504A3F),
    borderStrong = Color(0xFF6B6457),
    inkRule = Color(0x24F7F4ED),
    inkWash = Color(0x0FF7F4ED),
    accent = Color(0xFFDD7A32),
    accentQuiet = Color(0xFF403529),
    accentRing = Color(0xFF54453A),
    onAccent = Color(0xFFFFF8F1),
    onAccentSoft = Color(0xFFEAB988),
    knob = Color(0xFFFFF8F1),
    danger = Color(0xFF6A3DFF),
    dangerSoft = Color(0xFF251852),
    onDanger = Color(0xFFFFFFFF),
    success = Color(0xFF33B98A),
    successSoft = Color(0xFF123A2C),
    successText = Color(0xFF8FE9C4),
    warning = Color(0xFFE5AF2B),
    warningSoft = Color(0xFF3E2D07),
    warningText = Color(0xFFFFD98A),
    info = Color(0xFF3FA6D2),
    infoSoft = Color(0xFF0E3346),
    infoText = Color(0xFF8FD3F0),
    tintGrey = Color(0xFFB5AEA3),
    tintGreySoft = Color(0xFF353330),
    scrim = Color(0xA8080705),
)

private fun variable(resource: Int, weight: Int) = Font(
    resource,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Serif = FontFamily(
    variable(R.font.piazzolla, 400), variable(R.font.piazzolla, 500), variable(R.font.piazzolla, 600),
)
private val Sans = FontFamily(
    variable(R.font.instrument_sans, 400), variable(R.font.instrument_sans, 500), variable(R.font.instrument_sans, 600),
)
private val Mono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.W400),
    Font(R.font.ibm_plex_mono_medium, FontWeight.W500),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.W600),
)

/**
 * The system's ramp, one step up for a phone held at arm's length: its 14px body is for a
 * desktop pane. Serif for headings, sans for controls and prose, mono for anything read as
 * data — times, sizes, counts, addresses, fingerprints.
 */
class EthanType {
    val display = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W600, fontSize = 32.sp, lineHeight = 35.sp, letterSpacing = (-0.015).em)
    val title1 = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W600, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.012).em)
    val title2 = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W600, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.009).em)
    val title3 = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W600, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.006).em)
    val body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.W500)
    val bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 16.sp)
    val label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 15.sp, lineHeight = 20.sp)
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp)
    val monoSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 16.sp)
    val monoLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.W500, fontSize = 22.sp, lineHeight = 28.sp)
    val micro = TextStyle(fontFamily = Mono, fontWeight = FontWeight.W500, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.075.em)
}

object EthanRadius {
    val xs = 3.dp
    val row = 5.dp
    val control = 7.dp
    val card = 10.dp
    val sheet = 20.dp
}

private val LocalEthanColors = staticCompositionLocalOf { Light }
private val Type = EthanType()

object Ethan {
    val colors: EthanColors
        @Composable @ReadOnlyComposable get() = LocalEthanColors.current
    val type: EthanType get() = Type
}

private fun EthanColors.material(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = accentQuiet, onPrimaryContainer = onAccentSoft,
        secondary = accent, onSecondary = onAccent,
        secondaryContainer = accentQuiet, onSecondaryContainer = onAccentSoft,
        tertiary = info, onTertiary = onAccent,
        background = bgApp, onBackground = textBody,
        surface = bgApp, onSurface = textBody,
        surfaceVariant = bgSunken, onSurfaceVariant = textMuted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = surfaceCard, surfaceContainerLow = surfaceCard,
        surfaceContainer = surfaceRaised, surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceRaised,
        inverseSurface = textHeading, inverseOnSurface = bgApp,
        outline = borderDefault, outlineVariant = borderSubtle,
        error = danger, onError = onDanger, errorContainer = dangerSoft, onErrorContainer = textDanger,
        scrim = scrim,
    )
}

private val MaterialType = Typography(
    displaySmall = Type.display, headlineLarge = Type.display, headlineMedium = Type.title1,
    headlineSmall = Type.title1, titleLarge = Type.title2, titleMedium = Type.title3,
    titleSmall = Type.bodyStrong, bodyLarge = Type.body, bodyMedium = Type.body,
    bodySmall = Type.bodySmall, labelLarge = Type.label, labelMedium = Type.bodySmall,
    labelSmall = Type.caption,
)

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(EthanRadius.row),
    small = RoundedCornerShape(EthanRadius.control),
    medium = RoundedCornerShape(EthanRadius.card),
    large = RoundedCornerShape(EthanRadius.card),
    extraLarge = RoundedCornerShape(EthanRadius.sheet),
)

@Composable
fun HomecamTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) Dark else Light
    CompositionLocalProvider(LocalEthanColors provides colors) {
        MaterialTheme(
            colorScheme = colors.material(dark),
            typography = MaterialType,
            shapes = MaterialShapes,
            content = content,
        )
    }
}
