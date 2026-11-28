package app.limenet.ui.dial

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.limenet.resources.Res
import app.limenet.resources.allDrawableResources
import app.limenet.resources.limenet_logo
import app.limenet.ui.theme.LimeColors
import app.limenet.ui.theme.LimeTheme
import app.limenet.ui.theme.Radii
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/** Данные одного сегмента. Свободный слот — `null` в списке. */
data class DialSlot(
    val id: String,
    /** Подпись вокруг обода: код страны или «·N». */
    val code: String,
    val country: String,
    /** Пинг в мс; `null` — недоступен (если [measured]) либо ещё не мерили. */
    val pingMs: Int?,
    val measured: Boolean,
    /** Код страны для флага (картинка `flag_xx`); `null` — рисуем [code] текстом. */
    val flagCode: String? = null,
) {
    val reachable: Boolean get() = pingMs != null
}

enum class DialStatus { OFF, CONNECTING, ON }

const val DIAL_SEGMENTS = 12
private const val STEP = 30f
private const val SEG_WIDTH = 27f
private const val INNER = 0.46f
private const val OUTER_MIN = 0.62f
private const val OUTER_MAX = 0.89f
private const val DISC = 0.42f

/** Удержание центра открывает список серверов; короткий обычный тап подключает. */
private const val HOLD_MS = 600L

/** quality = 1 − clamp((ping − 20) / 280, 0, 1); недоступный → 0. */
fun dialQuality(pingMs: Int?): Float =
    if (pingMs == null) 0f else 1f - ((pingMs - 20f) / 280f).coerceIn(0f, 1f)

