package com.limeprod.limenet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.util.lerp
import app.limenet.core.Flags
import app.limenet.core.Format
import app.limenet.core.Mode
import app.limenet.core.ProxyNode
import app.limenet.core.QrConfigPayload
import app.limenet.core.model.DnsMode
import app.limenet.core.model.RuleAction
import app.limenet.core.model.RuleKind
import app.limenet.core.model.RoutingRule
import app.limenet.core.model.parseRoutingRuleValues
import app.limenet.core.model.RoutingPreset
import app.limenet.core.model.TEST_URL_PRESETS
import app.limenet.core.model.TestMethod
import app.limenet.core.model.UserAgentPreset
import app.limenet.core.model.POPULAR_SERVICES
import app.limenet.ui.dial.DIAL_SEGMENTS
import app.limenet.ui.dial.DialSlot
import app.limenet.ui.dial.DialStatus
import app.limenet.ui.dial.LimeDial
import app.limenet.ui.theme.GlassBackdrop
import app.limenet.ui.theme.LimeColors
import app.limenet.ui.theme.LimeTheme
import app.limenet.ui.theme.Radii
import app.limenet.ui.theme.Spacing
import app.limenet.ui.theme.glass
import app.limenet.ui.theme.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

private val TextMain = LimeColors.Text
private val Muted = LimeColors.TextSecondary
private val Accent = LimeColors.Accent
private val AccentDark = LimeColors.Bg
private val Danger = LimeColors.Error
private val Warn = LimeColors.Text

// ---------------------------------------------------------------------- корень

@Composable
fun MobileApp(controller: AndroidController, deepLink: String?) {
    val context = LocalContext.current
    var showTutorial by remember {
        mutableStateOf(
            !context.getSharedPreferences("limenet", Context.MODE_PRIVATE)
                .getBoolean("android_tutorial_seen", false),
        )
    }
    var tutorialStep by remember { mutableStateOf(0) }
    var showList by remember { mutableStateOf(false) }
    var listLayerVisible by remember { mutableStateOf(false) }
    var dialCenter by remember { mutableStateOf(Offset.Unspecified) }
    val listProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(MobileTab.CONNECT) }
    var updateOffer by remember { mutableStateOf<MobileUpdateOffer?>(null) }

    LaunchedEffect(showTutorial) {
        if (!showTutorial) {
            delay(1_000)
            updateOffer = withContext(Dispatchers.IO) { fetchMobileUpdateOffer() }
        }
    }

    LaunchedEffect(showList) {
        if (showList) {
            listLayerVisible = true
            listProgress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
        } else {
            listProgress.animateTo(0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium))
            listLayerVisible = false
        }
    }

    fun closeServerList() {
        showList = false
    }

    LaunchedEffect(deepLink) {
        if (!deepLink.isNullOrBlank()) controller.handleDeepLink(deepLink)
    }

    BackHandler(enabled = showList || showAdd || tab != MobileTab.CONNECT) {
        when {
            showAdd -> showAdd = false
            showList -> showList = false
            else -> tab = MobileTab.CONNECT
        }
    }

    LimeTheme {
        Box(Modifier.fillMaxSize().background(LimeColors.Bg)) {
            GlassBackdrop(
                shift = {
                    when {
                        tab == MobileTab.SETTINGS -> 2f
                        tab == MobileTab.SUBSCRIPTIONS -> 1f
                        showList -> 1f
                        else -> 0f
                    }
                },
            )

            Column(
                Modifier.fillMaxSize().safeDrawingPadding().graphicsLayer {
                    val progress = if (listLayerVisible) listProgress.value.coerceIn(0f, 1f) else 0f
                    val scale = 1f - 0.06f * progress
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.5f * progress
                },
            ) {
                TopBar(controller, tab) { tab = it }
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        // Поведение близко к desktop pager: упругий spring вместо
                        // фиксированного медленного сдвига на 380 мс.
                        val spec = spring<IntOffset>(
                            dampingRatio = 0.9f,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                        if (targetState.ordinal > initialState.ordinal) {
                            (slideInHorizontally(spec) { it / 3 } + fadeIn(tween(160))) togetherWith
                                (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(120)))
                        } else {
                            (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(160))) togetherWith
                                (slideOutHorizontally(spec) { it / 3 } + fadeOut(tween(120)))
                        }
                    },
                    label = "mobile-main-tabs",
                ) { targetTab ->
                    when (targetTab) {
                        MobileTab.CONNECT -> DialScreen(
                            controller = controller,
                            onOpenList = { showList = true },
                            onDialCenter = { dialCenter = it },
                        )
                        MobileTab.SUBSCRIPTIONS -> SubscriptionsScreen(
                            controller = controller,
                            onAdd = { showAdd = true },
                        )
                        MobileTab.SETTINGS -> SettingsScreen(controller) {
                            tutorialStep = 0
                            showTutorial = true
                        }
                    }
                }
            }

            if (listLayerVisible) {
                val progress = listProgress.value.coerceIn(0f, 1f)
                ServerSheet(
                    controller = controller,
                    onClose = ::closeServerList,
                    onAddSubscription = { showAdd = true },
                    progress = { listProgress.value },
                    origin = dialCenter,
                )
            }
            if (showAdd) {
                AddSubscriptionSheet(
                    controller = controller,
                    onClose = { showAdd = false },
                )
            }

            if (showTutorial) {
                AndroidTutorial(step = tutorialStep, onStep = { tutorialStep = it }, onFinish = {
                    context.getSharedPreferences("limenet", Context.MODE_PRIVATE)
                        .edit().putBoolean("android_tutorial_seen", true).apply()
                    showTutorial = false
                })
            }

            updateOffer?.let { offer ->
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.18f))
                        .clickable { updateOffer = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl)
                            .clickable { },
                        color = LimeColors.Surface,
                        shape = RoundedCornerShape(Radii.l),
                    ) {
                        Column(Modifier.padding(Spacing.xl)) {
                            Text(
                                "Доступна другая версия LimeNet",
                                color = TextMain,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(Spacing.m))
                            Text(
                                "На GitHub указана версия ${offer.version} (${offer.code}); установлена ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}).",
                                color = Muted,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                            )
                            Spacer(Modifier.height(Spacing.xl))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SmallButton("Позже") { updateOffer = null }
                                if (offer.downloadUrl.isNotBlank()) {
                                    SmallButton("Скачать") {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(offer.downloadUrl))) }
                                        updateOffer = null
                                    }
                                }
                            }
                        }
                    }
                }
            }

            MessagePill(controller, Modifier.align(Alignment.TopCenter))
        }
    }
}

private enum class MobileTab(val label: String) {
    CONNECT("подключение"),
    SUBSCRIPTIONS("подписки"),
    SETTINGS("настройки"),
}

// ---------------------------------------------------------------- главный экран

