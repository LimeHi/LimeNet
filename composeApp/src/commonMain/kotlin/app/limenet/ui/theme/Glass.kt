package app.limenet.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * «Жидкое стекло» без блюра: полупрозрачная заливка, блик по верхнему краю и обводка
 * со светлым углом. Всё рисуется градиентами (дёшево для GPU, без снимков фона),
 * поэтому одинаково работает на десктопе и телефоне. Нужен фон с «свечением»
 * ([GlassBackdrop]), иначе стеклу не за чем просвечивать.
 */

private fun Outline.toPath(): Path = Path().apply {
    when (val o = this@toPath) {
        is Outline.Rectangle -> addRect(o.rect)
        is Outline.Rounded -> addRoundRect(o.roundRect)
        is Outline.Generic -> addPath(o.path)
    }
}

/**
 * Заливка/обводка по [Outline]. `DrawScope.drawOutline` в этой версии Compose
 * недоступен (internal), поэтому переводим контур в [Path] сами.
 */
private fun DrawScope.drawGlass(
    outline: Outline,
    brush: Brush,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
) = drawPath(outline.toPath(), brush, alpha, style)

/**
 * Рисует стекло под содержимым.
 *
 * @param accent 0..1 — насколько стекло «залито» акцентом (активная кнопка).
 * @param level 0..1 — общая яркость материала (для плавного появления плашки вкладки).
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(Radii.m),
    accent: Float = 0f,
    level: () -> Float = { 1f },
): Modifier = drawBehind {
    val k = level().coerceIn(0f, 1f)
    if (k <= 0.001f) return@drawBehind
    val outline = shape.createOutline(size, layoutDirection, this)
    val diagonalEnd = Offset(size.width, size.height)

    // тело стекла
    drawGlass(
        outline,
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.13f),
                Color.White.copy(alpha = 0.04f),
                LimeColors.Accent.copy(alpha = 0.05f),
            ),
            start = Offset.Zero,
            end = diagonalEnd,
        ),
        alpha = k * (1f - accent),
    )
    if (accent > 0f) {
        drawGlass(
            outline,
            Brush.verticalGradient(
                listOf(LimeColors.Accent.copy(alpha = 0.96f), LimeColors.Accent.copy(alpha = 0.78f)),
            ),
            alpha = k * accent,
        )
    }

    // блик: верхняя половина, скруглённая вместе с формой
    val shineH = size.height * 0.55f
    clipPath(outline.toPath()) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.16f + 0.14f * accent), Color.Transparent),
                startY = 0f,
                endY = shineH,
            ),
            size = Size(size.width, shineH),
            alpha = k,
        )
    }

    // обводка: светлая сверху-слева, тусклая снизу, лёгкий лайм в дальнем углу
    drawGlass(
        outline,
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f),
                Color.White.copy(alpha = 0.06f),
                LimeColors.Accent.copy(alpha = 0.28f),
            ),
            start = Offset.Zero,
            end = diagonalEnd,
        ),
        alpha = k,
        style = Stroke(1.dp.toPx()),
    )
}

/**
 * Анимация нажатия: упругое «вдавливание» + светлая волна, расходящаяся от точки касания.
 * Заменяет ripple. Сохраняет клавиатуру и доступность (обычный `clickable`).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    shape: Shape = RoundedCornerShape(Radii.m),
    enabled: Boolean = true,
    /** Во сколько раз сжимается элемент под пальцем: 0.94 — кнопки, 0.985 — широкие строки. */
    depth: Float = 0.94f,
    /** Зажатие (долгое нажатие); `null` — не обрабатывается, работает только обычный клик. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) depth else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "press-scale",
    )
    val hold by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.10f else 0f,
        animationSpec = tween(120),
        label = "press-hold",
    )
    val wave = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                origin = interaction.pressPosition
                launch {
                    wave.snapTo(0f)
                    wave.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .drawWithContent {
            drawContent()
            val w = wave.value
            if (w < 1f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.30f * (1f - w)),
                    radius = size.maxDimension * 1.1f * w,
                    center = origin,
                )
            }
            if (hold > 0f) drawRect(Color.White.copy(alpha = hold))
        }
        .then(
            if (onLongClick == null) {
                Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
            } else {
                Modifier.combinedClickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
            },
        )
}

/** Стеклянная кнопка: нажатие + материал. */
fun Modifier.glassButton(
    shape: Shape = RoundedCornerShape(Radii.m),
    accent: Float = 0f,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = pressable(shape, enabled, onClick = onClick).glass(shape, accent)

/**
 * Фон: тёмная основа и несколько размытых лаймовых пятен, сквозь которые «светит» стекло.
 * [shift] — позиция вкладки (0..n): пятна чуть смещаются при перелистывании.
 */
@Composable
fun GlassBackdrop(shift: () -> Float = { 0f }, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(LimeColors.Bg)
            .drawBehind {
                val s = shift()
                val big = size.maxDimension
                glow(Offset(size.width * (0.15f - 0.10f * s), size.height * 0.10f), big * 0.55f, LimeColors.Accent, 0.20f)
                glow(Offset(size.width * (0.95f - 0.14f * s), size.height * 0.55f), big * 0.60f, LimeColors.Rind, 0.38f)
                glow(Offset(size.width * (0.30f + 0.10f * s), size.height * 1.00f), big * 0.45f, LimeColors.Accent, 0.10f)
            },
    )
}

private fun DrawScope.glow(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
