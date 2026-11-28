package app.limenet.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.limenet.ui.APP_NAME
import app.limenet.ui.theme.LimeColors
import app.limenet.ui.theme.LimeTheme
import app.limenet.ui.theme.Radii
import app.limenet.ui.theme.Spacing
import app.limenet.core.Flags
import app.limenet.core.Format
import app.limenet.core.Mode
import app.limenet.core.ProxyNode
import app.limenet.core.QrConfigPayload
import app.limenet.core.model.DEFAULT_TEST_URL
import app.limenet.core.model.CoreEngine
import app.limenet.core.model.DnsMode
import app.limenet.core.model.RoutingPreset
import app.limenet.core.model.RuleAction
import app.limenet.core.model.RuleKind
import app.limenet.core.model.RoutingRule
import app.limenet.core.model.parseRoutingRuleValues
import app.limenet.core.model.SubscriptionInfo
import app.limenet.core.model.TEST_URL_PRESETS
import app.limenet.core.model.TestMethod
import app.limenet.core.model.UserAgentPreset
import app.limenet.desktop.Controller
import app.limenet.desktop.Hwid
import app.limenet.desktop.Platform
import app.limenet.desktop.QrScanner
import app.limenet.desktop.UrlResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.limenet.ui.theme.GlassBackdrop
import app.limenet.ui.theme.glass
import app.limenet.ui.theme.pressable
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import app.limenet.resources.Res
import app.limenet.resources.allDrawableResources
import app.limenet.resources.limenet_logo
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

private val Bg = LimeColors.Bg
private val Panel = LimeColors.Surface
private val Panel2 = LimeColors.Line
private val Line = LimeColors.Line
private val TextMain = LimeColors.Text
private val Muted = LimeColors.TextSecondary
private val Accent = LimeColors.Accent
private val AccentDark = LimeColors.Bg
private val Danger = LimeColors.Error

/** Нейтральное «предупреждение»: второго акцентного цвета нет. */
private val Warn = LimeColors.Text

private enum class Tab(val label: String) {
    CONNECT("подключение"),
    SUBS("подписки"),
    SETTINGS("настройки"),
}