@Composable
private fun DialScreen(
    controller: AndroidController,
    onOpenList: () -> Unit,
    onDialCenter: (Offset) -> Unit,
) {
    var dialPickId by remember { mutableStateOf<String?>(null) }

    val allNodes = controller.nodes
    val favIds = MobileFavorites.ids
    val selectedId = controller.selectedId
    val pings = controller.pings

    LaunchedEffect(controller.connected, selectedId, controller.busy) {
        if (controller.connected && !controller.busy) controller.checkPopularServices()
        else controller.cancelPopularServiceChecks()
    }

    val sub = selectedId?.let(controller::subscriptionOf)
        ?: controller.subscriptions.firstOrNull { it.id == controller.settings.activeSubscriptionId }
        ?: controller.subscriptions.firstOrNull { controller.nodeCount(it.id) > 0 }

    val candidates = remember(sub?.id, sub?.updatedAt, allNodes) {
        if (sub == null) emptyList() else controller.nodesOf(sub.id)
            .filter { it.supported && it.autoBalancerTag == null }
    }

    // Без избранного предпочитаем узлы с меньшим пингом; остаток списка случайный.
    val epoch = sub?.id to sub?.updatedAt
    var topEpoch by remember { mutableStateOf<Any?>(null) }
    var topIds by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(epoch) {
        if (epoch != topEpoch) {
            topEpoch = epoch
            topIds = null
        }
    }
    val useTop = favIds.isEmpty()
    val ready = candidates.isNotEmpty() && candidates.all { it.id in pings }
    LaunchedEffect(useTop, epoch, ready) {
        if (!useTop || topIds != null || candidates.isEmpty()) return@LaunchedEffect
        if (!ready) {
            delay(6_000)
            if (candidates.none { it.id in controller.pings }) return@LaunchedEffect
        }
        topIds = candidates.sortedBy { controller.latencyOf(it.id) ?: Int.MAX_VALUE }
            .take(DIAL_SEGMENTS)
            .map { it.id }
    }

    val ordered: List<ProxyNode?> = remember(favIds, topIds, candidates, allNodes) {
        val byId = allNodes.associateBy { it.id }
        val favorites = favIds.mapNotNull { byId[it] }.filter { it.supported }
        val preferred = if (favIds.isEmpty()) {
            val candidateById = candidates.associateBy { it.id }
            topIds.orEmpty().mapNotNull { candidateById[it] }
        } else favorites
        val usedIds = preferred.mapTo(mutableSetOf()) { it.id }
        val auto = sub?.autoBalancerTag?.let { tag -> controller.nodesOf(sub.id).firstOrNull { it.autoBalancerTag == tag } }
        val list = (listOfNotNull(auto) + preferred + candidates.filter { it.id !in usedIds }.shuffled())
            .distinctBy { it.id }
            .take(DIAL_SEGMENTS)
        List(DIAL_SEGMENTS) { list.getOrNull(it) }
    }

    LaunchedEffect(selectedId) {
        if (dialPickId == selectedId) dialPickId = null
    }
    LaunchedEffect(dialPickId, controller.connected, controller.screenPaused, controller.busy) {
        val id = dialPickId ?: return@LaunchedEffect
        if (controller.busy || id == selectedId) return@LaunchedEffect
        delay(250)
        if (dialPickId == id && !controller.busy) {
            dialPickId = null
            controller.selectNode(id)
        }
    }

    val displayId = dialPickId ?: selectedId
    val selectedIndex = ordered.indexOfFirst { it?.id == displayId }
    val current = displayId?.let { id -> allNodes.firstOrNull { it.id == id } } ?: controller.selected
    val currentSlot = current?.let { node -> toSlot(node, controller) }
    val switchPending = controller.connected && dialPickId != null && dialPickId != selectedId

    Column(Modifier.fillMaxSize()) {
        ServerTitle(current)

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.l),
            contentAlignment = Alignment.Center,
        ) {
            val slots: List<DialSlot?> = ordered.map { node -> node?.let { toSlot(it, controller) } }
            LimeDial(
                slots = slots,
                selectedIndex = selectedIndex,
                current = currentSlot,
                status = when {
                    controller.busy || controller.connecting -> DialStatus.CONNECTING
                    controller.connected || controller.screenPaused -> DialStatus.ON
                    else -> DialStatus.OFF
                },
                trafficLeft = sub?.let {
                    if (it.total > 0) (1f - it.used.toFloat() / it.total.toFloat()).coerceIn(0f, 1f) else 1f
                } ?: 1f,
                switchPending = switchPending,
                onSelect = { index ->
                    val id = ordered.getOrNull(index)?.id
                    if (id != null && id != controller.selectedId) {
                        if (controller.connected || controller.screenPaused) {
                            dialPickId = id
                        } else {
                            dialPickId = null
                            controller.selectNode(id)
                        }
                    }
                },
                onCenterTap = controller::toggleConnection,
                onCenterLongPress = onOpenList,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f)
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInRoot()
                        onDialCenter(
                            Offset(
                                position.x + coordinates.size.width / 2f,
                                position.y + coordinates.size.height / 2f,
                            ),
                        )
                    },
                emptyText = if (useTop && candidates.isNotEmpty() && topIds == null) "замеряю…" else "нет серверов",
                onEmptySlotTap = onOpenList,
            )
        }

        DialChips(controller)
        if (controller.connected && !controller.busy) PopularServicesPanel(controller)
        DialFooter(controller)
    }
}

@Composable
private fun PopularServicesPanel(controller: AndroidController) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs)
            .glass(RoundedCornerShape(Radii.m)).padding(horizontal = Spacing.s, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("сервисы через VPN", color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
            Text(
                if (controller.serviceCheckRunning) "проверяю…" else "обновить",
                color = Accent,
                fontSize = 9.sp,
                modifier = Modifier.clickable { controller.checkPopularServices() }.padding(horizontal = Spacing.xs),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            POPULAR_SERVICES.forEach { service ->
                val result = controller.serviceResults[service.id]
                val value = when {
                    result?.latencyMs != null -> "${result.latencyMs} мс"
                    result != null -> "нет связи"
                    controller.serviceCheckRunning -> "…"
                    else -> "—"
                }
                val color = when {
                    result?.latencyMs != null -> Accent
                    result != null -> Danger
                    else -> Muted
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(service.title, color = Muted, fontSize = 8.sp, maxLines = 1)
                    Text(value, color = color, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "скорость VPN: ${controller.speedTestResult?.speedBps?.let(Format::speed) ?: "—"}",
                color = controller.speedTestResult?.speedBps?.let { Accent } ?: Muted,
                fontSize = 9.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    controller.speedTestRunning -> "замер…"
                    controller.speedTestResult?.speedBps != null -> "повторить"
                    controller.speedTestResult != null -> "ошибка · повторить"
                    else -> "спидтест"
                },
                color = Accent,
                fontSize = 9.sp,
                modifier = Modifier.clickable(enabled = !controller.speedTestRunning) {
                    controller.runSpeedTest()
                }.padding(horizontal = Spacing.xs),
            )
        }
    }
}

@Composable
private fun TopBar(controller: AndroidController, selected: MobileTab, onSelect: (MobileTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "LimeNet",
            color = TextMain,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(Spacing.xs))
        Box(
            Modifier.size(8.dp).background(
                if (controller.connected || controller.screenPaused) Accent else Muted,
                RoundedCornerShape(50),
            ),
        )
        Spacer(Modifier.width(Spacing.xs))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            MobileTab.entries.forEach { tab ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Chip(tab.label, selected == tab) { onSelect(tab) }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(LimeColors.Line))
}

@Composable
private fun ServerTitle(node: ProxyNode?) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = 460.dp)
                .glass(RoundedCornerShape(Radii.l))
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node?.country?.let {
                Text(Flags.emoji(it), fontSize = 16.sp)
                Spacer(Modifier.width(Spacing.s))
            }
            Text(
                node?.shortName ?: "сервер не выбран",
                color = if (node == null) Muted else TextMain,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DialChips(controller: AndroidController) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TUN · системный VPN", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(Spacing.s))
        TestAllButton(controller)
        if (controller.testing) {
            Spacer(Modifier.height(Spacing.s))
            CheckProgress(controller)
        }
    }
}

@Composable
private fun TestAllButton(controller: AndroidController) {
    val running = controller.testing
    val total = controller.checkTotal
    val done = controller.checkDone
    val shape = RoundedCornerShape(Radii.m)
    Row(
        Modifier
            .pressable(
                shape = shape,
                enabled = running || controller.nodes.isNotEmpty(),
                onClick = { if (running) controller.cancelCheck() else controller.testAll() },
            )
            .glass(shape)
            .then(if (running) Modifier.mobileScanSweep(strength = 0.2f) else Modifier)
            .heightIn(min = 40.dp)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            MobileSpinnerRing()
            Spacer(Modifier.width(Spacing.xs))
        }
        val testLabel = when (controller.settings.testMethod) {
            TestMethod.URL -> "URL-тест"
            TestMethod.TCP -> "TCP-тест"
            TestMethod.TCP_URL -> "TCP + URL"
        }
        Text(if (running) "остановить" else testLabel, color = if (running) Accent else TextMain, fontSize = 11.sp)
        if (running && total > 0) {
            Spacer(Modifier.width(Spacing.xs))
            Text("$done/$total", color = Accent, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CheckProgress(controller: AndroidController) {
    val total = controller.checkTotal
    val done = controller.checkDone
    val fraction by animateFloatAsState(
        targetValue = if (total > 0) done.toFloat() / total else 0f,
        animationSpec = tween(300),
        label = "mobile-check-fraction",
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MobileSpinnerRing(diameter = 11.dp)
        Spacer(Modifier.width(Spacing.xs))
        Text(
            "проверяю серверы",
            color = Muted,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f),
        )
        Text("$done/$total", color = Accent, fontSize = 10.sp)
    }
    Spacer(Modifier.height(Spacing.xs))
    Box(
        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(Spacing.xs))
            .background(LimeColors.Line).mobileScanSweep(color = Color.White, strength = 0.26f),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(Accent))
    }
}