/**
 * Долька лайма: 12 сегментов-серверов, обод-датчик трафика, центр-кнопка.
 *
 * Дольки — избранные серверы (до 12). Выбор ([selectedIndex]) поднят наверх:
 * диал сообщает о смене через [onSelect], а сам только рисует и доворачивается.
 * [selectedIndex] = -1 — выбранный сервер не в избранных (сегмент не подсвечен).
 * [current] — выбранный сервер для центра (может быть вне дольки).
 * Подключение — только через [onCenterTap].
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun LimeDial(
    slots: List<DialSlot?>,
    selectedIndex: Int,
    current: DialSlot?,
    status: DialStatus,
    trafficLeft: Float,
    switchPending: Boolean,
    onSelect: (Int) -> Unit,
    onCenterTap: () -> Unit,
    onCenterLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "нет серверов",
    onEmptySlotTap: () -> Unit = {},
) {
    val cells = remember(slots) { List(DIAL_SEGMENTS) { slots.getOrNull(it) } }
    val fonts = LimeTheme.fonts
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val focus = remember { FocusRequester() }

    val curCells by rememberUpdatedState(cells)
    val curIndex by rememberUpdatedState(selectedIndex)
    val curSelect by rememberUpdatedState(onSelect)
    val curTap by rememberUpdatedState(onCenterTap)
    val curLong by rememberUpdatedState(onCenterLongPress)
    val curEmpty by rememberUpdatedState(onEmptySlotTap)
    val rotIdx = selectedIndex.coerceAtLeast(0)

    // Флаги — готовые картинки (не Юникод-эмодзи); нет картинки — рисуем код текстом.
    val flagPainters: List<Painter?> = cells.map { slot ->
        val res = slot?.flagCode?.takeIf { it.isNotBlank() }
            ?.let { Res.allDrawableResources["flag_${it.lowercase()}"] }
        if (res != null) painterResource(res) else null
    }
    val logo = painterResource(Res.drawable.limenet_logo)

    // Состояния анимации. Всё читается только в draw-фазе, поэтому Canvas
    // перерисовывается лишь когда что-то реально движется.
    var rotation by remember { mutableFloatStateOf(-rotIdx * STEP - 70f) } // стартовый доворот
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val pressWave = remember { Animatable(1f) } // волна от центра при нажатии
    val holdArc = remember { Animatable(0f) }   // дуга «удержания» до открытия списка
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "center-press",
    )
    val intro = remember { Animatable(0f) }
    val fill = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    val scan = remember { Animatable(0f) }
    val wave = remember { Animatable(1f) }
    val gauge = remember { Animatable(0f) }
    val quality = remember { List(DIAL_SEGMENTS) { Animatable(0f) } }
    val selAnim = remember { List(DIAL_SEGMENTS) { Animatable(if (it == selectedIndex) 1f else 0f) } }

    LaunchedEffect(Unit) {
        intro.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Длины сегментов: после замеров меняется только длина, порядок не трогаем.
    val targets = cells.map { if (it == null) 0f else dialQuality(it.pingMs) }
    LaunchedEffect(targets) {
        targets.forEachIndexed { i, t ->
            launch { quality[i].animateTo(t, tween(600, easing = FastOutSlowInEasing)) }
        }
    }
    LaunchedEffect(selectedIndex) {
        selAnim.forEachIndexed { i, a ->
            launch { a.animateTo(if (i == selectedIndex) 1f else 0f, tween(200)) }
        }
    }
    LaunchedEffect(trafficLeft) {
        gauge.animateTo(trafficLeft.coerceIn(0f, 1f), tween(600, easing = FastOutSlowInEasing))
    }
    // Доворот к выбранному сегменту (по кратчайшей дуге).
    LaunchedEffect(selectedIndex, dragging) {
        if (!dragging && selectedIndex >= 0) {
            val base = -selectedIndex * STEP
            val target = base + 360f * ((rotation - base) / 360f).roundToInt()
            animate(
                initialValue = rotation,
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            ) { v, _ -> rotation = v }
        }
    }
    LaunchedEffect(status) {
        when (status) {
            DialStatus.ON -> {
                pulse.snapTo(0f)
                launch {
                    wave.snapTo(0f)
                    wave.animateTo(1f, tween(900, easing = LinearEasing))
                }
                fill.animateTo(1f, tween(500, easing = LinearOutSlowInEasing))
                while (true) {
                    pulse.animateTo(1f, tween(2200, easing = FastOutSlowInEasing))
                    pulse.animateTo(0f, tween(2200, easing = FastOutSlowInEasing))
                }
            }
            DialStatus.CONNECTING -> {
                launch { fill.animateTo(0f, tween(300)) }
                while (true) {
                    scan.snapTo(0f)
                    scan.animateTo(360f, tween(1100, easing = LinearEasing))
                }
            }
            DialStatus.OFF -> fill.animateTo(0f, tween(300))
        }
    }

    fun step(dir: Int) {
        for (j in 1 until DIAL_SEGMENTS) {
            val i = ((curIndex + dir * j) % DIAL_SEGMENTS + DIAL_SEGMENTS) % DIAL_SEGMENTS
            if (curCells[i] != null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                curSelect(i)
                return
            }
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val rDp = side * 0.4f
        val labelSize = (rDp.value * 0.05f).coerceIn(9f, 14f).sp
        val labelStyle = remember(fonts, labelSize) {
            TextStyle(fontFamily = fonts.mono, fontSize = labelSize, fontWeight = FontWeight.Medium)
        }
        val labelCodes = cells.map { it?.code.orEmpty() }
        val labels = remember(labelCodes, labelStyle) {
            labelCodes.map { measurer.measure(AnnotatedString(it), labelStyle) }
        }

        Box(
            Modifier
                .size(side)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionRight, Key.DirectionDown -> { step(1); true }
                        Key.DirectionLeft, Key.DirectionUp -> { step(-1); true }
                        Key.Enter, Key.NumPadEnter, Key.Spacebar -> { curTap(); true }
                        Key.M -> { curLong(); true }
                        else -> false
                    }
                }
                .focusRequester(focus)
                .focusable()
                // тап по сегменту
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        runCatching { focus.requestFocus() }
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val d = pos - c
                        val r = minOf(size.width, size.height) * 0.4f
                        val dist = d.getDistance()
                        if (dist > r * INNER && dist < r * 1.02f) {
                            val theta = norm360(angleOf(d) - rotation)
                            val i = (theta / STEP).roundToInt() % DIAL_SEGMENTS
                            if (curCells[i] != null) {
                                if (i != curIndex) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    curSelect(i)
                                }
                            } else if (dist < r * OUTER_MAX) {
                                curEmpty() // пустой слот → добавить избранное
                            }
                        }
                    }
                }
                // вращение перетаскиванием вокруг центра
                .pointerInput(Unit) {
                    var active = false
                    var last = 0f
                    detectDragGestures(
                        onDragStart = { pos ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val d = pos - c
                            active = d.getDistance() > minOf(size.width, size.height) * 0.4f * (DISC + 0.02f)
                            if (active) {
                                dragging = true
                                last = angleOf(d)
                            }
                        },
                        onDragEnd = { active = false; dragging = false },
                        onDragCancel = { active = false; dragging = false },
                    ) { change, _ ->
                        if (!active) return@detectDragGestures
                        change.consume()
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val a = angleOf(change.position - c)
                        var delta = a - last
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        last = a
                        rotation += delta
                        val nearest = nearestFilled(curCells, norm360(-rotation) / STEP)
                        if (nearest >= 0 && nearest != curIndex) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            curSelect(nearest)
                        }
                    }
                }
                // колесо мыши
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Scroll) {
                                val dy = e.changes.first().scrollDelta.y
                                if (dy != 0f) step(if (dy > 0f) 1 else -1)
                                e.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension * 0.4f
                val c = center
                val hair = 0.5.dp.toPx()
                val ringW = r * 0.03f
                val introT = intro.value
                val introE = FastOutSlowInEasing.transform(introT)
                val ringRect = Rect(c.x - r, c.y - r, c.x + r, c.y + r)

                // направляющая максимальной длины сегмента
                drawCircle(
                    LimeColors.Line.copy(alpha = 0.5f), radius = r * OUTER_MAX, center = c,
                    style = Stroke(hair),
                )

                // обод: дорожка + датчик остатка трафика
                drawCircle(
                    LimeColors.Rind.copy(alpha = 0.18f), radius = r, center = c,
                    style = Stroke(ringW),
                )
                val sweep = 360f * (gauge.value * introE).coerceIn(0f, 1f)
                if (sweep > 0f) {
                    drawArc(
                        color = LimeColors.Rind.copy(alpha = 0.6f),
                        startAngle = -90f, sweepAngle = sweep, useCenter = false,
                        topLeft = ringRect.topLeft, size = Size(r * 2, r * 2),
                        style = Stroke(ringW),
                    )
                    if (fill.value > 0f) {
                        drawArc(
                            color = LimeColors.Accent.copy(alpha = 0.78f + 0.22f * pulse.value),
                            startAngle = -90f, sweepAngle = sweep * fill.value, useCenter = false,
                            topLeft = ringRect.topLeft, size = Size(r * 2, r * 2),
                            style = Stroke(ringW),
                        )
                    }
                }
                if (status == DialStatus.CONNECTING) {
                    drawArc(
                        color = LimeColors.Accent.copy(alpha = 0.9f),
                        startAngle = -90f + scan.value, sweepAngle = 70f, useCenter = false,
                        topLeft = ringRect.topLeft, size = Size(r * 2, r * 2),
                        style = Stroke(ringW),
                    )
                }

                // сегменты
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                val inner = r * INNER
                for (i in 0 until DIAL_SEGMENTS) {
                    val slot = cells[i]
                    val s = selAnim[i].value
                    val grow = growth(introT, i)
                    val target = if (slot == null) r * 0.58f
                    else r * (OUTER_MIN + (OUTER_MAX - OUTER_MIN) * quality[i].value)
                    val outer = inner + (target - inner) * grow
                    val path = segmentPath(c, inner, outer, i * STEP + rotation - SEG_WIDTH / 2f)

                    if (slot == null) {
                        drawPath(path, LimeColors.Accent.copy(alpha = 0.08f), style = Stroke(hair))
                        continue
                    }
                    val k = (i - selectedIndex + DIAL_SEGMENTS) % DIAL_SEGMENTS
                    val p = wave.value * (DIAL_SEGMENTS + 3) - k
                    val boost = if (p in 0f..3f) sin(PI.toFloat() * p / 3f) else 0f
                    val dead = slot.measured && !slot.reachable
                    val fillColor = if (dead) {
                        // недоступный: даже выбранный не «сплошной» — пунктир должен читаться
                        LimeColors.Accent.copy(alpha = 0.06f + 0.29f * s)
                    } else {
                        lerp(LimeColors.Accent.copy(alpha = 0.16f + 0.3f * boost), LimeColors.Accent, s)
                    }
                    drawPath(path, fillColor)
                    if (dead) {
                        drawPath(
                            path, LimeColors.Accent.copy(alpha = 0.5f),
                            style = Stroke(hair * 2, pathEffect = dash),
                        )
                    } else if (s < 1f) {
                        drawPath(path, LimeColors.Accent.copy(alpha = 0.4f * (1f - s)), style = Stroke(hair))
                    }
                }

                // флаги вокруг обода (картинки; нет картинки — код страны текстом)
                val fs = (r * 0.15f).coerceIn(14.dp.toPx(), 24.dp.toPx())
                val rl = r * 1.12f
                val corner = CornerRadius(Radii.s.toPx())
                for (i in 0 until DIAL_SEGMENTS) {
                    if (cells[i] == null) continue
                    val th = (i * STEP + rotation) * PI.toFloat() / 180f
                    val pos = Offset(c.x + rl * sin(th), c.y - rl * cos(th))
                    val sel = selAnim[i].value
                    val painter = flagPainters[i]
                    if (painter != null) {
                        val tl = Offset(pos.x - fs / 2f, pos.y - fs / 2f)
                        // PNG флага — квадрат 72×72 с прозрачными полями: обрезку и рамку
                        // ведём по видимой части флага, иначе рамка стоит «чуть дальше» флага
                        val b = FLAG_BOUNDS[cells[i]?.flagCode?.lowercase()] ?: FLAG_DEFAULT_BOUNDS
                        val u = fs / FLAG_SRC
                        val vis = Rect(tl.x + b[0] * u, tl.y + b[1] * u, tl.x + b[2] * u, tl.y + b[3] * u)
                        val clip = Path().apply { addRoundRect(RoundRect(vis, corner)) }
                        clipPath(clip) {
                            translate(tl.x, tl.y) {
                                with(painter) { draw(Size(fs, fs), alpha = introE * (0.6f + 0.4f * sel)) }
                            }
                        }
                        drawRoundRect(
                            color = lerp(LimeColors.Line, LimeColors.Accent, sel),
                            topLeft = vis.topLeft, size = vis.size, cornerRadius = corner,
                            style = Stroke(if (sel > 0.5f) 1.5.dp.toPx() else hair),
                        )
                    } else {
                        val lay = labels[i]
                        drawText(
                            lay,
                            color = lerp(LimeColors.TextSecondary, LimeColors.Accent, sel),
                            topLeft = Offset(pos.x - lay.size.width / 2f, pos.y - lay.size.height / 2f),
                            alpha = introE,
                        )
                    }
                }

                // маркер над выбранным сегментом
                if (cells.getOrNull(selectedIndex) != null) {
                    val mc = Offset(c.x, c.y - r)
                    drawCircle(LimeColors.Bg, radius = r * 0.075f, center = mc)
                    drawCircle(LimeColors.Accent, radius = r * 0.045f, center = mc)
                }

                // диск-кнопка: стекло (заливка, блик сверху, обводка со светлым углом)
                val disc = r * DISC
                val statusRing = when (status) {
                    DialStatus.ON -> LimeColors.Accent
                    DialStatus.CONNECTING -> LimeColors.Accent.copy(alpha = 0.5f)
                    DialStatus.OFF -> null
                }
                drawCircle(LimeColors.Surface.copy(alpha = 0.72f), radius = disc, center = c)
                drawCircle(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = if (pressed) 0.20f else 0.12f),
                            Color.White.copy(alpha = 0.03f),
                            LimeColors.Accent.copy(alpha = 0.06f),
                        ),
                        start = Offset(c.x - disc, c.y - disc),
                        end = Offset(c.x + disc, c.y + disc),
                    ),
                    radius = disc, center = c,
                )
                // блик: светлый «полумесяц» у верхнего края
                clipPath(Path().apply { addOval(Rect(c.x - disc, c.y - disc, c.x + disc, c.y + disc)) }) {
                    drawOval(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                            startY = c.y - disc,
                            endY = c.y + disc * 0.1f,
                        ),
                        topLeft = Offset(c.x - disc * 0.82f, c.y - disc * 1.0f),
                        size = Size(disc * 1.64f, disc * 1.0f),
                    )
                }
                drawCircle(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.06f),
                            LimeColors.Accent.copy(alpha = 0.28f),
                        ),
                        start = Offset(c.x - disc, c.y - disc),
                        end = Offset(c.x + disc, c.y + disc),
                    ),
                    radius = disc, center = c, style = Stroke(1.dp.toPx()),
                )
                if (statusRing != null) {
                    drawCircle(statusRing, radius = disc, center = c, style = Stroke(1.5.dp.toPx()))
                }
                // волна от центра при нажатии
                val w = pressWave.value
                if (w < 1f) {
                    drawCircle(
                        LimeColors.Accent.copy(alpha = 0.40f * (1f - w)),
                        radius = disc * (1f + 0.35f * w), center = c, style = Stroke(2.dp.toPx()),
                    )
                }
                // дуга удержания: заполняется, пока держишь центр; на конце откроется список
                if (holdArc.value > 0.01f) {
                    drawArc(
                        color = LimeColors.Accent,
                        startAngle = -90f, sweepAngle = 360f * holdArc.value, useCenter = false,
                        topLeft = Offset(c.x - disc - 4.dp.toPx(), c.y - disc - 4.dp.toPx()),
                        size = Size((disc + 4.dp.toPx()) * 2, (disc + 4.dp.toPx()) * 2),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }

            // Центр: большая круглая кнопка с логотипом; под ней — статус и пинг.
            // Тап — подключить/отключить, долгое нажатие — список серверов.
            val statusText = when {
                status == DialStatus.CONNECTING -> "подключаюсь…"
                current == null -> emptyText
                status == DialStatus.ON && switchPending -> "переключить"
                status == DialStatus.ON -> "подключено"
                else -> "отключено"
            }
            val pingText = when {
                current == null -> "—"
                current.pingMs != null -> "${current.pingMs} мс"
                current.measured -> "—"
                else -> "…"
            }
            val countryText = current?.country ?: emptyText
            val action = when {
                current == null -> "открыть список серверов"
                status == DialStatus.ON && switchPending -> "переключиться на $countryText"
                status == DialStatus.ON -> "отключиться"
                else -> "подключиться"
            }
            val baseConfig = LocalViewConfiguration.current
            val lightHold = remember(baseConfig) {
                object : ViewConfiguration by baseConfig {
                    override val longPressTimeoutMillis: Long get() = HOLD_MS
                }
            }
            CompositionLocalProvider(LocalViewConfiguration provides lightHold) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(rDp * DISC * 2)
                        .clip(CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    pressed = true
                                    scope.launch {
                                        pressWave.snapTo(0f)
                                        pressWave.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                                    }
                                    val arc = scope.launch {
                                        holdArc.snapTo(0f)
                                        holdArc.animateTo(1f, tween(HOLD_MS.toInt(), easing = LinearEasing))
                                    }
                                    tryAwaitRelease()
                                    pressed = false
                                    arc.cancel()
                                    scope.launch { holdArc.animateTo(0f, tween(160)) }
                                },
                                onTap = { curTap() },
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    curLong()
                                },
                            )
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = "$statusText, $countryText, $pingText. $action"
                            onClick(label = action) { curTap(); true }
                            onLongClick(label = "список серверов") { curLong(); true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.fillMaxWidth(0.9f).graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(rDp * DISC * 2 * 0.48f),
                        )
                        Spacer(Modifier.height(rDp * 0.03f))
                        BasicText(
                            statusText,
                            style = TextStyle(
                                fontFamily = fonts.sans,
                                fontSize = (rDp.value * 0.055f).coerceAtLeast(10f).sp,
                                color = if (status == DialStatus.ON) LimeColors.Accent else LimeColors.TextSecondary,
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            pingText,
                            style = TextStyle(
                                fontFamily = fonts.mono,
                                fontWeight = FontWeight.Medium,
                                fontSize = (rDp.value * 0.085f).coerceAtLeast(12f).sp,
                                color = LimeColors.Text,
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }

            // Невидимые узлы доступности: по одному на сегмент.
            for (i in 0 until DIAL_SEGMENTS) {
                val slot = cells[i] ?: continue
                val th = ((i - selectedIndex) * STEP) * PI.toFloat() / 180f
                val ping = slot.pingMs?.let { "$it мс" } ?: if (slot.measured) "недоступен" else "не измерен"
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = rDp * 0.7f * sin(th), y = -rDp * 0.7f * cos(th))
                        .size(rDp * 0.2f)
                        .semantics {
                            role = Role.RadioButton
                            selected = i == selectedIndex
                            contentDescription = "${slot.country}, $ping"
                            onClick(label = "выбрать") { curSelect(i); true }
                        },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- геометрия

/** Угол точки относительно центра: 0° сверху, по часовой, 0..360. */
private fun angleOf(d: Offset): Float = norm360(atan2Deg(d.x, -d.y))

