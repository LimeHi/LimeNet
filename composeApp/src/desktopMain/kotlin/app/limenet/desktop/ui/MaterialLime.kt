package app.limenet.desktop.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import app.limenet.ui.theme.LimeFonts
import app.limenet.ui.theme.Radii

/** Полностью заменяет шрифт Material (Roboto/системный) на Manrope из ресурсов. */
internal fun limeTypography(fonts: LimeFonts): Typography {
    val base = Typography()
    fun TextStyle.sans() = copy(fontFamily = fonts.sans)
    return Typography(
        displayLarge = base.displayLarge.sans(),
        displayMedium = base.displayMedium.sans(),
        displaySmall = base.displaySmall.sans(),
        headlineLarge = base.headlineLarge.sans(),
        headlineMedium = base.headlineMedium.sans(),
        headlineSmall = base.headlineSmall.sans(),
        titleLarge = base.titleLarge.sans(),
        titleMedium = base.titleMedium.sans(),
        titleSmall = base.titleSmall.sans(),
        bodyLarge = base.bodyLarge.sans(),
        bodyMedium = base.bodyMedium.sans(),
        bodySmall = base.bodySmall.sans(),
        labelLarge = base.labelLarge.sans(),
        labelMedium = base.labelMedium.sans(),
        labelSmall = base.labelSmall.sans(),
    )
}

/** Радиусы только 4 / 8 / 12. */
internal val limeShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.s),
    small = RoundedCornerShape(Radii.m),
    medium = RoundedCornerShape(Radii.l),
    large = RoundedCornerShape(Radii.l),
    extraLarge = RoundedCornerShape(Radii.l),
)