@Composable
private fun MobileSpinnerRing(diameter: androidx.compose.ui.unit.Dp = 12.dp) {
    val transition = rememberInfiniteTransition(label = "mobile-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "mobile-spinner-angle",
    )
    Canvas(Modifier.size(diameter)) {
        val stroke = 1.5.dp.toPx()
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        drawArc(Accent.copy(alpha = 0.22f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(Accent, angle, 96f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

private fun Modifier.mobileScanSweep(color: Color = Accent, strength: Float = 0.16f): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "mobile-scan")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "mobile-scan-phase",
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

@Composable
private fun DialFooter(controller: AndroidController) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LimeColors.Line))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mono = TextStyle(fontFamily = LimeTheme.fonts.mono, fontSize = 11.sp, color = Muted)
        Text("узлов ${controller.nodes.size}", style = mono, modifier = Modifier.weight(1f))
        val protocol = controller.selected?.protocol?.wire ?: "—"
        val mode = if (controller.settings.mode == Mode.TUN) "tun" else "proxy"
        Text("$protocol · $mode", style = mono)
        Spacer(Modifier.width(Spacing.m))
        SessionClock(controller.connected, mono, Modifier.weight(1f))
    }
}

@Composable
private fun SessionClock(connected: Boolean, style: TextStyle, modifier: Modifier = Modifier) {
    var startedAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(0L) }
    LaunchedEffect(connected, VpnBridge.startedAt) {
        if (!connected) {
            startedAt = 0L
            now = 0L
            return@LaunchedEffect
        }
        startedAt = if (VpnBridge.startedAt > 0) VpnBridge.startedAt else System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text = if (connected && startedAt > 0L) {
        val sec = ((now - startedAt) / 1000).coerceAtLeast(0)
        "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    } else {
        "—"
    }
    Text(text, style = style, modifier = modifier)
}

// ------------------------------------------------------------------ все серверы

@Composable
private fun ServerSheet(
    controller: AndroidController,
    onClose: () -> Unit,
    onAddSubscription: () -> Unit,
    progress: () -> Float,
    origin: Offset,
) {
    var query by remember { mutableStateOf("") }
    var scope by remember {
        mutableStateOf(
            controller.selectedId?.let(controller::subscriptionOf)?.id
                ?: controller.settings.activeSubscriptionId
                ?: controller.subscriptions.firstOrNull { controller.nodeCount(it.id) > 0 }?.id,
        )
    }
    var menuNode by remember { mutableStateOf<ProxyNode?>(null) }
    var qrNode by remember { mutableStateOf<ProxyNode?>(null) }
    var menuSub by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalContext.current.clipboard()

    val subscriptions = controller.subscriptions
    LaunchedEffect(subscriptions, scope) {
        if (scope != null && subscriptions.none { it.id == scope }) {
            scope = controller.selectedId?.let(controller::subscriptionOf)?.id
                ?: subscriptions.firstOrNull { controller.nodeCount(it.id) > 0 }?.id
        }
    }

    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { layerSize = it }
            .graphicsLayer {
                val p = progress().coerceIn(0f, 1f)
                scaleX = lerp(0.12f, 1f, p)
                scaleY = lerp(0.12f, 1f, p)
                alpha = (p * 1.8f).coerceIn(0f, 1f)
                transformOrigin = if (origin.x.isFinite() && origin.y.isFinite() && layerSize.width > 0 && layerSize.height > 0) {
                    TransformOrigin(origin.x / layerSize.width, origin.y / layerSize.height)
                } else TransformOrigin.Center
                shape = RoundedCornerShape((96f * (1f - p)).dp)
                clip = true
            }
            .consumeTaps(),
    ) {
        GlassBackdrop(shift = { 1f })
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Spacing.l)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dy -> if (dy > 14f) onClose() }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallButton("‹ назад", onClick = onClose)
                Spacer(Modifier.width(Spacing.m))
                Text(
                    "Серверы",
                    color = TextMain,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(LimeColors.Line))

            GlassField(
                value = query,
                onChange = { query = it },
                placeholder = "поиск по названию или адресу",
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
            )

            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.l, top = Spacing.s, bottom = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(end = Spacing.s),
                ) {
                    item(key = "all-subscriptions") {
                        SubscriptionGroupTab(
                            title = "Все",
                            count = controller.nodes.size,
                            active = scope == null,
                            onSelect = { scope = null },
                        )
                    }
                    items(subscriptions, key = { it.id }) { sub ->
                        SubscriptionGroupTab(
                            title = sub.title.ifBlank { "Подписка" },
                            count = controller.nodeCount(sub.id),
                            active = scope == sub.id,
                            onSelect = { scope = sub.id },
                            onLongClick = { menuSub = sub.id },
                            onMenu = { menuSub = sub.id },
                        )
                    }
                }
                SmallButton("＋", onClick = onAddSubscription)
                Spacer(Modifier.width(Spacing.l))
            }

            val checkSubscriptionId = scope
                ?: controller.selectedId?.let(controller::subscriptionOf)?.id
                ?: controller.settings.activeSubscriptionId
                ?: subscriptions.firstOrNull()?.id
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                SmallButton(
                    if (checkSubscriptionId != null && controller.testing && controller.checkScope == checkSubscriptionId) "стоп подписку" else "проверить подписку",
                    enabled = checkSubscriptionId != null && (!controller.testing || controller.checkScope == checkSubscriptionId),
                ) {
                    if (controller.testing) controller.cancelCheck()
                    else checkSubscriptionId?.let(controller::testSubscription)
                }
                SmallButton(
                    if (controller.testing && controller.checkScope == null) "стоп все" else "проверить все",
                    enabled = !controller.testing || controller.checkScope == null,
                ) {
                    if (controller.testing) controller.cancelCheck() else controller.testAll()
                }
            }

            AnimatedContent(
                targetState = scope,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val spec = spring<IntOffset>(
                        dampingRatio = 0.88f,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                    (slideInHorizontally(spec) { it / 5 } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(spec) { -it / 6 } + fadeOut(tween(130)))
                },
                label = "subscription-server-groups",
            ) { targetScope ->
                val nodes = remember(controller.nodes, targetScope, query) {
                    controller.nodes
                        .filter { targetScope == null || controller.subscriptionOf(it.id)?.id == targetScope }
                        .filter {
                            query.isBlank() ||
                                it.displayName.contains(query, ignoreCase = true) ||
                                it.server.contains(query, ignoreCase = true)
                        }
                }
                if (nodes.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isNotBlank()) "Ничего не найдено" else "В этой группе пока нет серверов",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = Spacing.l, end = Spacing.l, bottom = Spacing.xxl,
                        ),
                    ) {
                        items(nodes, key = { it.id }) { node ->
                            NodeRow(
                                controller = controller,
                                node = node,
                                subtitle = null,
                                onTap = {
                                    if (node.supported) {
                                        controller.selectNode(node.id)
                                        onClose()
                                    } else {
                                        menuNode = node
                                    }
                                },
                                onMenu = { menuNode = node },
                            )
                        }
                    }
                }
            }
        }
    }

    menuNode?.let { node ->
        BottomMenu(
            title = node.shortName,
            onDismiss = { menuNode = null },
            actions = buildList<Pair<String, () -> Unit>> {
                add("выбрать" to {
                    controller.selectNode(node.id)
                    menuNode = null
                    onClose()
                })
                add("копировать ссылку" to {
                    clipboard.copy(node.raw)
                    controller.toast("Ссылка скопирована")
                    menuNode = null
                })
                add("показать QR-код" to {
                    qrNode = node
                    menuNode = null
                })
                add("копировать JSON" to {
                    clipboard.copy(controller.nodeConfigJson(node))
                    controller.toast("JSON скопирован")
                    menuNode = null
                })
                add(
                    (if (MobileFavorites.isFav(node.id)) "★ убрать с колеса" else "☆ на колесо") to {
                        if (!MobileFavorites.toggle(node.id)) {
                            controller.toast("На колесе уже ${MobileFavorites.MAX} серверов", error = true)
                        }
                        menuNode = null
                    },
                )
            },
        )
    }

    qrNode?.let { node ->
        QrCodeDialog(node = node, onClose = { qrNode = null })
    }

    menuSub?.let { subId ->
        val sub = controller.subscription(subId)
        var rename by remember(subId) { mutableStateOf(sub?.title.orEmpty()) }
        BottomMenu(
            title = sub?.title ?: "Подписка",
            onDismiss = { menuSub = null },
            custom = {
                Column(Modifier.fillMaxWidth()) {
                    GlassField(
                        value = rename,
                        onChange = { rename = it },
                        placeholder = "название подписки",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.s))
                }
            },
            actions = listOf(
                "проверить подписку" to {
                    controller.testSubscription(subId)
                    menuSub = null
                },
                "обновить подписку" to {
                    controller.refreshSubscription(subId)
                    menuSub = null
                },
                "сохранить название" to {
                    controller.renameSubscription(subId, rename)
                    menuSub = null
                },
                "удалить подписку" to {
                    controller.removeSubscription(subId)
                    menuSub = null
                },
            ),
        )
    }
}

