package app.limenet.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.limenet.core.model.SubscriptionInfo
import app.limenet.core.model.TestMethod
import app.limenet.desktop.Controller
import app.limenet.ui.theme.LimeColors
import app.limenet.ui.theme.LimeTheme
import app.limenet.ui.theme.Radii
import app.limenet.ui.theme.Spacing
import app.limenet.ui.theme.glass
import app.limenet.ui.theme.pressable
import kotlin.math.cos
import kotlin.math.sin

/*
 * Проверка серверов, обновление подписок и всплывающие сообщения.
 *
 * Главная идея: пока что-то делается в фоне, интерфейс всегда это показывает —
 * вращающееся кольцо, бегущий блик, счётчик «5/12» и живой прогресс. Приложение
 * не должно выглядеть зависшим, даже если URL-тест идёт десятки секунд.
 */

private val Accent = LimeColors.Accent
private val Muted = LimeColors.TextSecondary
private val TextMain = LimeColors.Text
private val Danger = LimeColors.Error

// ------------------------------------------------------------- примитивы анимации

/** Вращающееся кольцо: дуга бежит по тусклой дорожке. */
@Composable
internal fun SpinnerRing(
    diameter: Dp = 12.dp,
    color: Color = Accent,
    stroke: Dp = 1.5.dp,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spinner-angle",
    )
    Canvas(Modifier.size(diameter)) {
        val w = stroke.toPx()
        val box = Size(this.size.width - w, this.size.height - w)
        val topLeft = Offset(w / 2f, w / 2f)
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = Stroke(w),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = topLeft,
            size = box,
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

/** Три точки по очереди загораются: «в очереди на проверку». */
@Composable
internal fun PendingDots(color: Color = Muted) {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "dots-phase",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val distance = (phase - index + 3f) % 3f
            val light = (1f - distance / 1.5f).coerceIn(0f, 1f)
            Box(
                Modifier.size(3.dp).clip(CircleShape).background(color).alpha(0.25f + 0.75f * light),
            )
        }
    }
}

/** Мягкий блик, бегущий слева направо поверх элемента. Только пока идёт проверка. */
internal fun Modifier.scanSweep(
    color: Color = Accent,
    strength: Float = 0.16f,
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "sweep")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "sweep-phase",
    )
    drawWithContent {
        drawContent()
        val band = size.width * 0.38f
        val start = -band + (size.width + band) * phase
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, color.copy(alpha = strength), Color.Transparent),
                startX = start,
                endX = start + band,
            ),
        )
    }
}

