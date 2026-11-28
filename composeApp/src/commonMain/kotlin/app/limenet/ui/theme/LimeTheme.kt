package app.limenet.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.limenet.resources.Res
import app.limenet.resources.jetbrains_mono_medium
import app.limenet.resources.jetbrains_mono_regular
import app.limenet.resources.manrope_regular
import app.limenet.resources.manrope_semibold
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font

/** Палитра из DESIGN.md. Один акцент, красный только для ошибок. */
@Immutable
object LimeColors {
    val Bg = Color(0xFF0B0E13)
    val Surface = Color(0xFF11161D)
    val Line = Color(0xFF232A35)
    val Text = Color(0xFFE7ECF3)
    val TextSecondary = Color(0xFF8B95A5)
    val Accent = Color(0xFFA3E635)
    val Rind = Color(0xFF4D7C0F)
    val Error = Color(0xFFF87171)
}

/** Шкала отступов: только эти значения. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Радиусы: только эти значения. */
object Radii {
    val s = 4.dp
    val m = 8.dp
    val l = 12.dp
}

@Immutable
class LimeFonts(
    /** Манроп: весь текст. */
    val sans: FontFamily,
    /** JetBrains Mono: цифры и коды. */
    val mono: FontFamily,
)

val LocalLimeFonts = compositionLocalOf<LimeFonts> {
    LimeFonts(FontFamily.SansSerif, FontFamily.Monospace)
}

object LimeTheme {
    val colors: LimeColors get() = LimeColors
    val fonts: LimeFonts
        @Composable @ReadOnlyComposable get() = LocalLimeFonts.current
}

@OptIn(ExperimentalResourceApi::class)
@Composable
fun rememberLimeFonts(): LimeFonts {
    val manropeRegular = Font(Res.font.manrope_regular, FontWeight.Normal)
    val manropeSemi = Font(Res.font.manrope_semibold, FontWeight.SemiBold)
    val monoRegular = Font(Res.font.jetbrains_mono_regular, FontWeight.Normal)
    val monoMedium = Font(Res.font.jetbrains_mono_medium, FontWeight.Medium)
    return remember(manropeRegular, manropeSemi, monoRegular, monoMedium) {
        LimeFonts(
            // Manrope отдаём без Bold: ближайший к 700 — SemiBold, без синтетического жирного.
            sans = FontFamily(manropeRegular, manropeSemi),
            mono = FontFamily(monoRegular, monoMedium),
        )
    }
}

/** Базовые стили текста (не Material), используются в новых компонентах. */
object LimeText {
    fun body(fonts: LimeFonts) = TextStyle(
        fontFamily = fonts.sans, fontSize = 13.sp, color = LimeColors.Text,
    )

    fun caption(fonts: LimeFonts) = TextStyle(
        fontFamily = fonts.sans, fontSize = 11.sp, color = LimeColors.TextSecondary,
    )

    fun digits(fonts: LimeFonts) = TextStyle(
        fontFamily = fonts.mono, fontWeight = FontWeight.Medium, fontSize = 12.sp,
        color = LimeColors.Text,
    )
}

/** Подаёт шрифты вниз по дереву. Material-обёртка — в desktop-слое (там лежит material3). */
@Composable
fun LimeTheme(content: @Composable () -> Unit) {
    val fonts = rememberLimeFonts()
    CompositionLocalProvider(LocalLimeFonts provides fonts, content = content)
}