@Composable
fun LimeNetApp(controller: Controller) {
    val tabs = Tab.entries
    val pager = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val position = { pager.currentPage + pager.currentPageOffsetFraction }
    var updateOffer by remember { mutableStateOf<DesktopUpdateOffer?>(null) }
    var qrNode by remember { mutableStateOf<ProxyNode?>(null) }

    LaunchedEffect(Onboarding.open) {
        if (!Onboarding.open) {
            kotlinx.coroutines.delay(1_000)
            updateOffer = withContext(Dispatchers.IO) { fetchDesktopUpdateOffer() }
        }
    }

    fun go(tab: Tab) {
        scope.launch {
            pager.animateScrollToPage(
                tab.ordinal,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }

    // Шторка серверов «вырастает» из центральной кнопки и сворачивается обратно.
    val sheet = remember { Animatable(0f) }
    var sheetShown by remember { mutableStateOf(false) }
    LaunchedEffect(ServersSheet.open) {
        if (ServersSheet.open) {
            sheetShown = true
            sheet.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
        } else {
            sheet.animateTo(0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium))
            sheetShown = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalCompact provides (maxWidth < COMPACT_MAX)) {
    LimeTheme {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                onPrimary = AccentDark,
                background = Bg,
                onBackground = TextMain,
                surface = Panel,
                onSurface = TextMain,
                surfaceVariant = Panel2,
                onSurfaceVariant = Muted,
                outline = Line,
                outlineVariant = Line,
                error = Danger,
            ),
            typography = limeTypography(LimeTheme.fonts),
            shapes = limeShapes,
        ) {
            Surface(Modifier.fillMaxSize(), color = Bg) {
                Box(Modifier.fillMaxSize().onPreviewKeyEvent { Onboarding.onKey(it) }) {
                    GlassBackdrop(shift = position)

                    // Основной экран чуть отъезжает назад, пока открыта шторка (как на iOS).
                    Column(
                        Modifier.fillMaxSize().graphicsLayer {
                            val p = sheet.value.coerceIn(0f, 1f)
                            val k = 1f - 0.06f * p
                            scaleX = k
                            scaleY = k
                            alpha = 1f - 0.5f * p
                        },
                    ) {
                        TopBar(controller, position) { go(it) }
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            beyondViewportPageCount = tabs.size,
                            userScrollEnabled = !ServersSheet.open,
                        ) { index ->
                            Box(
                                Modifier.fillMaxSize().graphicsLayer {
                                    // соседняя страница уходит в глубину и растворяется
                                    val off = ((pager.currentPage - index) + pager.currentPageOffsetFraction)
                                        .absoluteValue.coerceIn(0f, 1f)
                                    val k = 1f - 0.06f * off
                                    scaleX = k
                                    scaleY = k
                                    alpha = 1f - 0.55f * off
                                },
                            ) {
                                when (tabs[index]) {
                                    Tab.CONNECT -> DialScreen(
                                        controller,
                                        onOpenSubscriptions = { go(Tab.SUBS) },
                                        onOpenList = { ServersSheet.show() },
                                    )
                                    Tab.SUBS -> SubscriptionsScreen(controller)
                                    Tab.SETTINGS -> SettingsScreen(controller)
                                }
                            }
                        }
                        // телефон: вкладки внизу, как в Android-приложениях
                        if (LocalCompact.current) BottomBar(position) { go(it) }
                    }

                    if (sheetShown) {
                        ServersSheetLayer(
                            controller = controller,
                            progress = { sheet.value },
                            onShowQr = { qrNode = it },
                            onOpenSubscriptions = {
                                ServersSheet.hide()
                                go(Tab.SUBS)
                            },
                        )
                    }

                    // всплывающие сообщения: поверх экрана и шторки серверов
                     ToastHost(controller, Modifier.align(Alignment.BottomCenter))

                     qrNode?.let { node ->
                         DesktopQrDialog(node = node, onClose = { qrNode = null })
                     }

                    if (Onboarding.open) {
                        OnboardingLayer(onOpenSubscriptions = { go(Tab.SUBS) })
                    }

                    updateOffer?.let { offer ->
                        Box(
                            Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.58f))
                                .clickable { updateOffer = null },
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                modifier = Modifier.widthIn(min = 320.dp, max = 460.dp)
                                    .clickable { },
                                color = Panel,
                                shape = RoundedCornerShape(Radii.l),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(24.dp),
                                ) {
                                    Text("Доступна другая версия LimeNet", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(12.dp))
                                    Text("На GitHub указана версия ${offer.version} (${offer.code}); установлена ${offer.currentVersion} (${offer.currentCode}).", color = Muted)
                                    Spacer(Modifier.height(20.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (offer.downloadUrl.isNotBlank()) {
                                            TextButton(onClick = {
                                                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(offer.downloadUrl)) }
                                                updateOffer = null
                                            }) { Text("Скачать") }
                                        }
                                        TextButton(onClick = { updateOffer = null }) { Text("Позже") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
    }
}

/**
 * Слой «все серверы». Растёт из центра колеса (scale + прозрачность + скругление окна),
 * закрывается кнопкой «назад», свайпом вниз по шапке или выбором сервера.
 */
@Composable
private fun ServersSheetLayer(
    controller: Controller,
    progress: () -> Float,
    onShowQr: (ProxyNode) -> Unit,
    onOpenSubscriptions: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val origin = ServersSheet.origin

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .graphicsLayer {
                val p = progress()
                val k = androidx.compose.ui.util.lerp(0.12f, 1f, p)
                scaleX = k
                scaleY = k
                alpha = (p * 1.8f).coerceIn(0f, 1f)
                transformOrigin = if (origin.isSpecified && size.width > 0 && size.height > 0) {
                    TransformOrigin(origin.x / size.width, origin.y / size.height)
                } else {
                    TransformOrigin.Center
                }
                shape = RoundedCornerShape((96f * (1f - p.coerceIn(0f, 1f))).dp)
                clip = true
            }
            // слой перекрывает всё: касания не проваливаются в экран под ним
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        GlassBackdrop(shift = { 1f })
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = screenPad())
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dy -> if (dy > 14f) ServersSheet.hide() }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallButton("‹ назад") { ServersSheet.hide() }
                Spacer(Modifier.width(Spacing.m))
                Text("Серверы", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            NodePanel(
                controller,
                onShowQr = onShowQr,
                onOpenSubscriptions = onOpenSubscriptions,
                onPicked = { ServersSheet.hide() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

// ------------------------------------------------------------------ каркас

@Composable
private fun TopBar(controller: Controller, position: () -> Float, onTab: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = screenPad()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(Res.drawable.limenet_logo),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(Spacing.s))
        Text(APP_NAME, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.width(Spacing.s))
        // единственный индикатор состояния: точка (акцент — подключено)
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(if (controller.connected) Accent else Muted),
        )

        Spacer(Modifier.weight(1f))
        if (!LocalCompact.current) {
            Tab.entries.forEach { entry ->
                TabButton(
                    label = entry.label,
                    strength = { (1f - abs(position() - entry.ordinal)).coerceIn(0f, 1f) },
                ) { onTab(entry) }
                Spacer(Modifier.width(Spacing.xs))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** Нижняя навигация телефона: три равные стеклянные плашки, активная подсвечена. */
@Composable
private fun BottomBar(position: () -> Float, onTab: (Tab) -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Tab.entries.forEach { entry ->
            TabButton(
                label = entry.label,
                strength = { (1f - abs(position() - entry.ordinal)).coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f),
            ) { onTab(entry) }
        }
    }
}

/** Вкладка: стеклянная «плашка» проявляется и гаснет вслед за перелистыванием. */
@Composable
private fun TabButton(
    label: String,
    strength: () -> Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.m)
    Box(
        modifier
            .pressable(shape, onClick = onClick)
            .glass(shape, level = strength)
            .heightIn(min = if (LocalCompact.current) 44.dp else 0.dp)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = androidx.compose.ui.graphics.lerp(Muted, Accent, strength()),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ------------------------------------------------------------------ серверы

/** Выбор сервера (кнопка «выбрать» в раскрытой строке) → вкладка «подключение». ★ — в избранное (дольки). */
private fun pickNode(controller: Controller, id: String, onPicked: () -> Unit) {
    if (controller.connected && id != controller.selectedId) {
        Pick.pendingId = id // не переключаем молча: подтверждение — тапом в центр
    } else {
        Pick.pendingId = null
        controller.selectNode(id)
    }
    onPicked()
}

@Composable
private fun NodePanel(
    controller: Controller,
    onShowQr: (ProxyNode) -> Unit,
    onOpenSubscriptions: () -> Unit,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var onlyFav by remember { mutableStateOf(false) }
    var activeSubscription by remember {
        mutableStateOf(controller.selectedId?.let(controller::subscriptionOf)?.id)
    }
    val favIds = Favorites.ids
    val compact = LocalCompact.current

    LaunchedEffect(controller.subscriptions, activeSubscription) {
        if (activeSubscription != null && controller.subscriptions.none { it.id == activeSubscription }) {
            activeSubscription = null
        }
    }

    Column(modifier.padding(screenPad())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(Radii.m),
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Поиск: страна, имя, протокол", fontSize = 12.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(8.dp))
            val checkSubscriptionId = activeSubscription
                ?: controller.selectedId?.let(controller::subscriptionOf)?.id
                ?: controller.settings.activeSubscriptionId
                ?: controller.subscriptions.firstOrNull()?.id
            TestControls(controller, checkSubscriptionId)
        }
        // пока идёт проверка — видно, что именно проверяется и сколько осталось
        CheckStatusBar(controller)

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip("все", active = !onlyFav) { onlyFav = false }
            Spacer(Modifier.width(8.dp))
            Chip("избранные ${favIds.size}/${Favorites.MAX}", active = onlyFav) { onlyFav = true }
        }

        Spacer(Modifier.height(Spacing.s))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("Все · ${controller.nodes.size}", active = activeSubscription == null) {
                activeSubscription = null
            }
            controller.subscriptions.forEach { sub ->
                val count = controller.nodeCount(sub.id)
                if (count > 0) {
                    Chip(
                        "${sub.title.ifBlank { "Подписка" }} · $count",
                        active = activeSubscription == sub.id,
                    ) { activeSubscription = sub.id }
                }
            }
        }

        if (!compact) {
            Spacer(Modifier.width(12.dp))
            Text(NODE_HINT, color = Muted, fontSize = 10.sp)
        } else {
            Spacer(Modifier.height(Spacing.s))
            Text(NODE_HINT, color = Muted, fontSize = 10.sp)
        }

        Spacer(Modifier.height(12.dp))

        val needle = query.trim().lowercase()

        if (controller.nodes.isEmpty()) {
            EmptyState(
                title = "Пока нет серверов",
                text = "Добавьте ссылку на подписку — $APP_NAME сам разберёт узлы и покажет задержки.",
                action = "Добавить подписку",
                onAction = onOpenSubscriptions,
            )
            return@Column
        }

        AnimatedContent(
            targetState = activeSubscription,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val spec = spring<IntOffset>(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessMediumLow,
                )
                (slideInHorizontally(spec) { it / 5 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(spec) { -it / 6 } + fadeOut(tween(130)))
            },
            label = "desktop-subscription-groups",
        ) { groupId ->
            val groupNodes = controller.nodes.filter { node ->
                groupId == null || controller.subscriptionOf(node.id)?.id == groupId
            }
            val visible = groupNodes.filter { node ->
                (!onlyFav || node.id in favIds) &&
                    (
                        needle.isEmpty() ||
                            node.name.lowercase().contains(needle) ||
                            node.server.lowercase().contains(needle) ||
                            node.protocol.wire.contains(needle) ||
                            (node.country?.lowercase()?.contains(needle) == true)
                        )
            }

            if (visible.isEmpty()) {
                EmptyState(
                    title = when {
                        onlyFav && needle.isEmpty() -> "Нет избранных в этой группе"
                        else -> "Ничего не найдено"
                    },
                    text = if (onlyFav && needle.isEmpty()) {
                        "Отметьте серверы звёздочкой — они появятся дольками на главном экране."
                    } else {
                        "Попробуйте другое название, страну или протокол."
                    },
                )
            } else {
                val flat = needle.isNotEmpty() || onlyFav
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (needle.isEmpty()) {
                        item(key = "auto-fav") {
                            val pool = groupNodes.filter { it.id in favIds }
                            AutoRow("авто · быстрейший из избранных", enabled = pool.isNotEmpty()) {
                                val id = fastestId(controller, pool)
                                if (id != null) pickNode(controller, id, onPicked) else controller.testAll()
                            }
                        }
                        if (!onlyFav) {
                            item(key = "auto-all") {
                                AutoRow("авто · быстрейший из всех", enabled = groupNodes.isNotEmpty()) {
                                    val id = fastestId(controller, groupNodes)
                                    if (id != null) pickNode(controller, id, onPicked) else controller.testAll()
                                }
                            }
                        }
                    }
                    items(visible, key = { it.id }) { node ->
                        NodeRow(
                            controller,
                            node,
                            onPicked,
                            subtitle = if (flat) controller.subscriptionOf(node.id)?.title else null,
                            onShowQr = onShowQr,
                        )
                    }
                }
            }
        }
    }
}

/** Строка «авто»: выбирает самый быстрый по уже снятым замерам (разово, не режим). */
@Composable
private fun AutoRow(title: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.l)
    Row(
        Modifier.fillMaxWidth()
            .pressable(shape, enabled, depth = 0.985f, onClick = onClick)
            .glass(shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (enabled) Accent else Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text("→", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun SubscriptionHeader(
    subscription: SubscriptionInfo,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    controller: Controller,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radii.m))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "▾" else "▸", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            subscription.title.ifBlank { "Подписка" },
            color = TextMain,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(trafficShort(subscription), color = Muted, fontSize = 10.sp)
        Spacer(Modifier.width(12.dp))
        Text("$count", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.width(Spacing.s))
        // проверка только серверов этой подписки: способ — из настроек
        GlassAction(
            label = if (controller.isChecking(subscription.id)) "стоп" else "тест",
            busy = controller.isChecking(subscription.id),
            enabled = !controller.testing || controller.isChecking(subscription.id),
            dense = true,
            cancelWhenBusy = true,
        ) {
            if (controller.testing) controller.cancelCheck() else controller.testSubscription(subscription.id)
        }
        Spacer(Modifier.width(Spacing.xs))
        // обновление: стрелка вращается, затем галочка или крестик
        RefreshIconButton(controller, subscription)
    }
}

private const val NODE_HINT = "тап — выбрать · зажатие или три точки — меню · ★ — на колесо"

/**
 * Кнопка проверки серверов. Одна: способ (URL-тест или TCP-тест) выбирается в
 * настройках → стабильность, на кнопке подпись показывает выбранный способ и ход проверки.
 */
@Composable
internal fun TestControls(controller: Controller, subscriptionId: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        TestButton(controller, subscriptionId, all = false)
        TestButton(controller, subscriptionId = null, all = true)
    }
}

/**
 * Строка сервера. Тап — выбрать сервер. Меню (выбрать, копировать ссылку, копировать json)
 * открывается только зажатием строки или нажатием на «⋮».
 */
@Composable
private fun NodeRow(
    controller: Controller,
    node: ProxyNode,
    onPicked: () -> Unit,
    subtitle: String? = null,
    onShowQr: (ProxyNode) -> Unit,
) {
    val selected = controller.selectedId == node.id
    val ping = controller.pings[node.id]
    val tested = node.id in controller.pings
    val method = controller.settings.testMethod
    // URL показывает реальный результат, TCP — только соединение с портом.
    val result = if (method != TestMethod.TCP) controller.urlResults[node.id] else null
    val tcpFailure = controller.tcpFailures[node.id].takeIf { method != TestMethod.URL }
    val compact = LocalCompact.current
    // проверка идёт: узел ждёт очереди (точки) или проверяется сейчас (кольцо и блик по строке)
    val queued = node.id in controller.checkQueue
    val scanning = node.id in controller.checkActive
    // на телефоне скорость уходит во вторую строку: в первой не хватает места
    val speedText = result?.speedBps?.takeIf { controller.settings.showSpeed && method != TestMethod.TCP }?.let { Format.speed(it) }
    val subLine = if (compact) listOfNotNull(subtitle, speedText).joinToString(" · ").ifEmpty { null } else subtitle

    var menuOpen by remember(node.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val cardShape = RoundedCornerShape(Radii.l)
    Column(
        Modifier.fillMaxWidth()
            .clip(cardShape)
            .glass(cardShape)
            .background(if (selected) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (scanning) Modifier.scanSweep() else Modifier),
    ) {
    Row(
        Modifier.fillMaxWidth()
            .pressable(cardShape, depth = 0.985f, onLongClick = { menuOpen = true }) {
                // обычный тап выбирает сервер; узел без движка выбрать нельзя — показываем меню (ссылка)
                if (node.supported) pickNode(controller, node.id, onPicked) else menuOpen = true
            }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (node.autoBalancerTag != null) "AUTO" else node.protocol.wire.uppercase(),
            color = Muted,
            fontSize = 9.sp,
            modifier = Modifier.width(if (compact) 40.dp else 56.dp),
        )

        Column(Modifier.weight(1f)) {
            if (node.autoBalancerTag != null) {
                Text("Авто", color = TextMain, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            } else NodeTitle(node, fontSize = 13, color = if (node.supported) TextMain else Muted)
            if (subLine != null) {
                Text(
                    subLine,
                    color = Muted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!compact && controller.settings.showSpeed && method != TestMethod.TCP && result?.speedBps != null) {
            Text(Format.speed(result.speedBps), color = Muted, fontSize = 10.sp)
            Spacer(Modifier.width(12.dp))
        }

        // результат плавно сменяет индикатор проверки; ширина фиксирована, чтобы строка не «прыгала»
        Box(Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.CenterEnd) {
            Crossfade(
                targetState = if (scanning) 2 else if (queued) 1 else 0,
                animationSpec = tween(200),
                label = "ping-state",
            ) { phase ->
                when (phase) {
                    2 -> SpinnerRing()
                    1 -> PendingDots()
                    else -> Text(
                        when {
                            node.autoBalancerTag != null -> "подписка"
                            !node.supported -> "нет движка"
                            result?.failure == app.limenet.core.model.TestFailure.TIMEOUT || tcpFailure == app.limenet.core.model.TestFailure.TIMEOUT -> "тайм-аут"
                            method == TestMethod.TCP && tcpFailure == null && tested && ping == null -> "недоступен"
                            result?.error != null -> "недоступен"
                            tcpFailure != null -> "недоступен"
                            result?.latencyMs != null -> "${result.latencyMs} мс"
                            ping != null -> "$ping мс"
                            tested -> "недоступен"
                            else -> "…"
                        },
                        color = when {
                            node.autoBalancerTag != null -> Muted
                            !node.supported -> Danger
                            result?.failure != null || tcpFailure != null -> Danger
                            method == TestMethod.TCP && tested && ping == null -> Danger
                            result?.error != null -> Danger
                            result?.latencyMs != null -> Accent
                            ping != null -> if (ping < 200) Accent else Muted
                            tested -> Danger
                            else -> Muted
                        },
                        fontSize = 11.sp,
                    )
                }
            }
        }

        val fav = Favorites.isFav(node.id)
        val canToggle = fav || !Favorites.full
        Text(
            if (fav) "★" else "☆",
            color = when {
                fav -> Accent
                canToggle -> Muted
                else -> Line
            },
            fontSize = 16.sp,
            modifier = Modifier
                .padding(start = if (compact) 0.dp else 12.dp)
                .clip(RoundedCornerShape(Radii.s))
                .clickable(enabled = node.supported && canToggle) { Favorites.toggle(node.id) }
                .semantics { contentDescription = if (fav) "убрать из избранных" else "добавить в избранные" }
                .padding(horizontal = if (compact) 10.dp else 4.dp, vertical = if (compact) 8.dp else 0.dp),
        )

        // «⋮»: три точки рисуем сами — не зависим от глифов шрифта
        Box {
            Column(
                Modifier
                    .padding(start = if (compact) 0.dp else 4.dp)
                    .clip(RoundedCornerShape(Radii.s))
                    .clickable { menuOpen = true }
                    .semantics { contentDescription = "меню сервера" }
                    .padding(horizontal = if (compact) 12.dp else 10.dp, vertical = if (compact) 10.dp else 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(3) {
                    Box(Modifier.size(3.dp).clip(CircleShape).background(Muted))
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                NodeMenuItem("Выбрать", enabled = node.supported) {
                    menuOpen = false
                    pickNode(controller, node.id, onPicked)
                }
                NodeMenuItem("Копировать ссылку") {
                    menuOpen = false
                    clipboard.setText(AnnotatedString(node.raw))
                    controller.toast("Ссылка скопирована")
                }
                NodeMenuItem("Показать QR-код") {
                    menuOpen = false
                    onShowQr(node)
                }
                NodeMenuItem("Копировать JSON", enabled = node.supported) {
                    menuOpen = false
                    runCatching { controller.nodeConfigJson(node) }
                        .onSuccess {
                            clipboard.setText(AnnotatedString(it))
                            controller.toast("JSON скопирован")
                        }
                        .onFailure { controller.toast("Не удалось собрать JSON", error = true) }
                }
            }
        }
    }
    }
}

@Composable
private fun NodeMenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (enabled) TextMain else Muted.copy(alpha = 0.5f),
                fontSize = 13.sp,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

// --------------------------------------------------------------- подписки

@Composable
private fun SubscriptionsScreen(controller: Controller) {
    var input by remember { mutableStateOf("") }
    var catalogSelected by remember { mutableStateOf(false) }
    var catalogQuery by remember { mutableStateOf("") }
    val compact = LocalCompact.current
    val scanScope = rememberCoroutineScope()
    var scanBusy by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Column(Modifier.fillMaxSize().padding(screenPad())) {
        Text("Подписки", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Ссылка на подписку или список конфигов. $APP_NAME хранит их локально и обновляет сам.",
            color = Muted,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(Radii.m),
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("https://… или vless://…", fontSize = 12.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    controller.addSubscription(input)
                    input = ""
                }),
            )
            Spacer(Modifier.width(8.dp))
            GlassButton(
                "Добавить",
                accent = true,
                enabled = !controller.busy && input.isNotBlank(),
            ) {
                controller.addSubscription(input)
                input = ""
            }
            Spacer(Modifier.width(8.dp))
            GlassAction(
                label = if (scanBusy) "Сканирую…" else "Сканировать QR",
                busy = scanBusy,
                enabled = !controller.busy || scanBusy,
                cancelWhenBusy = true,
            ) {
                if (scanBusy) {
                    scanJob?.cancel()
                } else {
                    scanBusy = true
                    scanJob = scanScope.launch {
                        try {
                            val scanned = withContext(Dispatchers.IO) { QrScanner.scan() }
                            controller.addSubscription(QrConfigPayload.validate(scanned))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            controller.toast(error.message ?: "Не удалось прочитать QR-код", error = true)
                        } finally {
                            scanBusy = false
                            scanJob = null
                        }
                    }
                }
            }
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                SubscriptionBulkActions(controller)
            }
        }
        if (compact) {
            Spacer(Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubscriptionBulkActions(controller)
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Мои подписки", !catalogSelected) { catalogSelected = false }
            Chip("Полезные подписки", catalogSelected) { catalogSelected = true }
        }
        Spacer(Modifier.height(12.dp))

        if (catalogSelected) {
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(Radii.m),
                value = catalogQuery,
                onValueChange = { catalogQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Поиск по подпискам", fontSize = 12.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    controller.catalogError?.let {
                        if (controller.catalog.isNotEmpty()) "$it · показана сохранённая копия" else it
                    } ?: if (controller.catalogLoading) "Загрузка каталога…" else "Каталог GitHub",
                    color = if (controller.catalogError != null) Danger else Muted,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                GlassButton(if (controller.catalogLoading) "Загрузка…" else "Обновить", enabled = !controller.catalogLoading) {
                    controller.refreshCatalog()
                }
            }
            Spacer(Modifier.height(12.dp))
            val filteredCatalog = controller.catalog.filter { entry ->
                catalogQuery.isBlank() || listOf(entry.name, entry.description, entry.url)
                    .any { it.contains(catalogQuery.trim(), ignoreCase = true) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filteredCatalog.isEmpty()) {
                    item {
                        Text(
                            if (controller.catalogError != null) "Каталог не загружен. Проверьте подключение и нажмите «Обновить»."
                            else if (controller.catalogLoading) "Получаем список из GitHub…"
                            else if (controller.catalog.isEmpty()) "В GitHub-каталоге пока нет записей."
                            else "Ничего не найдено",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                items(filteredCatalog, key = { it.url }) { entry ->
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(Radii.l)).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.name, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                if (entry.status == app.limenet.core.CatalogEntry.Status.FREE) "Бесплатно" else "Платно / Бот",
                                color = if (entry.status == app.limenet.core.CatalogEntry.Status.FREE) Accent else Warn,
                                fontSize = 10.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(entry.description, color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton(
                                if (entry.opensExternally) "Открыть бота" else "Добавить",
                                accent = !entry.opensExternally,
                                enabled = !controller.busy,
                            ) {
                                if (entry.opensExternally) {
                                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(entry.url)) }
                                        .onFailure { controller.toast("Не удалось открыть ссылку", error = true) }
                                } else controller.addCatalogEntry(entry)
                            }
                            entry.channelUrl?.let { url ->
                                GlassButton("Канал") {
                                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                                        .onFailure { controller.toast("Не удалось открыть канал", error = true) }
                                }
                            }
                        }
                    }
                }
            }
        } else if (controller.subscriptions.isEmpty()) {
            EmptyState(
                title = "Подписок нет",
                text = "Вставьте ссылку сверху. Поддерживаются vless, vmess, trojan, shadowsocks и base64-списки.",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(controller.subscriptions, key = { it.id }) { sub ->
                    SubscriptionCard(controller, sub)
                }
            }
        }
    }
}

@Composable
private fun DesktopQrDialog(node: ProxyNode, onClose: () -> Unit) {
    val matrix = remember(node.raw) {
        runCatching {
            QRCodeWriter().encode(
                node.raw,
                BarcodeFormat.QR_CODE,
                600,
                600,
                mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
        }.getOrNull()
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.60f)).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 320.dp, max = 460.dp).clickable { },
            color = Panel,
            shape = RoundedCornerShape(Radii.l),
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("QR-код конфигурации", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Spacing.s))
                Text(node.displayName, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Spacing.m))
                Text("Наведите камеру телефона на код", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(Spacing.s))
                if (matrix != null) {
                    Canvas(Modifier.size(280.dp).background(Color.White).padding(8.dp)) {
                        val cellWidth = size.width / matrix.width
                        val cellHeight = size.height / matrix.height
                        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                            if (matrix[x, y]) {
                                drawRect(
                                    Color.Black,
                                    topLeft = Offset(x * cellWidth, y * cellHeight),
                                    size = Size(cellWidth + 0.5f, cellHeight + 0.5f),
                                )
                            }
                        }
                    }
                } else {
                    Text("Не удалось создать QR-код", color = Danger)
                }
                Spacer(Modifier.height(Spacing.m))
                Text("QR-код содержит секрет подключения. Показывайте его только доверенному устройству.", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(Spacing.s))
                GlassButton("Закрыть", onClick = onClose)
            }
        }
    }
}