@Composable
private fun SubscriptionRow(
    controller: AndroidController,
    subId: String,
    active: Boolean,
    onSelect: () -> Unit,
    onMenu: () -> Unit,
) {
    val sub = controller.subscription(subId) ?: return
    val shape = RoundedCornerShape(Radii.l)
    val outcome = controller.refreshOutcomes[subId]
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs)
            .glass(shape, accent = if (active) 0.16f else 0f)
            .pressable(shape, depth = 0.99f, onLongClick = onMenu, onClick = onSelect)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                sub.title.ifBlank { "Подписка" },
                color = TextMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("узлов ${controller.nodeCount(subId)}")
                    if (sub.total > 0) append(" · ${Format.bytes(sub.used)}/${Format.bytes(sub.total)}")
                    if (sub.expire > 0) append(" · до ${Format.date(sub.expire)}")
                    when {
                        controller.updating == subId -> append(" · обновляю…")
                        outcome == true -> append(" · ок")
                        outcome == false -> append(" · ошибка")
                    }
                },
                color = Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("⋮", color = Muted, fontSize = 18.sp, modifier = Modifier.clickable { onMenu() })
    }
}

@Composable
private fun SubscriptionGroupTab(
    title: String,
    count: Int,
    active: Boolean,
    onSelect: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Radii.l)
    val accent by animateFloatAsState(
        targetValue = if (active) 0.20f else 0f,
        animationSpec = tween(220),
        label = "subscription-group-tab",
    )
    Row(
        Modifier.widthIn(min = 82.dp, max = 188.dp)
            .pressable(shape, depth = 0.98f, onLongClick = onLongClick, onClick = onSelect)
            .glass(shape, accent = accent)
            .heightIn(min = 44.dp)
            .padding(start = Spacing.m, end = Spacing.s, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                title,
                color = if (active) TextMain else Muted,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$count серверов",
                color = Muted.copy(alpha = 0.86f),
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
        if (onMenu != null) {
            Text(
                "⋮",
                color = Muted,
                fontSize = 17.sp,
                modifier = Modifier.clickable(onClick = onMenu),
            )
        }
    }
}