private fun atan2Deg(y: Float, x: Float): Float =
    (kotlin.math.atan2(y, x) * 180f / PI.toFloat())

private fun norm360(a: Float): Float {
    val m = a % 360f
    return if (m < 0f) m + 360f else m
}

/** Ближайший непустой слот к дробной позиции [pos] (в шагах по 30°). */
private fun nearestFilled(cells: List<DialSlot?>, pos: Float): Int {
    var best = -1
    var bestDist = Float.MAX_VALUE
    for (i in cells.indices) {
        if (cells[i] == null) continue
        var d = kotlin.math.abs(pos - i)
        if (d > DIAL_SEGMENTS / 2f) d = DIAL_SEGMENTS - d
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

/** Рост сегмента при появлении: волна по часовой с мягким перелётом. */
private fun growth(intro: Float, index: Int): Float {
    val span = 1f - (DIAL_SEGMENTS - 1) * 0.035f
    val t = ((intro - index * 0.035f) / span).coerceIn(0f, 1f)
    val u = t - 1f
    return 1f + 2f * u * u * u + 1f * u * u // easeOutBack
}

/**
 * Сегмент: внутренний старт → внешний старт, дуга по внешней окружности,
 * линия к внутреннему концу, дуга назад по внутренней, close.
 */
private fun segmentPath(c: Offset, rIn: Float, rOut: Float, startDeg: Float): Path {
    fun pt(radius: Float, deg: Float): Offset {
        val a = deg * PI.toFloat() / 180f
        return Offset(c.x + radius * sin(a), c.y - radius * cos(a))
    }
    val path = Path()
    val a = pt(rIn, startDeg)
    val b = pt(rOut, startDeg)
    val d = pt(rIn, startDeg + SEG_WIDTH)
    path.moveTo(a.x, a.y)
    path.lineTo(b.x, b.y)
    path.arcTo(Rect(c.x - rOut, c.y - rOut, c.x + rOut, c.y + rOut), startDeg - 90f, SEG_WIDTH, false)
    path.lineTo(d.x, d.y)
    path.arcTo(Rect(c.x - rIn, c.y - rIn, c.x + rIn, c.y + rIn), startDeg + SEG_WIDTH - 90f, -SEG_WIDTH, false)
    path.close()
    return path
}

/** Размер PNG флагов (квадрат), из которого видна только часть: остальное прозрачные поля. */
private const val FLAG_SRC = 72f

/** Видимая часть флага в пикселях PNG: left, top, right, bottom. У почти всех флагов одна и та же. */
private val FLAG_DEFAULT_BOUNDS = listOf(0f, 10f, 72f, 62f)

/** Флаги с другой формой: почти квадратные и узкие. */
private val FLAG_BOUNDS: Map<String?, List<Float>> = mapOf(
    "be" to listOf(6f, 10f, 66f, 62f),
    "ch" to listOf(10f, 10f, 62f, 62f),
    "va" to listOf(10f, 10f, 62f, 62f),
    "np" to listOf(16f, 10f, 56f, 62f),
)