/** «Обновить все» и «Тест всех»: оба показывают ход работы на самой кнопке. */
@Composable
private fun SubscriptionBulkActions(controller: Controller) {
    GlassAction(
        label = if (controller.updatingAll) "Обновляю…" else "Обновить все",
        busy = controller.updatingAll,
        enabled = controller.updating == null,
    ) { controller.updateAll() }
    Spacer(Modifier.width(Spacing.s))
    GlassAction(
        label = if (controller.testing && controller.checkScope == null) {
            "Стоп · ${controller.checkDone}/${controller.checkTotal}"
        } else {
            "Тест всех"
        },
        busy = controller.testing && controller.checkScope == null,
        enabled = !controller.testing || controller.checkScope == null,
        cancelWhenBusy = true,
    ) { if (controller.testing) controller.cancelCheck() else controller.testAll() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionCard(controller: Controller, sub: SubscriptionInfo) {
    val count = controller.nodeCount(sub.id)
    var editing by remember(sub.id) { mutableStateOf(false) }
    var draft by remember(sub.id) { mutableStateOf(sub.title) }
    val compact = LocalCompact.current
    val save = {
        controller.renameSubscription(sub.id, draft)
        editing = false
    }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radii.l))
            .glass(RoundedCornerShape(Radii.l))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(Radii.m),
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("название подписки", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                } else {
                    Text(
                        sub.title.ifBlank { "Подписка" },
                        color = TextMain,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (sub.url != null) {
                    Text(
                        sub.url,
                        color = Muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text("$count узлов", color = Muted, fontSize = 11.sp)
        }

        if (sub.announce.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("объявление", color = Muted, fontSize = 9.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                sub.announce,
                color = TextMain,
                fontSize = 11.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }

        sub.refreshError?.let { Text("Не удалось обновить: $it", color = Danger, fontSize = 10.sp) }
        if (sub.total > 0 && sub.used >= sub.total) {
            Text("Трафик подписки исчерпан", color = Danger, fontSize = 10.sp)
        }
        if (sub.expire > 0) {
            val daysLeft = Format.daysLeft(sub.expire, System.currentTimeMillis() / 1000)
            when {
                daysLeft != null && daysLeft < 0 -> Text("Срок подписки истёк", color = Danger, fontSize = 10.sp)
                daysLeft != null && daysLeft in 0..2 -> Text("Срок подписки скоро истечёт", color = Warn, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        TrafficBar(sub)
        Spacer(Modifier.height(8.dp))

        if (compact) {
            Text(trafficText(sub), color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(Spacing.xs))
            Text(expireText(sub), color = expireColor(sub), fontSize = 10.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trafficText(sub), color = Muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(expireText(sub), color = expireColor(sub), fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        if (compact) {
            Text("Обновлено: ${Format.date(sub.updatedAt / 1000)}", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(Spacing.s))
        }
        // кнопки действий; на телефоне их четыре, поэтому они переносятся по ширине
        val actions: @Composable () -> Unit = {
            if (editing) {
                SmallButton("Сохранить", enabled = draft.isNotBlank()) { save() }
                SmallButton("Отмена") {
                    draft = sub.title
                    editing = false
                }
            } else {
                SmallButton("Переименовать") {
                    draft = sub.title
                    editing = true
                }
            }
            // на телефоне при переименовании остаются только «Сохранить» и «Отмена»
            if (!(compact && editing)) {
                GlassAction(
                    label = if (controller.isChecking(sub.id)) {
                        "Стоп · ${controller.checkDone}/${controller.checkTotal}"
                    } else {
                        "Тест"
                    },
                    busy = controller.isChecking(sub.id),
                    enabled = !controller.testing || controller.isChecking(sub.id),
                    cancelWhenBusy = true,
                ) {
                    if (controller.testing) controller.cancelCheck() else controller.testSubscription(sub.id)
                }
                RefreshButton(controller, sub)
                SmallButton("Удалить", danger = true) { controller.removeSubscription(sub.id) }
            }
        }
        if (compact) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) { actions() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    "Обновлено: ${Format.date(sub.updatedAt / 1000)}",
                    color = Muted,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    }
}

@Composable
private fun TrafficBar(sub: SubscriptionInfo) {
    val total = sub.total
    val used = sub.used
    val fraction = if (total > 0) (used.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    val color = when {
        total <= 0 -> Muted
        fraction > 0.9 -> Danger
        fraction > 0.7 -> Warn
        else -> Accent
    }

    Box(
        Modifier.fillMaxWidth().height(5.dp)
            .clip(RoundedCornerShape(Radii.s))
            .background(Line),
    ) {
        if (total > 0) {
            Box(
                Modifier.fillMaxWidth(fraction.toFloat()).fillMaxHeight()
                    .clip(RoundedCornerShape(Radii.s))
                    .background(color),
            )
        }
    }
}

private fun trafficShort(sub: SubscriptionInfo): String {
    if (sub.total <= 0) return "${Format.bytes(sub.used)}"
    return "${Format.bytes(sub.used)} / ${Format.bytes(sub.total)}"
}

private fun trafficText(sub: SubscriptionInfo): String {
    if (sub.total <= 0) return "Трафик: ${Format.bytes(sub.used)} (без лимита)"
    val left = (sub.total - sub.used).coerceAtLeast(0)
    return "Трафик: ${Format.bytes(sub.used)} из ${Format.bytes(sub.total)} · осталось ${Format.bytes(left)}"
}

private fun expireText(sub: SubscriptionInfo): String {
    if (sub.expire <= 0) return "без срока"
    val now = System.currentTimeMillis() / 1000
    val days = Format.daysLeft(sub.expire, now)
    return when {
        days == null -> "без срока"
        days < 0 -> "истекла ${Format.date(sub.expire)}"
        days == 0L -> "истекает сегодня"
        else -> "до ${Format.date(sub.expire)} ($days дн.)"
    }
}

private fun expireColor(sub: SubscriptionInfo): Color {
    if (sub.expire <= 0) return Muted
    val days = Format.daysLeft(sub.expire, System.currentTimeMillis() / 1000) ?: return Muted
    return when {
        days < 0 -> Danger
        days < 3 -> Warn
        else -> Muted
    }
}

// --------------------------------------------------------------- настройки

private enum class SettingsPage(val title: String) {
    CHAIN("Цепочка узлов"),
    ENGINE("Движок подключения"),
    MODE("Режим работы"),
    ROUTING("Маршрутизация"),
    DNS("DNS"),
    NETWORK("Сеть и производительность"),
    STABILITY("Стабильность"),
    APP_UPDATES("Обновление приложения"),
    UPDATES("Обновление подписок"),
    SPLIT("Разделение трафика"),
    ADMIN("Права администратора"),
    LOGS("Логи ядра"),
    SPEED("Отображение скорости"),
}

/** Настройки: список разделов → отдельная страница на каждую функцию, со скольжением. */
@Composable
private fun SettingsScreen(controller: Controller) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val spec = tween<IntOffset>(380, easing = FastOutSlowInEasing)
            if (targetState != null) {
                (slideInHorizontally(spec) { it / 3 } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(200)))
            } else {
                (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(spec) { it / 3 } + fadeOut(tween(200)))
            }
        },
        label = "settings-nav",
    ) { current ->
        if (current == null) {
            SettingsRoot(controller) { page = it }
        } else {
            SettingsDetail(controller, current) { page = null }
        }
    }
}

private fun settingsSummary(controller: Controller, page: SettingsPage): String {
    val s = controller.settings
    if (page == SettingsPage.CHAIN) return s.chainEntryNodeId?.let { id -> controller.nodes.firstOrNull { it.id == id }?.shortName }
        ?.let { "Через узел: $it" } ?: "Напрямую (Без цепочки)"
    if (page == SettingsPage.SPEED) return if (s.showSpeed) "Скорость отображается" else "Скорость скрыта"
    return when (page) {
        SettingsPage.CHAIN -> s.chainEntryNodeId?.let { id -> controller.nodes.firstOrNull { it.id == id }?.shortName }
            ?.let { "Через узел: $it" } ?: "Напрямую (Без цепочки)"
        SettingsPage.SPEED -> if (s.showSpeed) "Скорость отображается" else "Скорость скрыта"
        SettingsPage.ENGINE -> if (s.engine == CoreEngine.XRAY) "Xray-core" else "Sing-box"
        SettingsPage.MODE -> if (s.mode == Mode.TUN) "TUN" else "SOCKS + HTTP"
        SettingsPage.ROUTING -> when (s.routing) {
            RoutingPreset.ALL_PROXY -> "всё через VPN"
            RoutingPreset.RU_DIRECT -> "РФ напрямую"
            RoutingPreset.CUSTOM -> "свои правила"
        }
        SettingsPage.DNS -> when (s.dns.mode) {
            DnsMode.SYSTEM -> "системный · ${s.dns.remoteServer}"
            DnsMode.DOH -> "DoH · ${s.dns.remoteServer.substringAfter("://").substringBefore('/')}"
            DnsMode.DOT -> "DoT · ${s.dns.remoteServer.substringAfter("://").substringBefore('/')}"
        }
        SettingsPage.NETWORK -> "MTU ${s.tunMtu ?: "Авто"} · QUIC ${if (s.blockQuic) "выкл" else "вкл"}"
        SettingsPage.STABILITY ->
            "kill switch ${if (s.killSwitch) "вкл" else "выкл"} · автопереключение ${if (s.autoSwitch) "вкл" else "выкл"}"
        SettingsPage.APP_UPDATES -> "проверка версии LimeNet"
        SettingsPage.UPDATES -> buildList {
            add(if (s.autoUpdateHours == 0) "автообновление выкл" else "каждые ${s.autoUpdateHours} ч")
            if (s.updateSubscriptionsOnLaunch) add("проверка подписок при входе")
            if (s.pingServersOnLaunch) add("пинг при входе")
            if (s.batterySaver) add("экономия батареи")
        }.joinToString(" · ")
        SettingsPage.SPLIT ->
            "напрямую: ${s.splitTunnel.directApps.size} · через VPN: ${s.splitTunnel.proxyApps.size}"
        SettingsPage.ADMIN -> if (controller.isAdmin) "есть" else "нет"
        SettingsPage.LOGS -> "${controller.logs.size} строк"
    }
}

@Composable
private fun SettingsRoot(controller: Controller, onOpen: (SettingsPage) -> Unit) {
    Column(Modifier.fillMaxSize().padding(screenPad()).verticalScroll(rememberScrollState())) {
        Text("Настройки", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        SettingsPage.entries.forEach { entry ->
            val shape = RoundedCornerShape(Radii.l)
            Row(
                Modifier.fillMaxWidth()
                    .pressable(shape, depth = 0.985f) { onOpen(entry) }
                    .glass(shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        settingsSummary(controller, entry),
                        color = if (entry == SettingsPage.ADMIN && !controller.isAdmin) Danger else Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("›", color = Muted, fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        val helpShape = RoundedCornerShape(Radii.l)
        Row(
            Modifier.fillMaxWidth()
                .pressable(helpShape, depth = 0.985f) { Onboarding.show() }
                .glass(helpShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Как пользоваться", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("колесо, подключение, список серверов", color = Muted, fontSize = 11.sp)
            }
            Text("›", color = Muted, fontSize = 16.sp)
        }
        Spacer(Modifier.height(Spacing.s))
        val telegramShape = RoundedCornerShape(Radii.l)
        Row(
            Modifier.fillMaxWidth().pressable(telegramShape, depth = 0.985f) {
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://t.me/LimeProdNews")) }
            }.glass(telegramShape).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Наш Telegram · @LimeProdNews", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Новости и обновления LimeNet", color = Muted, fontSize = 11.sp)
            }
            Text("↗", color = Muted, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SettingsDetail(controller: Controller, page: SettingsPage, onBack: () -> Unit) {
    val settings = controller.settings
    Column(Modifier.fillMaxSize().padding(screenPad()).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton("‹ назад", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(page.title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(Radii.l)).padding(16.dp)) {
            when (page) {
                SettingsPage.CHAIN -> {
                    Text("Первый узел соединяется с выбранным сервером. Выберите сохранённый узел, отличный от целевого.", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(12.dp))
                    Chip("Напрямую (Без цепочки)", settings.chainEntryNodeId == null) {
                        controller.updateSettings { it.copy(chainEntryNodeId = null) }
                    }
                    Spacer(Modifier.height(8.dp))
                    controller.nodes.filter { it.supported && it.id != settings.selectedNodeId }.forEach { entry ->
                        Chip("Через узел (Цепочка): ${entry.shortName}", settings.chainEntryNodeId == entry.id) {
                            controller.updateSettings { it.copy(chainEntryNodeId = entry.id) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (controller.nodes.none { it.supported && it.id != settings.selectedNodeId }) {
                        Text("Нет других сохранённых узлов для цепочки.", color = Muted, fontSize = 10.sp)
                    }
                }
                SettingsPage.SPEED -> ToggleRow(
                    "Показывать скорость в списке серверов",
                    "Скрывает результаты замера скорости в карточках серверов.",
                    settings.showSpeed,
                ) { value -> controller.updateSettings { it.copy(showSpeed = value) } }
                SettingsPage.ENGINE -> {
                    Row {
                        Chip("Xray", settings.engine == CoreEngine.XRAY) {
                            controller.updateSettings { it.copy(engine = CoreEngine.XRAY) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Chip("Sing-box", settings.engine == CoreEngine.SING_BOX) {
                            controller.updateSettings { it.copy(engine = CoreEngine.SING_BOX) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (settings.engine == CoreEngine.XRAY) {
                            "Xray-core: VLESS, VMess, Trojan и Shadowsocks."
                        } else {
                            "Sing-box: VLESS, VMess, Trojan и Shadowsocks. Sing-box должен быть установлен рядом с приложением в vendor/sing-box/sing-box.exe."
                        },
                        color = Muted,
                        fontSize = 10.sp,
                    )
                }
                SettingsPage.MODE -> {
                Row {
                    Chip("TUN", settings.mode == Mode.TUN) { controller.updateMode(Mode.TUN) }
                    Spacer(Modifier.width(8.dp))
                    Chip("SOCKS + HTTP", settings.mode == Mode.PROXY) { controller.updateMode(Mode.PROXY) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (settings.mode == Mode.TUN) {
                        "Виртуальный адаптер перехватывает весь трафик. Нужны права администратора."
                    } else {
                        "Локальный SOCKS5 :${Controller.SOCKS_PORT} и HTTP :${Controller.HTTP_PORT}, " +
                            "плюс системный прокси Windows."
                    },
                    color = Muted,
                    fontSize = 10.sp,
                )
                }
                SettingsPage.ROUTING -> {
                Row {
                    Chip("Всё через VPN", settings.routing == RoutingPreset.ALL_PROXY) {
                        controller.updateSettings { it.copy(routing = RoutingPreset.ALL_PROXY) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Chip("РФ напрямую", settings.routing == RoutingPreset.RU_DIRECT) {
                        controller.updateSettings { it.copy(routing = RoutingPreset.RU_DIRECT) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Chip("Свои правила", settings.routing == RoutingPreset.CUSTOM) {
                        controller.updateSettings { it.copy(routing = RoutingPreset.CUSTOM) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "«РФ напрямую»: geosite:category-ru и geoip:ru идут мимо VPN, остальное — через узел. " +
                        "FakeIP в Xray-core нет, поэтому DNS работает через DoH/DoT без утечек.",
                    color = Muted,
                    fontSize = 10.sp,
                )

                if (settings.routing == RoutingPreset.CUSTOM) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Добавляйте списки доменов/IP и выбирайте маршрут для каждой записи: через VPN, напрямую или блокировать. Разделители — запятая, пробел или новая строка.",
                        color = Muted,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    RuleEditor(controller)
                }
                }
                SettingsPage.DNS -> {
                Row {
                    Chip("Системный", settings.dns.mode == DnsMode.SYSTEM) {
                        controller.updateSettings { it.copy(dns = it.dns.copy(mode = DnsMode.SYSTEM)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Chip("DoH", settings.dns.mode == DnsMode.DOH) {
                        controller.updateSettings { it.copy(dns = it.dns.copy(mode = DnsMode.DOH)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Chip("DoT", settings.dns.mode == DnsMode.DOT) {
                        controller.updateSettings { it.copy(dns = it.dns.copy(mode = DnsMode.DOT)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SettingField(
                    label = "Удалённый DNS",
                    value = settings.dns.remoteServer,
                    placeholder = "https://dns.google/dns-query",
                ) { value ->
                    controller.updateSettings { it.copy(dns = it.dns.copy(remoteServer = value)) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Готовый DoH-провайдер", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Row {
                    listOf(
                        "Google" to "https://dns.google/dns-query",
                        "Cloudflare" to "https://1.1.1.1/dns-query",
                        "Quad9" to "https://dns.quad9.net/dns-query",
                    ).forEach { (name, address) ->
                        Chip(name, settings.dns.remoteServer == address) {
                            controller.updateSettings { it.copy(dns = it.dns.copy(mode = DnsMode.DOH, remoteServer = address)) }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                SettingField(
                    label = "DNS для российских доменов",
                    value = settings.dns.directServer,
                    placeholder = "77.88.8.8",
                ) { value ->
                    controller.updateSettings { it.copy(dns = it.dns.copy(directServer = value)) }
                }
                }
                SettingsPage.STABILITY -> {
                ToggleRow(
                    "Kill Switch",
                    "Блокировать утечки DNS и трафика при обрыве (WFP внутри Xray, режим TUN).",
                    settings.killSwitch,
                ) { value ->
                    controller.updateSettings { it.copy(killSwitch = value) }
                }
                ToggleRow(
                    "Автопереключение",
                    "Если узел или ядро упали — переключиться на рабочий.",
                    settings.autoSwitch,
                ) { value ->
                    controller.updateSettings { it.copy(autoSwitch = value) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Перед каждым запуском LimeNet проверяет выбранный узел реальным запросом. Если он не отвечает, приложение попробует рабочий узел из той же подписки (если включено автопереключение).",
                    color = Muted,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(8.dp))
                TestSettings(controller)
                }
                SettingsPage.NETWORK -> {
                    Text("MTU для виртуального адаптера. Авто оставляет стандартные 1500 байт; 1400 или 1280 стоит пробовать, если сайты, звонки или игры зависают.", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        listOf("Авто" to null, "1500" to 1500, "1400" to 1400, "1280" to 1280).forEach { (label, value) ->
                            Chip(label, settings.tunMtu == value) { controller.updateSettings { it.copy(tunMtu = value) } }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        "Отключать QUIC / HTTP3",
                        "Блокировать UDP/443, чтобы YouTube и другие приложения могли перейти на TCP. Может снизить скорость.",
                        settings.blockQuic,
                    ) { value -> controller.updateSettings { it.copy(blockQuic = value) } }
                    Spacer(Modifier.height(12.dp))
                    Text("TLS-фрагментация (только Sing-box)", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (settings.engine == CoreEngine.SING_BOX) {
                        ToggleRow(
                            "Фрагментировать ClientHello",
                            "Для отдельных заблокированных TLS-соединений; может ухудшить скорость и совместимость. Включайте только если нужно.",
                            settings.tlsFragment,
                        ) { value -> controller.updateSettings { it.copy(tlsFragment = value) } }
                        ToggleRow(
                            "Разбивать TLS на записи",
                            "Более лёгкий вариант фрагментации. Если помогает, предпочтительнее полного ожидания между фрагментами.",
                            settings.tlsRecordFragment,
                        ) { value -> controller.updateSettings { it.copy(tlsRecordFragment = value) } }
                    } else {
                        Text("Выбран Xray: эти параметры его генератором не применяются. Переключите движок на Sing-box, если нужна фрагментация.", color = Muted, fontSize = 10.sp)
                    }
                }
                SettingsPage.APP_UPDATES -> DesktopAppUpdateCheck()
                SettingsPage.UPDATES -> {
                ToggleRow(
                    "Проверять подписки при входе",
                    "Обновлять подписки, для которых уже наступил срок проверки по выбранному интервалу.",
                    settings.updateSubscriptionsOnLaunch,
                ) { value -> controller.updateSettings { it.copy(updateSubscriptionsOnLaunch = value) } }
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    "Пинговать серверы при входе",
                    "Проверить доступность и задержку всех серверов после открытия LimeNet.",
                    settings.pingServersOnLaunch,
                ) { value -> controller.updateSettings { it.copy(pingServersOnLaunch = value) } }
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    "Экономия батареи",
                    "Реже проверять активное соединение и подписки, отключить автоматические пинги серверов. VPN продолжит работать, но обнаружение обрыва может занять больше времени.",
                    settings.batterySaver,
                ) { value -> controller.updateSettings { it.copy(batterySaver = value) } }
                Spacer(Modifier.height(12.dp))
                Text("Интервал автообновления подписок", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.height(12.dp))
                Row {
                    listOf(0, 6, 12, 24, 48).forEach { hours ->
                        Chip(if (hours == 0) "Выкл" else "${hours} ч", settings.autoUpdateHours == hours) {
                            controller.updateSettings { it.copy(autoUpdateHours = hours) }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Автообновление по ссылке: лимиты трафика и дата окончания подтягиваются из заголовка подписки.",
                    color = Muted,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(16.dp))
                Text("User-Agent при загрузке подписки", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Row {
                    UserAgentPreset.entries.forEach { preset ->
                        Chip(preset.label, settings.userAgent == preset) {
                            controller.updateSettings { it.copy(userAgent = preset) }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Если подписка не добавляется или приходит пустой список — смените клиент: часть панелей отдаёт серверы только известным приложениям. Сейчас: ${settings.userAgent.value}.",
                    color = Muted,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    "Привязка к устройству (HWID)",
                    "Отправлять панели заголовки x-hwid / x-device-os / x-ver-os / x-device-model. Нужно для подписок с лимитом устройств (Remnawave, Marzban). Отпечаток: ${Hwid.value.take(8)}…",
                    settings.hwid,
                ) { value ->
                    controller.updateSettings { it.copy(hwid = value) }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (controller.protocolRegistered) {
                            "Схема limenet:// зарегистрирована — ссылки открываются в $APP_NAME."
                        } else {
                            "Схема limenet:// не зарегистрирована."
                        },
                        color = if (controller.protocolRegistered) Accent else Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    SmallButton("Проверить снова") {
                        controller.recheckProtocol()
                    }
                }
                }
                SettingsPage.SPLIT -> {
                SplitList(
                    title = "Напрямую (мимо VPN)",
                    items = settings.splitTunnel.directApps,
                    onAdd = { value ->
                        controller.updateSettings {
                            it.copy(splitTunnel = it.splitTunnel.copy(directApps = it.splitTunnel.directApps + value))
                        }
                    },
                    onRemove = { value ->
                        controller.updateSettings {
                            it.copy(
                                splitTunnel = it.splitTunnel.copy(
                                    directApps = it.splitTunnel.directApps - value,
                                ),
                            )
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                SplitList(
                    title = "Только через VPN",
                    items = settings.splitTunnel.proxyApps,
                    onAdd = { value ->
                        controller.updateSettings {
                            it.copy(splitTunnel = it.splitTunnel.copy(proxyApps = it.splitTunnel.proxyApps + value))
                        }
                    },
                    onRemove = { value ->
                        controller.updateSettings {
                            it.copy(
                                splitTunnel = it.splitTunnel.copy(
                                    proxyApps = it.splitTunnel.proxyApps - value,
                                ),
                            )
                        }
                    },
                )
                }
                SettingsPage.ADMIN -> {
                Text(
                    if (controller.isAdmin) "Есть — TUN доступен" else "Нет — TUN недоступен",
                    color = if (controller.isAdmin) Accent else Danger,
                    fontSize = 12.sp,
                )
                if (!controller.isAdmin) {
                    Spacer(Modifier.height(8.dp))
                    GlassButton("Перезапустить с правами") { controller.relaunchElevated() }
                }
                }
                SettingsPage.LOGS -> {
                Box(
                    Modifier.fillMaxWidth().height(200.dp)
                        .clip(RoundedCornerShape(Radii.m))
                        .background(Bg.copy(alpha = 0.4f))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(
                        controller.logs.takeLast(80).joinToString("\n").ifEmpty { "пока пусто" },
                        color = Muted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DesktopAppUpdateCheck() {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var downloadUrl by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    Column {
        SmallButton("Проверить обновление", enabled = !checking) {
            checking = true
            result = "Проверяю версию…"
            downloadUrl = ""
            scope.launch {
                val checkResult = withContext(Dispatchers.IO) {
                    runCatching {
                        val lines = readGithubText("version.txt")
                        val version = lines.getOrNull(0)?.trim().orEmpty()
                        val code = lines.getOrNull(1)?.trim()?.toIntOrNull()
                        check(version.isNotBlank() && code != null) { "Неверный формат version.txt" }
                        val current = Platform.appVersion
                        if (version == current.first && code == current.second) {
                            "Установлена последняя версия (${current.first}, ${current.second})." to ""
                        } else {
                            val downloadUrl = readGithubText("downloadlinks.txt").getOrNull(1)?.trim().orEmpty()
                            val message = if (downloadUrl.isBlank()) {
                                "Доступна версия $version ($code), но ссылка для ПК пока не опубликована."
                            } else {
                                "Доступна версия $version ($code)."
                            }
                            message to downloadUrl
                        }
                    }.getOrElse { "Не удалось проверить обновления: ${it.message ?: "ошибка сети"}" to "" }
                }
                checking = false
                result = checkResult.first
                downloadUrl = checkResult.second
            }
        }
        result?.let { Text(it, color = Muted, fontSize = 10.sp) }
        downloadUrl.takeIf(String::isNotBlank)?.let { url ->
            SmallButton("Скачать обновление") {
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            }
        }
    }
}

private data class DesktopUpdateOffer(
    val version: String,
    val code: Int,
    val currentVersion: String,
    val currentCode: Int,
    val downloadUrl: String,
)

private fun fetchDesktopUpdateOffer(): DesktopUpdateOffer? = runCatching {
    val lines = readGithubText("version.txt")
    val version = lines.getOrNull(0)?.trim().orEmpty()
    val code = lines.getOrNull(1)?.trim()?.toIntOrNull()
    check(version.isNotBlank() && code != null) { "Неверный формат version.txt" }
    val current = Platform.appVersion
    if (version == current.first && code == current.second) return null
    val downloadUrl = readGithubText("downloadlinks.txt").getOrNull(1)?.trim().orEmpty()
    DesktopUpdateOffer(version, code, current.first, current.second, downloadUrl)
}.getOrNull()

private fun readGithubText(file: String): List<String> =
    URL("https://raw.githubusercontent.com/LimeHi/LimeNet/refs/heads/main/$file")
        .openConnection().apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Cache-Control", "no-cache")
        }.getInputStream().bufferedReader().use { it.readLines() }

@Composable
private fun RuleEditor(controller: Controller) {
    var value by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(RuleKind.DOMAIN) }
    var action by remember { mutableStateOf(RuleAction.DIRECT) }

    Column {
        controller.settings.customRules.forEachIndexed { index, rule ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${rule.kind.name}:${rule.value}",
                    color = TextMain,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (rule.action) {
                        RuleAction.PROXY -> "VPN"
                        RuleAction.DIRECT -> "напрямую"
                        RuleAction.BLOCK -> "блок"
                    },
                    color = when (rule.action) {
                        RuleAction.PROXY -> Accent
                        RuleAction.DIRECT -> Muted
                        RuleAction.BLOCK -> Danger
                    },
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "×",
                    color = Danger,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        controller.updateSettings {
                            it.copy(customRules = it.customRules.filterIndexed { i, _ -> i != index })
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            RuleKind.entries.forEach { entry ->
                Chip(entry.name, kind == entry) { kind = entry }
                Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        val compact = LocalCompact.current
        val field: @Composable (Modifier) -> Unit = { mod ->
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(Radii.m),
                value = value,
                onValueChange = { value = it },
                modifier = mod,
                singleLine = true,
                placeholder = { Text("google.com, example.org, 10.0.0.0/8", fontSize = 11.sp, maxLines = 1) },
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
        val actionChips: @Composable () -> Unit = {
            RuleAction.entries.forEach { entry ->
                Chip(
                    when (entry) {
                        RuleAction.PROXY -> "VPN"
                        RuleAction.DIRECT -> "напрямую"
                        RuleAction.BLOCK -> "блок"
                    },
                    action == entry,
                ) { action = entry }
                Spacer(Modifier.width(4.dp))
            }
        }
        val addRule = {
            val values = parseRoutingRuleValues(value)
            controller.updateSettings {
                it.copy(customRules = it.customRules + values.map { item -> RoutingRule(kind, item, action) })
            }
            value = ""
        }
        if (compact) {
            field(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                actionChips()
                Spacer(Modifier.weight(1f))
                SmallButton("Добавить", enabled = value.isNotBlank(), onClick = addRule)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                field(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                actionChips()
                SmallButton("Добавить", enabled = value.isNotBlank(), onClick = addRule)
            }
        }
    }
}

@Composable
private fun SplitList(
    title: String,
    items: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    Column {
        Text(title, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        if (items.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                items.forEach { item ->
                    Box(
                        Modifier.clip(RoundedCornerShape(Radii.m))
                            .background(Panel2)
                            .clickable { onRemove(item) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("$item  ×", color = TextMain, fontSize = 10.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        val compact = LocalCompact.current
        val field: @Composable (Modifier) -> Unit = { mod ->
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(Radii.m),
                value = input,
                onValueChange = { input = it },
                modifier = mod,
                singleLine = true,
                placeholder = { Text("chrome.exe", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (input.isNotBlank()) onAdd(input.trim())
                    input = ""
                }),
            )
        }
        val buttons: @Composable () -> Unit = {
            SmallButton("Добавить", enabled = input.isNotBlank()) {
                onAdd(input.trim())
                input = ""
            }
            Spacer(Modifier.width(8.dp))
            SmallButton("Список") { suggestions = Platform.runningApps() }
        }
        if (compact) {
            field(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { buttons() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                field(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                buttons()
            }
        }

        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                suggestions.take(40).forEach { app ->
                    Box(
                        Modifier.clip(RoundedCornerShape(Radii.m))
                            .background(Panel)
                            .clickable {
                                onAdd(app)
                                suggestions = suggestions - app
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(app, color = Muted, fontSize = 9.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ детали

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(title, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Как и чем проверять серверы: метод (URL / TCP), готовые URL кнопками и свой адрес. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestSettings(controller: Controller) {
    val settings = controller.settings

    Text("Метод проверки", color = Muted, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("URL-тест", settings.testMethod == TestMethod.URL) {
            controller.updateSettings { it.copy(testMethod = TestMethod.URL) }
        }
        Chip("TCP-тест", settings.testMethod == TestMethod.TCP) {
            controller.updateSettings { it.copy(testMethod = TestMethod.TCP) }
        }
        Chip("TCP + URL", settings.testMethod == TestMethod.TCP_URL) {
            controller.updateSettings { it.copy(testMethod = TestMethod.TCP_URL) }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        if (settings.testMethod == TestMethod.URL) {
            "URL-тест: поднимается временное ядро и через узел идёт запрос к адресу ниже — так видно, что узел реально пропускает трафик. Медленнее."
        } else if (settings.testMethod == TestMethod.TCP_URL) {
            "TCP + URL: сначала быстрая проверка порта, затем URL-запрос через ядро только для доступных узлов. Недоступные и истёкшие по тайм-ауту отмечаются отдельно."
        } else {
            "TCP-тест: только время подключения к порту сервера. Быстро, но не проверяет, что трафик проходит."
        },
        color = Muted,
        fontSize = 10.sp,
    )

    Spacer(Modifier.height(12.dp))
    Text("URL для проверки", color = Muted, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TEST_URL_PRESETS.forEach { (label, url) ->
            Chip(label, settings.testUrl == url) {
                controller.updateSettings { it.copy(testUrl = url) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    SettingField(
        label = "Свой URL",
        value = settings.testUrl,
        placeholder = DEFAULT_TEST_URL,
    ) { value ->
        controller.updateSettings { it.copy(testUrl = value.ifBlank { DEFAULT_TEST_URL }) }
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    placeholder: String = "",
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            colors = glassFieldColors(),
            shape = RoundedCornerShape(Radii.m),
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged {
                    if (focused && !it.isFocused && text.trim() != value) onCommit(text.trim())
                    focused = it.isFocused
                },
            singleLine = true,
            placeholder = { Text(placeholder, fontSize = 11.sp) },
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(text.trim()) }),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 12.sp)
            Text(description, color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentDark,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.20f),
            ),
        )
    }
}

@Composable
private fun EmptyState(
    title: String,
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().fillMaxHeight().padding(screenPad()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            GlassButton(action, accent = true, onClick = onAction)
        }
    }
}

@Composable
internal fun Chip(label: String, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.m)
    val accent by animateFloatAsState(if (active) 1f else 0f, tween(220), label = "chip-accent")
    Box(
        Modifier
            .pressable(shape, onClick = onClick)
            .glass(shape, accent = accent)
            .heightIn(min = touchMin())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = androidx.compose.ui.graphics.lerp(Muted, AccentDark, accent),
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
internal fun SmallButton(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> Muted.copy(alpha = 0.5f)
        danger -> Danger
        else -> TextMain
    }
    val shape = RoundedCornerShape(Radii.m)
    Box(
        Modifier
            .pressable(shape, enabled, onClick = onClick)
            .glass(shape)
            .heightIn(min = touchMin())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 11.sp)
    }
}

/** Крупная стеклянная кнопка; [accent] — главное действие на экране. */
@Composable
internal fun GlassButton(
    label: String,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.m)
    Box(
        Modifier
            .pressable(shape, enabled, onClick = onClick)
            .glass(shape, accent = if (accent && enabled) 1f else 0f)
            .heightIn(min = if (LocalCompact.current) 44.dp else 0.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> Muted.copy(alpha = 0.5f)
                accent -> AccentDark
                else -> TextMain
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Поля ввода: стеклянная подложка и акцентная обводка при фокусе. */
@Composable
private fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = 0.07f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
    focusedBorderColor = Accent.copy(alpha = 0.8f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
    cursorColor = Accent,
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
    focusedPlaceholderColor = Muted,
    unfocusedPlaceholderColor = Muted,
)

/**
 * Флаг страны. На Windows в системных шрифтах нет флагов-эмодзи, поэтому
 * рисуем готовую картинку, а если её нет — падаем обратно на эмодзи.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun FlagIcon(code: String?, size: Dp = 18.dp) {
    if (code.isNullOrBlank()) return
    val painter = Res.allDrawableResources["flag_${code.lowercase()}"]
    if (painter != null) {
        Image(
            painter = painterResource(painter),
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(Radii.s)),
        )
    } else {
        Text(Flags.emoji(code), fontSize = size.value.sp)
    }
}

/** Флаг + название узла без кода страны. */
@Composable
private fun NodeTitle(
    node: ProxyNode,
    fontSize: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FlagIcon(node.country)
        if (node.country != null) Spacer(Modifier.width(8.dp))
        Text(
            cleanServerName(node.shortName),
            color = color,
            fontSize = fontSize.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