@Composable
private fun NodeRow(
    controller: AndroidController,
    node: ProxyNode,
    subtitle: String?,
    onTap: () -> Unit,
    onMenu: () -> Unit,
) {
    val selected = controller.selectedId == node.id
    val ping = controller.latencyOf(node.id)
    val measured = node.id in controller.pings || controller.urlResults.containsKey(node.id) || node.id in controller.tcpFailures
    val result = if (controller.settings.testMethod != TestMethod.TCP) controller.urlResults[node.id] else null
    val tcpFailure = controller.tcpFailures[node.id].takeIf { controller.settings.testMethod != TestMethod.URL }
    val queued = node.id in controller.checkQueue
    val scanning = node.id in controller.checkActive
    val shape = RoundedCornerShape(Radii.l)
    val fav = MobileFavorites.isFav(node.id)

    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .glass(shape)
            .background(if (selected) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (scanning) Modifier.mobileScanSweep() else Modifier)
            .pressable(shape, depth = 0.985f, onLongClick = onMenu, onClick = onTap)
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (node.autoBalancerTag != null) "AUTO" else node.protocol.wire.uppercase(),
            color = Muted,
            fontSize = 9.sp,
            modifier = Modifier.width(42.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                node.country?.let {
                    Text(Flags.emoji(it), fontSize = 13.sp)
                    Spacer(Modifier.width(Spacing.xs))
                }
                Text(
                    if (node.autoBalancerTag != null) "Авто" else node.shortName,
                    color = if (node.supported) TextMain else Muted,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fav) {
                    Spacer(Modifier.width(Spacing.xs))
                    Text("★", color = Accent, fontSize = 11.sp)
                }
            }
            val line = buildString {
                append(subtitle ?: if (node.autoBalancerTag != null) "Выбор сервера подписки" else node.server)
                if (controller.settings.showSpeed && controller.settings.testMethod != TestMethod.TCP) {
                    controller.urlResults[node.id]?.speedBps?.let { append(" · ${Format.speed(it)}") }
                }
            }
            Text(line, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.widthIn(min = 52.dp), contentAlignment = Alignment.CenterEnd) {
            Crossfade(
                targetState = if (scanning) 2 else if (queued) 1 else 0,
                animationSpec = tween(200),
                label = "mobile-node-ping-state",
            ) { phase ->
                when (phase) {
                    2 -> MobileSpinnerRing()
                    1 -> PendingDots()
                    else -> Text(
                        when {
                            node.autoBalancerTag != null -> "подписка"
                            !node.supported -> "нет движка"
                            result?.failure == app.limenet.core.model.TestFailure.TIMEOUT || tcpFailure == app.limenet.core.model.TestFailure.TIMEOUT -> "тайм-аут"
                            result?.error != null || tcpFailure != null -> "недоступен"
                            ping != null -> "$ping мс"
                            measured -> "нет"
                            else -> "—"
                        },
                        color = when {
                            node.autoBalancerTag != null -> Muted
                            !node.supported -> Danger
                            result?.failure != null || tcpFailure != null -> Danger
                            ping != null -> if (ping < 200) Accent else TextMain
                            measured -> Danger
                            else -> Muted
                        },
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.width(Spacing.s))
        Box(
            Modifier.size(36.dp).clickable { onMenu() },
            contentAlignment = Alignment.Center,
        ) {
            Text("⋮", color = Muted, fontSize = 18.sp)
        }
    }
}

// ------------------------------------------------------------------- настройки

@Composable
private fun SubscriptionsScreen(controller: AndroidController, onAdd: () -> Unit) {
    var catalogSelected by remember { mutableStateOf(false) }
    var catalogQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        Text("Подписки", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(Spacing.xs))
        Text("Ссылки и конфиги серверов, сохранённые на этом устройстве.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(Spacing.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SmallButton("＋ добавить", enabled = !controller.busy, onClick = onAdd)
            SmallButton("обновить все", enabled = !controller.updatingAll) { controller.updateAll() }
            SmallButton(if (controller.testing) "стоп" else "проверить все") {
                if (controller.testing) controller.cancelCheck() else controller.testAll()
            }
        }
        Spacer(Modifier.height(Spacing.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SmallButton(if (catalogSelected) "Мои подписки" else "Мои подписки · выбрано") { catalogSelected = false }
            SmallButton(if (catalogSelected) "Полезные подписки · выбрано" else "Полезные подписки") { catalogSelected = true }
        }
        Spacer(Modifier.height(Spacing.s))
        if (catalogSelected) {
            GlassField(catalogQuery, { catalogQuery = it }, "Поиск по подпискам", Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.s))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    controller.catalogError?.let {
                        if (controller.catalog.isNotEmpty()) "$it · показана сохранённая копия" else it
                    } ?: if (controller.catalogLoading) "Загрузка каталога…" else "Каталог GitHub",
                    color = if (controller.catalogError != null) Danger else Muted,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                SmallButton(if (controller.catalogLoading) "Загрузка…" else "Обновить") {
                    controller.refreshCatalog()
                }
            }
            Spacer(Modifier.height(Spacing.s))
            val filteredCatalog = controller.catalog.filter { entry ->
                catalogQuery.isBlank() || listOf(entry.name, entry.description, entry.url)
                    .any { it.contains(catalogQuery.trim(), ignoreCase = true) }
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
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
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(Radii.l)).padding(Spacing.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.name, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                if (entry.status == app.limenet.core.CatalogEntry.Status.FREE) "Бесплатно" else "Платно / Бот",
                                color = if (entry.status == app.limenet.core.CatalogEntry.Status.FREE) Accent else Muted,
                                fontSize = 10.sp,
                            )
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(entry.description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                        Spacer(Modifier.height(Spacing.s))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            SmallButton(if (entry.opensExternally) "Открыть бота" else "Добавить", enabled = !controller.busy) {
                                if (entry.opensExternally) {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url))) }
                                        .onFailure { controller.toast("Не удалось открыть ссылку", error = true) }
                                } else controller.addCatalogEntry(entry)
                            }
                            entry.channelUrl?.let { url ->
                                SmallButton("Канал") {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                        .onFailure { controller.toast("Не удалось открыть канал", error = true) }
                                }
                            }
                        }
                    }
                }
            }
        } else if (controller.subscriptions.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Подписок пока нет", color = Muted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                items(controller.subscriptions, key = { it.id }) { sub ->
                    var rename by remember(sub.id) { mutableStateOf(false) }
                    var title by remember(sub.id) { mutableStateOf(sub.title) }
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(Radii.l)).padding(Spacing.m)) {
                        if (rename) {
                            GlassField(title, { title = it }, "название подписки", Modifier.fillMaxWidth())
                        } else {
                            Text(sub.title.ifBlank { "Подписка" }, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        sub.url?.let {
                            Text(it, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "${controller.nodeCount(sub.id)} узлов" +
                                if (sub.total > 0) " · ${Format.bytes(sub.used)} / ${Format.bytes(sub.total)}" else "",
                            color = if (sub.total > 0 && sub.used >= sub.total) Danger else Muted,
                            fontSize = 11.sp,
                        )
                        if (sub.expire > 0) {
                            val expired = sub.expire <= System.currentTimeMillis() / 1000
                            Text("до ${Format.date(sub.expire)}", color = if (expired) Danger else Muted, fontSize = 10.sp)
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
                        if (sub.announce.isNotBlank()) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(sub.announce, color = Muted, fontSize = 10.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(Spacing.s))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            SmallButton(if (rename) "сохранить" else "переименовать", enabled = !rename || title.isNotBlank()) {
                                if (rename) controller.renameSubscription(sub.id, title.trim())
                                rename = !rename
                            }
                            SmallButton(
                                if (controller.isChecking(sub.id)) "стоп" else "проверить",
                                enabled = !controller.testing || controller.isChecking(sub.id),
                            ) {
                                if (controller.testing) controller.cancelCheck() else controller.testSubscription(sub.id)
                            }
                            SmallButton("обновить", enabled = controller.updating != sub.id) { controller.refreshSubscription(sub.id) }
                            SmallButton("удалить", danger = true) { controller.removeSubscription(sub.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(controller: AndroidController, onTutorial: () -> Unit) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }
    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val spec = tween<IntOffset>(300)
            if (targetState != null) {
                (slideInHorizontally(spec) { it / 3 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(spec) { it / 3 } + fadeOut(tween(160)))
            }
        },
        label = "mobile-settings-navigation",
    ) { current ->
        if (current == null) SettingsRoot(controller, onTutorial) { page = it }
        else SettingsDetail(controller, current) { page = null }
    }
}

private enum class SettingsPage(val title: String) {
    CHAIN("Цепочка узлов"),
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

private val ANDROID_TUTORIAL = listOf(
    "Добро пожаловать в LimeNet" to "Коротко покажем основные возможности. Это обучение можно открыть снова в настройках.",
    "Добавьте подписку" to "Откройте вкладку «Подписки», добавьте ссылку провайдера или одиночный vless://… конфиг. После загрузки серверы появятся на колесе.",
    "Выберите сервер" to "На колесе каждая долька — сервер. Нажмите на дольку, чтобы выбрать сервер. Зажмите центр колеса, чтобы открыть полный список и поиск.",
    "Подключитесь" to "Нажмите центральную кнопку колеса для подключения или отключения. Режим TUN направляет трафик устройства через системный VPN.",
    "Проверка и быстрый доступ" to "Кнопка под колесом проверяет серверы методом из настроек → стабильность. Добавьте плитку LimeNet в быстрые настройки Android через карандаш в шторке — оттуда можно подключить или отключить VPN.",
)

@Composable
private fun AndroidTutorial(step: Int, onStep: (Int) -> Unit, onFinish: () -> Unit) {
    val safeStep = step.coerceIn(0, ANDROID_TUTORIAL.lastIndex)
    val (title, description) = ANDROID_TUTORIAL[safeStep]
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f))
            .clickable(onClick = { /* модальное обучение не закрываем касанием фона */ }),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.l)
                .clip(RoundedCornerShape(Radii.l))
                .background(LimeColors.Surface)
                .glass(RoundedCornerShape(Radii.l))
                .padding(Spacing.l),
        ) {
            Text("${safeStep + 1} / ${ANDROID_TUTORIAL.size}", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(Spacing.m))
            Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.s))
            Text(description, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(Spacing.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton("Пропустить", onClick = onFinish)
                Spacer(Modifier.weight(1f))
                if (safeStep > 0) {
                    SmallButton("Назад") { onStep(safeStep - 1) }
                    Spacer(Modifier.width(Spacing.s))
                }
                SmallButton(
                    if (safeStep == ANDROID_TUTORIAL.lastIndex) "Понятно" else "Далее",
                    onClick = { if (safeStep == ANDROID_TUTORIAL.lastIndex) onFinish() else onStep(safeStep + 1) },
                )
            }
        }
    }
}

private fun settingsSummary(controller: AndroidController, page: SettingsPage): String {
    val s = controller.settings
    if (page == SettingsPage.CHAIN) return s.chainEntryNodeId?.let { id -> controller.nodes.firstOrNull { it.id == id }?.shortName }
        ?.let { "Через узел: $it" } ?: "Напрямую (Без цепочки)"
    if (page == SettingsPage.SPEED) return if (s.showSpeed) "Скорость отображается" else "Скорость скрыта"
    return when (page) {
        SettingsPage.MODE -> "TUN · системный VPN"
        SettingsPage.CHAIN -> s.chainEntryNodeId?.let { id -> controller.nodes.firstOrNull { it.id == id }?.shortName }
            ?.let { "Через узел: $it" } ?: "Напрямую (Без цепочки)"
        SettingsPage.SPEED -> if (s.showSpeed) "Скорость отображается" else "Скорость скрыта"
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
        SettingsPage.STABILITY -> "экран-пауза ${if (s.pauseVpnWithScreen) "вкл" else "выкл"} · kill switch ${if (s.killSwitch) "вкл" else "выкл"}"
        SettingsPage.APP_UPDATES -> "проверка версии LimeNet"
        SettingsPage.UPDATES -> buildList {
            add(if (s.autoUpdateHours == 0) "автообновление выкл" else "каждые ${s.autoUpdateHours} ч")
            if (s.updateSubscriptionsOnLaunch) add("проверка подписок при входе")
            if (s.pingServersOnLaunch) add("пинг при входе")
            if (s.batterySaver) add("экономия батареи")
        }.joinToString(" · ")
        SettingsPage.SPLIT -> "напрямую: ${s.splitTunnel.directApps.size} · через VPN: ${s.splitTunnel.proxyApps.size}"
        SettingsPage.ADMIN -> "разрешение VPN Android"
        SettingsPage.LOGS -> "журнал VPN и Xray"
    }
}

