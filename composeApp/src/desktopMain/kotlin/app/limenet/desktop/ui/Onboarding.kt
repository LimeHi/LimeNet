package app.limenet.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.limenet.ui.theme.LimeColors
import app.limenet.ui.theme.LimeTheme
import app.limenet.ui.theme.Radii
import app.limenet.ui.theme.Spacing
import app.limenet.ui.theme.glass
import java.io.File

/**
 * Инструкция «как пользоваться». Показывается один раз при первом входе поверх главного экрана,
 * потом доступна из «настройки → как пользоваться». Признак «уже видел» — файл
 * `~/.limenet/onboarding-seen` (как у [Favorites]); Controller и настройки не затрагиваются.
 */
internal object Onboarding {
    private val file = File(System.getProperty("user.home"), ".limenet/onboarding-seen")

    var open: Boolean by mutableStateOf(!file.exists())
        private set

    var step: Int by mutableIntStateOf(0)
        private set

    val last: Int get() = STEPS.lastIndex

    fun show() {
        step = 0
        open = true
    }

    fun next() {
        if (step < last) step++ else finish()
    }

    fun back() {
        if (step > 0) step--
    }

    /** Закрыть и запомнить, что инструкцию уже видели (и «пропустить», и «понятно»). */
    fun finish() {
        open = false
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("1")
        }
    }

    /**
     * Клавиатура, пока инструкция открыта. Вешается на корень приложения (preview-событие),
     * чтобы не отбирать фокус у колеса: после закрытия стрелки и Enter работают сразу.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (!open) return false
        if (event.type != KeyEventType.KeyDown) return true // глотаем и KeyUp, чтобы колесо не реагировало
        when (event.key) {
            Key.Escape -> finish()
            Key.DirectionRight, Key.DirectionDown, Key.Enter, Key.NumPadEnter, Key.Spacebar -> next()
            Key.DirectionLeft, Key.DirectionUp -> back()
            else -> Unit
        }
        return true
    }
}

private class Hint(val key: String, val text: String)

private class Step(
    val title: String,
    val lead: String,
    val details: List<String> = emptyList(),
    val hints: List<Hint> = emptyList(),
    /** Кнопка-ярлык на шаге: переход на вкладку и закрытие инструкции. */
    val action: String? = null,
)

private val STEPS = listOf(
    Step(
        title = "добавьте подписку",
        lead = "Вкладка «подписки» → вставьте ссылку от провайдера → «Добавить».",
        details = listOf(
            "Подойдёт https://… со списком серверов или одиночный vless://…",
            "Если дольки на колесе пустые — нажмите кнопку теста под колесом.",
        ),
        action = "к подпискам",
    ),
    Step(
        title = "колесо серверов",
        lead = "Каждая долька — один сервер. Чем она длиннее, тем меньше пинг.",
        details = listOf(
            "Яркая дуга по ободу — остаток трафика подписки.",
            "Колесо можно вращать перетаскиванием: оно примагнитится к ближайшей дольке.",
        ),
        hints = listOf(
            Hint("тап", "выбрать дольку"),
            Hint("← →", "предыдущий / следующий"),
            Hint("колесо мыши", "вращать"),
        ),
    ),
    Step(
        title = "подключение",
        lead = "Тап по центру колеса — подключить или отключить.",
        details = listOf(
            "Подключены, а выбрали другой сервер — в центре появится «переключить». Сам сервер не сменится, пока вы не тапнете.",
            "Над кнопками режим: tun — весь трафик системы (нужны права администратора), socks + http — локальный прокси для приложений.",
        ),
        hints = listOf(Hint("Enter / пробел", "центр колеса")),
    ),
    Step(
        title = "все серверы",
        lead = "Зажмите центр колеса на треть секунды — откроется список всех серверов.",
        details = listOf(
            "В списке есть поиск. ★ ставит сервер на колесо (до 12); без избранных на колесе самые быстрые 12.",
            "Тап по серверу выбирает его. Зажмите строку или нажмите на три точки справа — откроется меню: выбрать, копировать ссылку, копировать json.",
            "Закрыть: «назад», свайп вниз по шапке или выбор сервера.",
        ),
        hints = listOf(Hint("M", "открыть список с клавиатуры")),
    ),
    Step(
        title = "остальное",
        lead = "Под колесом одна кнопка проверки серверов. Способ — «URL-тест» или «TCP-тест» — выбирается в настройках → стабильность.",
        details = listOf(
            "Вкладка «настройки»: маршрутизация, DNS, kill switch, URL и метод проверки, автообновление подписок, разделение трафика, логи.",
            "Эту инструкцию можно открыть снова: настройки → «как пользоваться».",
        ),
    ),
)

/** Слой инструкции: затемнение, стеклянная карточка по центру, шаги со скольжением. */
@Composable
internal fun OnboardingLayer(onOpenSubscriptions: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }

    val step = Onboarding.step
    val data = STEPS[step]
    val shape = RoundedCornerShape(Radii.l)

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = 0.6f))
            // слой перекрывает всё: касания не проваливаются в экран под ним
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    val k = 0.96f + 0.04f * appear.value
                    scaleX = k
                    scaleY = k
                }
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .padding(Spacing.l)
                .clip(shape)
                .background(LimeColors.Surface.copy(alpha = 0.9f))
                .glass(shape)
                .padding(screenPad()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${step + 1} / ${STEPS.size}",
                    color = LimeColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = LimeTheme.fonts.mono,
                )
                Spacer(Modifier.weight(1f))
                if (step < Onboarding.last) SmallButton("пропустить") { Onboarding.finish() }
            }
            Spacer(Modifier.height(Spacing.m))

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                    .heightIn(min = if (LocalCompact.current) 200.dp else 232.dp),
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
                    (slideInHorizontally(spec) { it / 4 * dir } + fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(spec) { -it / 4 * dir } + fadeOut(tween(160)))
                },
                label = "onboarding-step",
            ) { index ->
                StepBody(STEPS[index], onAction = {
                    Onboarding.finish()
                    onOpenSubscriptions()
                })
            }

            Spacer(Modifier.height(Spacing.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton("‹ назад", enabled = step > 0) { Onboarding.back() }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    STEPS.indices.forEach { i ->
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(if (i == step) LimeColors.Accent else LimeColors.Line),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                GlassButton(if (step == Onboarding.last) "понятно" else "далее", accent = true) { Onboarding.next() }
            }
        }
    }
}

@Composable
private fun StepBody(step: Step, onAction: () -> Unit) {
    // на низком окне телефона текст шага прокручивается, кнопки остаются на месте
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(step.title, color = LimeColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(Spacing.s))
        Text(step.lead, color = LimeColors.Text, fontSize = 13.sp, lineHeight = 19.sp)
        step.details.forEach {
            Spacer(Modifier.height(Spacing.s))
            Text(it, color = LimeColors.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (step.hints.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.m))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                step.hints.forEach { KeyHint(it) }
            }
        }
        if (step.action != null) {
            Spacer(Modifier.height(Spacing.m))
            SmallButton(step.action, onClick = onAction)
        }
    }
}

@Composable
private fun KeyHint(hint: Hint) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            hint.key,
            color = LimeColors.Text,
            fontSize = 11.sp,
            fontFamily = LimeTheme.fonts.mono,
            modifier = Modifier
                .glass(RoundedCornerShape(Radii.s))
                .padding(horizontal = Spacing.s, vertical = Spacing.xs),
        )
        Spacer(Modifier.width(Spacing.s))
        Text(hint.text, color = LimeColors.TextSecondary, fontSize = 11.sp)
    }
}