/** Галочка (успех, акцент) или крестик (ошибка) — рисуется «пером» за ~300 мс. */
@Composable
internal fun StatusGlyph(ok: Boolean, diameter: Dp = 14.dp, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(ok) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }
    val color = if (ok) Accent else Danger
    Canvas(modifier.size(diameter)) {
        val s = this.size.width
        val w = 1.75.dp.toPx()
        val t = progress.value
        if (ok) {
            val p1 = Offset(s * 0.16f, s * 0.54f)
            val p2 = Offset(s * 0.42f, s * 0.78f)
            val p3 = Offset(s * 0.86f, s * 0.24f)
            val first = (t / 0.35f).coerceIn(0f, 1f)
            val second = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
            drawLine(color, p1, lerpOffset(p1, p2, first), w, StrokeCap.Round)
            if (second > 0f) drawLine(color, p2, lerpOffset(p2, p3, second), w, StrokeCap.Round)
        } else {
            val a1 = Offset(s * 0.24f, s * 0.24f)
            val a2 = Offset(s * 0.76f, s * 0.76f)
            val b1 = Offset(s * 0.76f, s * 0.24f)
            val b2 = Offset(s * 0.24f, s * 0.76f)
            val first = (t / 0.5f).coerceIn(0f, 1f)
            val second = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
            drawLine(color, a1, lerpOffset(a1, a2, first), w, StrokeCap.Round)
            if (second > 0f) drawLine(color, b1, lerpOffset(b1, b2, second), w, StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ кнопки

/**
 * Малая стеклянная кнопка с состоянием «занято»: слева кольцо, ширина меняется плавно.
 * [dense] — для плотных мест (шапка подписки в списке серверов).
 */
@Composable
internal fun GlassAction(
    label: String,
    busy: Boolean = false,
    enabled: Boolean = true,
    dense: Boolean = false,
    tone: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    cancelWhenBusy: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.m)
    val color = when {
        busy -> Accent
        !enabled -> Muted.copy(alpha = 0.5f)
        tone != null -> tone
        else -> TextMain
    }
    Box(
        Modifier
            .pressable(shape, enabled && (!busy || cancelWhenBusy), onClick = onClick)
            .animateContentSize()
            .glass(shape)
            .then(if (busy) Modifier.scanSweep() else Modifier)
            .heightIn(min = touchMin())
            .padding(horizontal = if (dense) Spacing.s else Spacing.m, vertical = if (dense) Spacing.xs else Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                SpinnerRing()
                Spacer(Modifier.width(Spacing.s))
            } else if (leading != null) {
                leading()
                Spacer(Modifier.width(Spacing.s))
            }
            Text(label, color = color, fontSize = 11.sp, fontWeight = if (busy) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

/**
 * Кнопки проверки открытой подписки и всех серверов. Способ проверки задаётся в настройках;
 * во время проверки показываются кольцо, счётчик, заливка и бегущий блик.
 */
@Composable
internal fun TestButton(controller: Controller, subscriptionId: String?, all: Boolean) {
    val method = controller.settings.testMethod
    val busy = controller.testing
    val running = busy && (if (all) controller.checkScope == null else controller.checkScope == subscriptionId)
    val canStart = all || subscriptionId != null
    val total = controller.checkTotal
    val done = controller.checkDone
    val fraction by animateFloatAsState(
        targetValue = if (running && total > 0) done.toFloat() / total else 0f,
        animationSpec = tween(300),
        label = "test-progress",
    )
    val shape = RoundedCornerShape(Radii.m)
    val name = when (method) {
        TestMethod.URL -> "URL-тест"
        TestMethod.TCP -> "TCP-тест"
        TestMethod.TCP_URL -> "TCP + URL"
    }

    Box(
        Modifier
            .pressable(shape, enabled = running || (!busy && canStart)) {
                if (running) controller.cancelCheck()
                else if (!busy && canStart) {
                    if (all) controller.testAll() else subscriptionId?.let(controller::testSubscription)
                }
            }
            .animateContentSize()
            .glass(shape)
            .drawBehind {
                if (fraction > 0f) {
                    drawRect(Accent.copy(alpha = 0.22f), size = Size(size.width * fraction, size.height))
                }
            }
            .then(if (running) Modifier.scanSweep() else Modifier)
            .heightIn(min = touchMin())
            .padding(horizontal = Spacing.m, vertical = Spacing.s)
            .semantics {
                contentDescription = when {
                    running -> "остановить проверку серверов"
                    all -> "проверить все серверы: $name"
                    else -> "проверить подписку: $name"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                SpinnerRing()
                Spacer(Modifier.width(Spacing.s))
            }
            Text(
                when {
                    running -> "стоп"
                    busy -> "занято"
                    all -> "Проверить все"
                    else -> "Проверить"
                },
                color = if (running) Accent else TextMain,
                fontSize = 11.sp,
                fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (running && total > 0) {
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    "$done/$total",
                    color = Accent,
                    fontSize = 11.sp,
                    fontFamily = LimeTheme.fonts.mono,
                )
            }
        }
    }
}

// --------------------------------------------------- строка статуса в списке серверов

/**
 * Лента под поиском: «проверяю · <подписка> … 5/12» и полоса прогресса с бегущим бликом.
 * Появляется и исчезает плавно, пока идёт проверка.
 */
@Composable
internal fun CheckStatusBar(controller: Controller) {
    AnimatedVisibility(
        visible = controller.testing,
        enter = fadeIn(tween(200)) + expandVertically(tween(260, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(220)),
    ) {
        val scopeTitle = controller.checkScope
            ?.let { controller.subscription(it)?.title }
            ?.takeIf { it.isNotBlank() }
            ?: "все подписки"
        val method = when (controller.settings.testMethod) {
            TestMethod.URL -> "url-тест"
            TestMethod.TCP -> "tcp-тест"
            TestMethod.TCP_URL -> "tcp + url-тест"
        }
        val fraction = if (controller.checkTotal > 0) controller.checkDone.toFloat() / controller.checkTotal else 0f

        Column(Modifier.fillMaxWidth().padding(top = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing()
                Spacer(Modifier.width(Spacing.s))
                Text(
                    "проверяю · $method · $scopeTitle",
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    "${controller.checkDone}/${controller.checkTotal}",
                    color = Accent,
                    fontSize = 11.sp,
                    fontFamily = LimeTheme.fonts.mono,
                )
            }
            Spacer(Modifier.height(Spacing.s))
            ProgressTrack(fraction)
        }
    }
}

@Composable
private fun ProgressTrack(fraction: Float) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "check-fraction")
    val shape = RoundedCornerShape(Radii.s)
    Box(
        Modifier.fillMaxWidth().height(3.dp)
            .clip(shape)
            .background(LimeColors.Line)
            .scanSweep(color = Color.White, strength = 0.30f),
    ) {
        Box(
            Modifier.fillMaxWidth(animated).fillMaxHeight()
                .clip(shape)
                .background(Accent),
        )
    }
}

// ------------------------------------------------------------ обновление подписки

private enum class RefreshState { IDLE, SPIN, OK, FAIL }

private fun refreshStateOf(controller: Controller, id: String): RefreshState {
    val outcome = controller.refreshOutcomes[id]
    return when {
        controller.updating == id -> RefreshState.SPIN
        outcome == true -> RefreshState.OK
        outcome == false -> RefreshState.FAIL
        else -> RefreshState.IDLE
    }
}

/** Круговая стрелка «обновить»: дуга и наконечник. Рисуется самим, без глифов шрифта. */
private fun DrawScope.drawRefreshArrow(color: Color, strokeWidth: Float) {
    val s = size.width
    val r = s * 0.36f
    val c = Offset(s / 2f, s / 2f)
    val start = -50f
    val sweep = 290f
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(strokeWidth, cap = StrokeCap.Round),
    )
    val rad = Math.toRadians((start + sweep).toDouble())
    val end = Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat())
    val tangent = Offset((-sin(rad)).toFloat(), cos(rad).toFloat())
    val back = Offset(-tangent.x, -tangent.y)
    val tip = end + tangent * (r * 0.22f)
    val len = r * 0.78f
    for (turn in listOf(0.62f, -0.62f)) {
        val cs = cos(turn)
        val sn = sin(turn)
        val v = Offset(back.x * cs - back.y * sn, back.x * sn + back.y * cs)
        drawLine(color, tip, tip + v * len, strokeWidth, StrokeCap.Round)
    }
}

@Composable
private fun SpinningArrow(color: Color, diameter: Dp) {
    val transition = rememberInfiniteTransition(label = "refresh-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "refresh-angle",
    )
    Canvas(Modifier.size(diameter)) {
        rotate(angle) { drawRefreshArrow(color, 1.5.dp.toPx()) }
    }
}

/** Значок обновления: стрелка → вращение → галочка или крестик → снова стрелка. */
@Composable
private fun RefreshGlyph(state: RefreshState, color: Color, diameter: Dp = 16.dp) {
    Crossfade(targetState = state, animationSpec = tween(160), label = "refresh-glyph") { current ->
        when (current) {
            RefreshState.OK -> StatusGlyph(ok = true, diameter = diameter)
            RefreshState.FAIL -> StatusGlyph(ok = false, diameter = diameter)
            RefreshState.SPIN -> SpinningArrow(color, diameter)
            RefreshState.IDLE -> Canvas(Modifier.size(diameter)) { drawRefreshArrow(color, 1.5.dp.toPx()) }
        }
    }
}

/** Только значок: для шапки подписки в списке серверов. */
@Composable
internal fun RefreshIconButton(controller: Controller, sub: SubscriptionInfo) {
    val state = refreshStateOf(controller, sub.id)
    val enabled = sub.url != null && state == RefreshState.IDLE && !controller.updatingAll
    val shape = RoundedCornerShape(Radii.s)
    Box(
        Modifier
            .clip(shape)
            .clickable(enabled = enabled) { controller.refreshSubscription(sub.id) }
            .semantics { contentDescription = "обновить подписку" }
            .heightIn(min = touchMin())
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        RefreshGlyph(state, if (sub.url != null) Accent else Muted)
    }
}

/** Кнопка с подписью: для карточки подписки. Подпись и значок меняются по ходу обновления. */
@Composable
internal fun RefreshButton(controller: Controller, sub: SubscriptionInfo) {
    val state = refreshStateOf(controller, sub.id)
    val enabled = sub.url != null && state == RefreshState.IDLE && !controller.updatingAll
    val label = when (state) {
        RefreshState.SPIN -> "Обновление…"
        RefreshState.OK -> "Обновлено"
        RefreshState.FAIL -> "Не удалось"
        RefreshState.IDLE -> "Обновить"
    }
    val shape = RoundedCornerShape(Radii.m)
    val color = when {
        state == RefreshState.OK -> Accent
        state == RefreshState.FAIL -> Danger
        state == RefreshState.SPIN -> Accent
        !enabled -> Muted.copy(alpha = 0.5f)
        else -> TextMain
    }
    Box(
        Modifier
            .pressable(shape, enabled, onClick = { controller.refreshSubscription(sub.id) })
            .animateContentSize()
            .glass(shape)
            .then(if (state == RefreshState.SPIN) Modifier.scanSweep() else Modifier)
            .heightIn(min = touchMin())
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefreshGlyph(state, color, diameter = 14.dp)
            Spacer(Modifier.width(Spacing.s))
            Text(
                label,
                color = color,
                fontSize = 11.sp,
                fontWeight = if (state == RefreshState.IDLE) FontWeight.Normal else FontWeight.SemiBold,
            )
        }
    }
}

// ------------------------------------------------------- всплывающее сообщение

/**
 * Всплывающее сообщение (вместо строк текста, разбросанных по экранам): стеклянная
 * плашка выезжает снизу, сама прячется (таймер в `Controller.notify`), по тапу закрывается.
 * Успех — акцентная галочка, ошибка — красный крестик; красный только для ошибок.
 * Кладётся в корневой `Box` поверх всего, с `Modifier.align(Alignment.BottomCenter)`.
 */
@Composable
internal fun ToastHost(controller: Controller, modifier: Modifier = Modifier) {
    val text = controller.message
    val isError = controller.messageIsError
    var lastText by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf(false) }
    LaunchedEffect(text, isError) {
        if (text != null) {
            lastText = text
            lastError = isError
        }
    }
    val shownText = text ?: lastText
    val shownError = if (text != null) isError else lastError
    val bottom = if (LocalCompact.current) 44.dp + Spacing.l else Spacing.xl

    AnimatedVisibility(
        visible = text != null,
        modifier = modifier.padding(start = Spacing.l, end = Spacing.l, bottom = bottom),
        enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(180)),
    ) {
        val shape = RoundedCornerShape(Radii.l)
        Row(
            Modifier
                .widthIn(max = 420.dp)
                .clip(shape)
                .background(LimeColors.Surface.copy(alpha = 0.94f))
                .glass(shape)
                .pointerInput(Unit) { detectTapGestures { controller.clearMessage() } }
                .padding(horizontal = Spacing.m, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusGlyph(ok = !shownError, diameter = 16.dp)
            Spacer(Modifier.width(Spacing.m))
            Text(
                shownText,
                color = if (shownError) Danger else TextMain,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