@Composable
private fun SettingsRoot(controller: AndroidController, onTutorial: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l)) {
        Text("Настройки", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(Spacing.m))
        SettingsPage.entries.forEach { page ->
            val shape = RoundedCornerShape(Radii.l)
            Row(
                Modifier.fillMaxWidth().padding(bottom = Spacing.s)
                    .pressable(shape, depth = 0.985f) { onOpen(page) }
                    .glass(shape)
                    .padding(horizontal = Spacing.m, vertical = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(page.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(settingsSummary(controller, page), color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("›", color = Muted, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(Spacing.s))
        SmallButton("Как пользоваться") { onTutorial() }
        Spacer(Modifier.height(Spacing.m))
        SmallButton("Наш Telegram · @LimeProdNews") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/LimeProdNews"))) }
        }
    }
}

@Composable
private fun SettingsDetail(controller: AndroidController, page: SettingsPage, onBack: () -> Unit) {
    val settings = controller.settings
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton("‹ назад", onClick = onBack)
            Spacer(Modifier.width(Spacing.m))
            Text(page.title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(Spacing.m))
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(Radii.l)).padding(Spacing.m)) {
            when (page) {
                SettingsPage.CHAIN -> {
                    Text("Первый узел соединяется с выбранным сервером. Выберите сохранённый узел, отличный от целевого.", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(Spacing.m))
                    Chip("Напрямую (Без цепочки)", settings.chainEntryNodeId == null) {
                        controller.updateSettings { it.copy(chainEntryNodeId = null) }
                    }
                    Spacer(Modifier.height(Spacing.s))
                    controller.nodes.filter { it.supported && it.id != settings.selectedNodeId }.forEach { entry ->
                        Chip("Через узел (Цепочка): ${entry.shortName}", settings.chainEntryNodeId == entry.id) {
                            controller.updateSettings { it.copy(chainEntryNodeId = entry.id) }
                        }
                        Spacer(Modifier.height(Spacing.xs))
                    }
                    if (controller.nodes.none { it.supported && it.id != settings.selectedNodeId }) {
                        Text("Нет других сохранённых узлов для цепочки.", color = Muted, fontSize = 10.sp)
                    }
                }
                SettingsPage.SPEED -> GlassToggle("Показывать скорость в списке серверов", settings.showSpeed) { value ->
                    controller.updateSettings { it.copy(showSpeed = value) }
                }
        SettingsPage.MODE -> {
                    Text("На телефоне используется только режим TUN: весь трафик устройства проходит через системный VPN.", color = Muted, fontSize = 11.sp)
                }
                SettingsPage.ROUTING -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Chip("Всё через VPN", settings.routing == RoutingPreset.ALL_PROXY) { controller.updateSettings { it.copy(routing = RoutingPreset.ALL_PROXY) } }
                        Chip("РФ напрямую", settings.routing == RoutingPreset.RU_DIRECT) { controller.updateSettings { it.copy(routing = RoutingPreset.RU_DIRECT) } }
                        Chip("Свои правила", settings.routing == RoutingPreset.CUSTOM) { controller.updateSettings { it.copy(routing = RoutingPreset.CUSTOM) } }
                    }
                    Spacer(Modifier.height(Spacing.s))
                    Text("Добавляйте списки доменов/IP и выбирайте маршрут для каждой записи: через VPN, напрямую или блокировать. Разделители — запятая, пробел или новая строка.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    if (settings.routing == RoutingPreset.CUSTOM) {
                        Spacer(Modifier.height(Spacing.m))
                        MobileRuleEditor(controller)
                    }
                }
                SettingsPage.DNS -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Chip("Системный", settings.dns.mode == DnsMode.SYSTEM) { controller.updateSettings { it.copy(dns = it.dns.copy(mode = DnsMode.SYSTEM)) } }
                        Chip("DoH", settings.dns.mode == DnsMode.DOH) { controller.updateSettings { it.copy(dns = it.dns.copy(mode = DnsMode.DOH)) } }
                        Chip("DoT", settings.dns.mode == DnsMode.DOT) { controller.updateSettings { it.copy(dns = it.dns.copy(mode = DnsMode.DOT)) } }
                    }
                    Section("Провайдер DNS · DoH")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        listOf(
                            "Google" to "https://dns.google/dns-query",
                            "Cloudflare" to "https://1.1.1.1/dns-query",
                            "Quad9" to "https://dns.quad9.net/dns-query",
                        ).forEach { (name, address) ->
                            Chip(name, settings.dns.remoteServer == address) {
                                controller.updateSettings { it.copy(dns = it.dns.copy(mode = DnsMode.DOH, remoteServer = address)) }
                            }
                        }
                    }
                    Section("Удалённый DNS")
                    GlassField(settings.dns.remoteServer, { value -> controller.updateSettings { it.copy(dns = it.dns.copy(remoteServer = value)) } }, "https://dns.google/dns-query", Modifier.fillMaxWidth())
                    Text("Для своего DoH/DoT-сервера укажите IP-адрес, чтобы Android мог настроить DNS для TUN.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                    Section("DNS для российских доменов")
                    GlassField(settings.dns.directServer, { value -> controller.updateSettings { it.copy(dns = it.dns.copy(directServer = value)) } }, "8.8.8.8", Modifier.fillMaxWidth())
                }
                SettingsPage.NETWORK -> {
                    Text("MTU пакета TUN. Авто (рекомендуется) оставляет стандартное поведение; уменьшайте только если сайты, звонки или игры зависают.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(Spacing.m))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        listOf("Авто" to null, "1500" to 1500, "1400" to 1400, "1280" to 1280).forEach { (label, value) ->
                            Chip(label, settings.tunMtu == value) {
                                controller.updateSettings { it.copy(tunMtu = value) }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.l))
                    GlassToggle("Отключать QUIC / HTTP3 (UDP 443)", settings.blockQuic) { value ->
                        controller.updateSettings { it.copy(blockQuic = value) }
                    }
                    Text("Если YouTube или другой сервис подвисает на нестабильном UDP, включите: приложение попробует перейти на TCP. Может снизить скорость.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                    Section("TLS-фрагментация")
                    Text("Не применяется текущим Android-ядром Xray. Не включаем фиктивный переключатель; добавим после интеграции поддерживающего ядра и отдельной проверки совместимости.", color = Muted, fontSize = 10.sp, lineHeight = 16.sp)
                }
                SettingsPage.STABILITY -> {
                    GlassToggle("Пауза VPN при выключенном экране", settings.pauseVpnWithScreen) { value ->
                        controller.updateSettings { it.copy(pauseVpnWithScreen = value) }
                    }
                    Text("Если VPN подключён, LimeNet при выключении экрана приостановит туннель и восстановит подключение при включении экрана. Пока туннель приостановлен, Android может разрешить трафик напрямую или заблокировать его — в зависимости от системной настройки «Всегда включенный VPN». Вручную отключённый VPN сам не запустится.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    GlassToggle("Блокировать утечки при обрыве (kill switch)", settings.killSwitch) { value -> controller.updateSettings { it.copy(killSwitch = value) } }
                    GlassToggle("Автопереключение при обрыве", settings.autoSwitch) { value -> controller.updateSettings { it.copy(autoSwitch = value) } }
                    Text("Перед каждым запуском LimeNet проверяет выбранный узел реальным запросом. Если он не отвечает, приложение попробует рабочий узел из той же подписки (если включено автопереключение).", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                    Section("Способ проверки")
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Chip("URL-тест", settings.testMethod == TestMethod.URL) { controller.updateSettings { it.copy(testMethod = TestMethod.URL) } }
                        Chip("TCP-тест", settings.testMethod == TestMethod.TCP) { controller.updateSettings { it.copy(testMethod = TestMethod.TCP) } }
                        Chip("TCP + URL", settings.testMethod == TestMethod.TCP_URL) { controller.updateSettings { it.copy(testMethod = TestMethod.TCP_URL) } }
                    }
                    Section("URL проверки")
                    TEST_URL_PRESETS.forEach { (name, url) ->
                        Chip(name, settings.testUrl == url) { controller.updateSettings { it.copy(testUrl = url) } }
                        Spacer(Modifier.height(Spacing.xs))
                    }
                    GlassField(settings.testUrl, { value -> controller.updateSettings { it.copy(testUrl = value) } }, "http://…/generate_204", Modifier.fillMaxWidth())
                }
                SettingsPage.APP_UPDATES -> MobileAppUpdateCheck()
                SettingsPage.UPDATES -> {
                    Section("Автообновление")
                    GlassToggle("Проверять подписки при входе", settings.updateSubscriptionsOnLaunch) { value ->
                        controller.updateSettings { it.copy(updateSubscriptionsOnLaunch = value) }
                    }
                    Text("Обновляются подписки, чей срок проверки по выбранному ниже интервалу уже наступил.", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.height(Spacing.s))
                    GlassToggle("Пинговать серверы при входе", settings.pingServersOnLaunch) { value ->
                        controller.updateSettings { it.copy(pingServersOnLaunch = value) }
                    }
                    Spacer(Modifier.height(Spacing.s))
                    GlassToggle("Экономия батареи", settings.batterySaver) { value ->
                        controller.updateSettings { it.copy(batterySaver = value) }
                    }
                    Text("Реже проверяет активное соединение и подписки; отключает автоматический пинг всех серверов при входе. VPN продолжает работать, но обнаружение обрыва может занять больше времени.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(Spacing.s))
                    Section("Интервал автообновления")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        listOf(0, 6, 12, 24, 48).forEach { hours ->
                            Chip(if (hours == 0) "Выкл" else "${hours} ч", settings.autoUpdateHours == hours) {
                                controller.updateSettings { it.copy(autoUpdateHours = hours) }
                            }
                        }
                    }
                    Section("Представляться серверу как")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        UserAgentPreset.entries.forEach { preset ->
                            Chip(preset.label, settings.userAgent == preset) { controller.updateSettings { it.copy(userAgent = preset) } }
                        }
                    }
                    Section("Привязка к устройству")
                    GlassToggle("Отправлять HWID в заголовках подписки", settings.hwid) { value -> controller.updateSettings { it.copy(hwid = value) } }
                    Text("${MobileHwid.value.take(8)}… · ${MobileHwid.os} ${MobileHwid.osVersion} · ${MobileHwid.model}", color = Muted, fontSize = 9.sp)
                    Section("Подписки")
                    SmallButton("обновить все", enabled = !controller.updatingAll) { controller.updateAll() }
                }
                SettingsPage.SPLIT -> {
                    MobileAppList("Напрямую (мимо VPN)", settings.splitTunnel.directApps) { next ->
                        controller.updateSettings { it.copy(splitTunnel = it.splitTunnel.copy(directApps = next)) }
                    }
                    Spacer(Modifier.height(Spacing.l))
                    MobileAppList("Только через VPN", settings.splitTunnel.proxyApps) { next ->
                        controller.updateSettings { it.copy(splitTunnel = it.splitTunnel.copy(proxyApps = next)) }
                    }
                    Spacer(Modifier.height(Spacing.s))
                    Text("Введите package name приложения. Изменения применяются при следующем запуске VPN.", color = Muted, fontSize = 10.sp)
                }
                SettingsPage.ADMIN -> {
                    Text("Android не требует прав администратора для VPN. При первом подключении подтвердите системный запрос на VPN-разрешение.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        when {
                            controller.connected -> "VPN сейчас подключён"
                            controller.screenPaused -> "VPN на паузе до включения экрана"
                            else -> "VPN сейчас не подключён"
                        },
                        color = if (controller.connected || controller.screenPaused) Accent else Muted,
                        fontSize = 12.sp,
                    )
                }
                SettingsPage.LOGS -> {
                    Text("Ядро: ${AndroidEnv.xrayBin.absolutePath}", color = Muted, fontSize = 9.sp)
                    Spacer(Modifier.height(Spacing.s))
                    LogTail("Журнал VPN") { AndroidEnv.tailVpnLog(40) }
                    Spacer(Modifier.height(Spacing.s))
                    LogTail("Лог ядра Xray") { AndroidEnv.tailLog(40) }
                }
            }
        }
        Spacer(Modifier.height(Spacing.xxl))
    }
}

@Composable
private fun MobileAppUpdateCheck() {
    val context = LocalContext.current
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
                        val remoteVersion = lines.getOrNull(0)?.trim().orEmpty()
                        val remoteCode = lines.getOrNull(1)?.trim()?.toIntOrNull()
                        check(remoteVersion.isNotBlank() && remoteCode != null) { "Неверный формат version.txt" }
                        if (remoteVersion == BuildConfig.VERSION_NAME && remoteCode == BuildConfig.VERSION_CODE) {
                            "Установлена версия GitHub (${BuildConfig.VERSION_NAME}, ${BuildConfig.VERSION_CODE})." to ""
                        } else {
                            val downloadUrl = readGithubText("downloadlinks.txt").getOrNull(0)?.trim().orEmpty()
                            ("На GitHub указана версия $remoteVersion ($remoteCode)." to downloadUrl)
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
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
    }
}

private data class MobileUpdateOffer(val version: String, val code: Int, val downloadUrl: String)

private suspend fun fetchMobileUpdateOffer(): MobileUpdateOffer? = runCatching {
    val lines = readGithubText("version.txt")
    val version = lines.getOrNull(0)?.trim().orEmpty()
    val code = lines.getOrNull(1)?.trim()?.toIntOrNull()
    check(version.isNotBlank() && code != null) { "Неверный формат version.txt" }
    if (version == BuildConfig.VERSION_NAME && code == BuildConfig.VERSION_CODE) return null
    val url = readGithubText("downloadlinks.txt").getOrNull(0)?.trim().orEmpty()
    MobileUpdateOffer(version, code, url)
}.getOrNull()

private fun readGithubText(file: String): List<String> =
    URL("https://raw.githubusercontent.com/LimeHi/LimeNet/refs/heads/main/$file")
        .openConnection().apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Cache-Control", "no-cache")
        }.getInputStream().bufferedReader().use { it.readLines() }

@Composable
private fun MobileRuleEditor(controller: AndroidController) {
    var value by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(RuleKind.DOMAIN) }
    var action by remember { mutableStateOf(RuleAction.PROXY) }
        controller.settings.customRules.forEachIndexed { index, rule ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text("${rule.kind.name}:${rule.value} · ${when (rule.action) { RuleAction.PROXY -> "через VPN"; RuleAction.DIRECT -> "напрямую"; RuleAction.BLOCK -> "блокировать" }}", color = TextMain, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("×", color = Danger, modifier = Modifier.clickable {
                controller.updateSettings { it.copy(customRules = it.customRules.filterIndexed { i, _ -> i != index }) }
            })
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        RuleKind.entries.forEach { entry -> Chip(entry.name, kind == entry) { kind = entry } }
    }
    Spacer(Modifier.height(Spacing.s))
    GlassField(value, { value = it }, "google.com, example.org, 10.0.0.0/8", Modifier.fillMaxWidth())
    Spacer(Modifier.height(Spacing.s))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        RuleAction.entries.forEach { entry ->
            Chip(
                when (entry) {
                    RuleAction.PROXY -> "через VPN"
                    RuleAction.DIRECT -> "напрямую"
                    RuleAction.BLOCK -> "блокировать"
                },
                action == entry,
            ) { action = entry }
        }
        SmallButton("добавить", enabled = value.isNotBlank()) {
            val values = parseRoutingRuleValues(value)
            controller.updateSettings { it.copy(customRules = it.customRules + values.map { item -> RoutingRule(kind, item, action) }) }
            value = ""
        }
    }
}

@Composable
private fun MobileAppList(title: String, apps: List<String>, onChange: (List<String>) -> Unit) {
    var value by remember(title) { mutableStateOf("") }
    Section(title)
    apps.forEach { app ->
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(app, color = TextMain, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("×", color = Danger, modifier = Modifier.clickable { onChange(apps - app) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        GlassField(value, { value = it }, "com.example.app", Modifier.weight(1f))
        SmallButton("добавить", enabled = value.isNotBlank()) {
            val app = value.trim()
            if (app !in apps) onChange(apps + app)
            value = ""
        }
    }
}

@Composable
private fun LogTail(title: String, source: () -> String) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            text = source().ifBlank { "пусто" }
            delay(2000)
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .glass(RoundedCornerShape(Radii.m))
            .padding(Spacing.s),
    ) {
        Text(title.uppercase(), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(Spacing.xs))
        Text(text, color = Muted, fontSize = 9.sp)
    }
}

// ---------------------------------------------------------------- добавление

@Composable
private fun AddSubscriptionSheet(controller: AndroidController, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    Box(
        Modifier.fillMaxSize().background(LimeColors.Bg.copy(alpha = 0.86f))
            .clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth()
                .padding(Spacing.l)
                .glass(RoundedCornerShape(Radii.l))
                .padding(Spacing.l)
                .consumeTaps(),
        ) {
            Text("Добавить подписку", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.s))
            Text(
                "Ссылка на подписку (http/https), список vless://… строк или limenet:// импорт.",
                color = Muted,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(Spacing.m))
            GlassField(
                value = text,
                onChange = { text = it },
                placeholder = "https://… или vless://…",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.s))
            SmallButton(
                "сканировать QR-код",
                enabled = activity != null && !controller.busy,
            ) {
                if (activity != null) {
                    val scanner = GmsBarcodeScanning.getClient(activity)
                    scanner.startScan()
                        .addOnSuccessListener { barcode ->
                            val payload = runCatching {
                                QrConfigPayload.validate(barcode.rawValue.orEmpty())
                            }.getOrElse {
                                controller.toast(it.message ?: "В QR-коде нет конфигурации", error = true)
                                return@addOnSuccessListener
                            }
                            controller.addSubscription(payload)
                            onClose()
                        }
                        .addOnFailureListener {
                            controller.toast("Не удалось прочитать QR-код", error = true)
                        }
                }
            }
            Spacer(Modifier.height(Spacing.m))
            Row(horizontalArrangement = Arrangement.End) {
                SmallButton("отмена", onClick = onClose)
                Spacer(Modifier.width(Spacing.s))
                SmallButton(
                    label = "добавить",
                    enabled = text.isNotBlank() && !controller.busy,
                    onClick = {
                        controller.addSubscription(text.trim())
                        onClose()
                    },
                )
            }
        }
    }
}

@Composable
private fun QrCodeDialog(node: ProxyNode, onClose: () -> Unit) {
    val bitmap = remember(node.raw) {
        runCatching {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                node.raw,
                com.google.zxing.BarcodeFormat.QR_CODE,
                720,
                720,
                mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
            android.graphics.Bitmap.createBitmap(720, 720, android.graphics.Bitmap.Config.ARGB_8888).also { image ->
                for (y in 0 until 720) for (x in 0 until 720) {
                    image.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }.getOrNull()
    }
    Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.xl)
                .glass(RoundedCornerShape(Radii.l)).padding(Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("QR-код конфигурации", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.s))
            Text(node.displayName, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Spacing.m))
            bitmap?.let {
                androidx.compose.foundation.Image(
                    it.asImageBitmap(),
                    contentDescription = "QR-код конфигурации",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radii.s)),
                )
            } ?: Text("Не удалось создать QR-код", color = Danger, fontSize = 12.sp)
            Spacer(Modifier.height(Spacing.m))
            Text("Код содержит секрет подключения. Передавайте его только доверенному устройству.", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(Spacing.s))
            SmallButton("закрыть", onClick = onClose)
        }
    }
}

// ------------------------------------------------------------------ компоненты

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(Spacing.l))
    Text(title.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(Spacing.s))
}

@Composable
private fun Chip(label: String, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.m)
    val accent by animateFloatAsState(if (active) 1f else 0f, tween(220), label = "chip")
    Box(
        Modifier.pressable(shape, onClick = onClick)
            .glass(shape, accent = accent)
            .heightIn(min = 40.dp)
            .padding(horizontal = Spacing.m, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) AccentDark else TextMain,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SmallButton(
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
        Modifier.pressable(shape, enabled, onClick = onClick)
            .glass(shape)
            .heightIn(min = 40.dp)
            .padding(horizontal = Spacing.m, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 11.sp)
    }
}

@Composable
private fun GlassField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Radii.m)
    Box(
        modifier.glass(shape).padding(horizontal = Spacing.m, vertical = Spacing.m),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = Muted, fontSize = 12.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = TextMain, fontSize = 12.sp),
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {}),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GlassToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(Radii.m)
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs)
            .glass(shape)
            .pressable(shape, depth = 0.99f, onClick = { onChange(!checked) })
            .padding(horizontal = Spacing.m, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(20.dp)
                .border(1.dp, if (checked) Accent else Muted, RoundedCornerShape(Radii.s))
                .background(if (checked) Accent.copy(alpha = 0.85f) else Color.Transparent, RoundedCornerShape(Radii.s)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Text("✓", color = AccentDark, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BottomMenu(
    title: String,
    onDismiss: () -> Unit,
    custom: (@Composable () -> Unit)? = null,
    actions: List<Pair<String, () -> Unit>>,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding()
                .glass(RoundedCornerShape(topStart = Radii.l, topEnd = Radii.l))
                .background(LimeColors.Surface.copy(alpha = 0.96f))
                .consumeTaps()
                .padding(Spacing.l),
        ) {
            Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Spacing.m))
            custom?.invoke()
            actions.forEach { (label, action) ->
                val shape = RoundedCornerShape(Radii.m)
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .glass(shape)
                        .pressable(shape, depth = 0.985f, onClick = action)
                        .padding(horizontal = Spacing.m, vertical = Spacing.m),
                ) {
                    Text(label, color = TextMain, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(Spacing.s))
        }
    }
}

@Composable
private fun MessagePill(controller: AndroidController, modifier: Modifier = Modifier) {
    val text = controller.message ?: return
    Box(modifier.fillMaxWidth().safeDrawingPadding().padding(Spacing.l), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.glass(RoundedCornerShape(Radii.l), accent = if (controller.messageIsError) 0f else 0.12f)
                .background(
                    if (controller.messageIsError) Danger.copy(alpha = 0.25f) else Color.Transparent,
                    RoundedCornerShape(Radii.l),
                )
                .padding(horizontal = Spacing.l, vertical = Spacing.m)
                .clickable { controller.clearMessage() },
        ) {
            Text(text, color = TextMain, fontSize = 12.sp)
        }
    }
}

// --------------------------------------------------------------------- утилиты

/** Поглощает касания: подложка оверлея не пропускает тапы к экрану под ней. */
private fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) {
    detectTapGestures { }
}

private fun toSlot(node: ProxyNode, controller: AndroidController): DialSlot {
    val url = controller.urlResults[node.id]
    return DialSlot(
        id = node.id,
        code = if (node.autoBalancerTag != null) "AUTO" else node.country ?: "·",
        country = if (node.autoBalancerTag != null) "Авто" else node.shortName,
        pingMs = controller.latencyOf(node.id),
        measured = node.id in controller.pings || url?.latencyMs != null || url?.error != null,
        flagCode = node.country,
    )
}

@Composable
private fun PendingDots() {
    val transition = rememberInfiniteTransition(label = "mobile-pending-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "mobile-pending-phase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val distance = (phase - index + 3f) % 3f
            val light = (1f - distance / 1.5f).coerceIn(0f, 1f)
            Box(Modifier.size(3.dp).background(Muted.copy(alpha = 0.25f + 0.75f * light), RoundedCornerShape(50)))
        }
    }
}

private fun Context.clipboard(): Clipboard = Clipboard(
    getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager,
)

private class Clipboard(private val manager: ClipboardManager) {
    fun copy(text: String) {
        manager.setPrimaryClip(ClipData.newPlainText("LimeNet", text))
    }
}


