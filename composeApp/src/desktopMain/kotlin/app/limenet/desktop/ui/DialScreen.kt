package app.limenet.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.shape.RoundedCornerShape
import app.limenet.ui.theme.Radii
import app.limenet.ui.theme.glass
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.limenet.core.Mode
import app.limenet.core.ProxyNode
import app.limenet.core.Format
import app.limenet.core.model.POPULAR_SERVICES
import app.limenet.desktop.Controller
import app.limenet.ui.dial.DIAL_SEGMENTS
import app.limenet.ui.dial.DialSlot
import app.limenet.ui.dial.DialStatus
import app.limenet.ui.dial.LimeDial
import app.limenet.ui.theme.LimeColors
import app.limenet.ui.theme.LimeTheme
import app.limenet.ui.theme.Spacing
import app.limenet.ui.theme.pressable
import java.io.File
import kotlinx.coroutines.delay

/**
 * Избранные серверы: именно они — дольки на главном экране (до 12).
 * Недостающие позиции заполняются случайными серверами выбранной подписки. Хранятся отдельным файлом `~/.limenet/favorites.txt` (по id на строку),
 * Controller и настройки не затрагиваются.
 */
internal object Favorites {
    const val MAX = DIAL_SEGMENTS
    private val file = File(System.getProperty("user.home"), ".limenet/favorites.txt")

    var ids: List<String> by mutableStateOf(load())
        private set

    val full: Boolean get() = ids.size >= MAX

    fun isFav(id: String): Boolean = id in ids

    /** Добавить/убрать. `false` — добавить нельзя: уже 12. */
    fun toggle(id: String): Boolean {
        when {
            id in ids -> save(ids - id)
            ids.size < MAX -> save(ids + id)
            else -> return false
        }
        return true
    }

    private fun load(): List<String> = runCatching {
        if (file.exists()) {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX)
        } else {
            emptyList()
        }
    }.getOrDefault(emptyList())

    private fun save(new: List<String>) {
        ids = new
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(new.joinToString("\n"))
        }
    }
}

/**
 * Запасной набор долек, когда избранных нет: топ-12 серверов текущей подписки по пингу.
 * Порядок замораживается один раз на «эпоху» подписки (смена или обновление) и живёт вне
 * композиции — переключение вкладок его не пересортировывает. Дальше меняются только длины.
 */
internal object TopSet {
    private var epoch: Any? = null

    var ids: List<String>? by mutableStateOf(null)
        private set

    fun reset(newEpoch: Any?) {
        if (epoch != newEpoch) {
            epoch = newEpoch
            ids = null
        }
    }

    fun freeze(list: List<String>) {
        ids = list
    }
}

/**
 * Сервер, выбранный на вкладке «серверы» при активном подключении: подключение
 * не переключается молча — на главном экране центр покажет «переключить».
 */
internal object Pick {
    var pendingId: String? by mutableStateOf(null)
}

/**
 * Шторка «все серверы»: открывается зажатием центральной кнопки и «вырастает» из неё,
 * как приложение на iPhone. [origin] — центр колеса в координатах окна (откуда расти).
 */
internal object ServersSheet {
    var open: Boolean by mutableStateOf(false)
    var origin: Offset by mutableStateOf(Offset.Unspecified)

    fun show() { open = true }
    fun hide() { open = false }
}

/** Самый быстрый из [pool] по уже известным замерам; `null` — замеров нет. */
internal fun fastestId(controller: Controller, pool: List<ProxyNode>): String? =
    pool.filter { it.supported }
        .mapNotNull { n -> controller.latencyOf(n.id)?.let { n.id to it } }
        .minByOrNull { it.second }
        ?.first

private val TECH_TAGS = Regex(
    "(?<![\\p{L}\\p{N}])(?:vless|vmess|trojan|shadowsocks|ss|hysteria2?|hy2|tuic|tcp|udp|ws|grpc|h2|quic|" +
        "xhttp|splithttp|reality|tls|xtls|vision|http|https|xray|v2ray|clash|sing-?box|x\\d+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?x)" +
        "(?![\\p{L}\\p{N}])",
    RegexOption.IGNORE_CASE,
)
private val FLAG_CHARS = Regex("[\\x{1F1E6}-\\x{1F1FF}]")
private val AT_TAIL = Regex("@\\S*")
private val BRACKETS = Regex("\\[[^\\]]*]|\\([^)]*\\)")
private val SEP_RUN = Regex("(?:\\s*[-–—|·•/]\\s*){2,}")
private val SPACES = Regex("\\s+")

/**
 * Название сервера без спама: убираем всё от `@`, эмодзи-флаги (флаг рисуем картинкой),
 * технические теги (протокол, транспорт, множители) вместе со скобками,
 * лишние разделители. Остаются страна/город и суть.
 */
internal fun cleanServerName(raw: String): String {
    var s = FLAG_CHARS.replace(raw, " ")
    s = AT_TAIL.replace(s, " ")
    s = BRACKETS.replace(s) { m -> if (TECH_TAGS.containsMatchIn(m.value)) " " else m.value }
    s = TECH_TAGS.replace(s, " ")
    s = SEP_RUN.replace(s, " · ")
    s = SPACES.replace(s, " ").trim { it in " -–—|·•:,_/" }
    return s.ifBlank { raw.trim() }
}

/**
 * Главный экран («подключение»): название сервера сверху, колесо избранных,
 * в центре кнопка подключения. Логика подключения не меняется — только
 * вызовы selectNode / toggleConnection.
 */
@Composable
internal fun DialScreen(
    controller: Controller,
    onOpenSubscriptions: () -> Unit,
    onOpenList: () -> Unit,
) {
    val allNodes = controller.nodes
    val subs = controller.subscriptions
    val selectedId = controller.selectedId
    val pendingId = Pick.pendingId
    val favIds = Favorites.ids
    val pings = controller.pings
    val urlResults = controller.urlResults

    LaunchedEffect(controller.connected, controller.selectedId, controller.busy) {
        if (controller.connected && !controller.busy) controller.checkPopularServices()
        else controller.cancelPopularServiceChecks()
    }

    // Подписка для датчика трафика: владелец выбранного узла, иначе активная, иначе первая с узлами.
    val sub = selectedId?.let(controller::subscriptionOf)
        ?: subs.firstOrNull { it.id == controller.settings.activeSubscriptionId }
        ?: subs.firstOrNull { controller.nodeCount(it.id) > 0 }

    val candidates = remember(sub?.id, sub?.updatedAt, allNodes) {
        if (sub == null) emptyList() else controller.nodesOf(sub.id)
            .filter { it.supported && it.autoBalancerTag == null }
    }

    // Порядок колеса стабилен на время сессии; избранное ниже дополняется
    // случайными узлами выбранной подписки.
    val useTop = favIds.isEmpty()
    val epoch = sub?.id to sub?.updatedAt
    val ready = candidates.isNotEmpty() && candidates.all { it.id in pings }
    LaunchedEffect(useTop, epoch, ready, candidates) {
        if (!useTop) return@LaunchedEffect
        TopSet.reset(epoch)
        if (TopSet.ids != null || candidates.isEmpty()) return@LaunchedEffect
        if (!ready) {
            delay(6000)
            if (candidates.none { it.id in controller.pings }) return@LaunchedEffect
        }
        TopSet.freeze(topByPing(candidates, controller::latencyOf))
    }

    // Дольки: избранные в порядке добавления, затем случайные узлы до 12.
    // Порядок стабилен, после замеров меняется только длина.
    val topIds = TopSet.ids
    val ordered: List<ProxyNode?> = remember(favIds, topIds, candidates, allNodes) {
        val list = if (favIds.isNotEmpty()) {
            val byId = allNodes.associateBy { it.id }
            val favorites = favIds.mapNotNull { byId[it] }.filter { it.supported }
            val auto = sub?.autoBalancerTag?.let { tag -> controller.nodesOf(sub.id).firstOrNull { it.autoBalancerTag == tag } }
            (listOfNotNull(auto) + favorites + candidates.filter { it.id !in favIds }.shuffled())
                .distinctBy { it.id }
                .take(DIAL_SEGMENTS)
        } else {
            val byId = candidates.associateBy { it.id }
            val top = topIds.orEmpty().mapNotNull { byId[it] }
            val auto = sub?.autoBalancerTag?.let { tag -> controller.nodesOf(sub.id).firstOrNull { it.autoBalancerTag == tag } }
            (listOfNotNull(auto) + top + candidates.filter { it.id !in topIds.orEmpty() }.shuffled())
                .distinctBy { it.id }
                .take(DIAL_SEGMENTS)
        }
        List(DIAL_SEGMENTS) { list.getOrNull(it) }
    }
    val testMethod = controller.settings.testMethod
    val slots: List<DialSlot?> = remember(ordered, pings, urlResults, testMethod) {
        ordered.mapIndexed { i, node -> node?.let { toSlot(it, i, controller) } }
    }

    // Выбранная долька (-1 — выбранный сервер не на колесе). При вращении сначала меняется
    // подсветка; выбор передаётся Controller после короткой паузы, чтобы не перезапускать
    // ядро на каждом промежуточном лепестке.
    //
    // Состояние выбора живёт в одном неизменяемом объекте: обработчики читают его из
    // `mutableStateOf`, а не из замыкания композиции. Раньше центр брал устаревшую
    // дольку и молча возвращал прошлый сервер.
    val dialState = remember { mutableStateOf(DialSelection().withOrder(ordered.map { it?.id })) }
    val dial = dialState.value

    // Порядок долей пересобрался (пинги, избранное) — колесо остаётся на том же сервере.
    SideEffect {
        val next = dialState.value.withOrder(ordered.map { it?.id })
        if (next != dialState.value) dialState.value = next
    }

    // Controller сменил сервер снаружи (старт, автопереключение) — показываем его.
    LaunchedEffect(selectedId, pendingId) {
        val next = dialState.value.withSync(selectedId, pendingId)
        if (next != dialState.value) dialState.value = next
    }
    LaunchedEffect(pendingId, selectedId) {
        if (pendingId != null && pendingId == selectedId) Pick.pendingId = null
    }

    val dialNode = dial.dialId?.let { id -> allNodes.firstOrNull { it.id == id } }

    // Пользователь выбрал дольку, а Controller ещё не знает: коммитим после короткой паузы,
    // как при отключённом клиенте, так и при подключённом (в последнем случае ядро
    // автоматически переподключится к новому узлу).
    LaunchedEffect(dial.index, controller.connected, controller.busy) {
        val id = dial.dialId ?: return@LaunchedEffect
        if (!dial.touched || controller.busy) return@LaunchedEffect
        if (id == controller.selectedId) return@LaunchedEffect
        delay(250)
        val live = dialState.value
        if (live.dialId == id && !controller.busy) controller.selectNode(id)
    }

    val pendingNode = pendingId?.let { id -> allNodes.firstOrNull { it.id == id } }
    val current: ProxyNode? = pendingNode ?: dialNode ?: controller.selected
    val currentSlot = current?.let { n ->
        toSlot(n, ordered.indexOfFirst { it?.id == n.id }.coerceAtLeast(0), controller)
    }
    val switchPending = controller.connected && current != null && current.id != selectedId
    val status = when {
        controller.busy -> DialStatus.CONNECTING
        controller.connected -> DialStatus.ON
        else -> DialStatus.OFF
    }
    val trafficLeft = sub?.let {
        if (it.total > 0) (1f - it.used.toFloat() / it.total.toFloat()).coerceIn(0f, 1f) else 1f
    } ?: 1f

    fun onCenterTap() {
        // Живое состояние: долька, на которую реально показывает колесо.
        val action = dialState.value.centerTap(
            selectedId = controller.selectedId,
            connected = controller.connected,
            busy = controller.busy,
            anyNodes = allNodes.isNotEmpty(),
            fallbackId = controller.selected?.id ?: allNodes.firstOrNull { it.supported }?.id,
        )
        when (action) {
            DialAction.None -> Unit
            DialAction.OpenSubscriptions -> onOpenSubscriptions()
            DialAction.OpenList -> onOpenList()
            is DialAction.SelectAndConnect -> {
                Pick.pendingId = null
                controller.selectNode(action.id)
                if (!controller.connected) controller.toggleConnection()
            }
            is DialAction.Select -> {
                Pick.pendingId = null
                controller.selectNode(action.id)
            }
            is DialAction.Connect -> controller.toggleConnection()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ServerTitle(
            current,
            Modifier.fillMaxWidth().padding(horizontal = screenPad(), vertical = Spacing.m),
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.l)
                .onGloballyPositioned { coords ->
                    // центр колеса в окне: отсюда «вырастает» шторка серверов
                    ServersSheet.origin = coords.positionInWindow() +
                        Offset(coords.size.width / 2f, coords.size.height / 2f)
                },
            contentAlignment = Alignment.Center,
        ) {
            LimeDial(
                slots = slots,
                selectedIndex = dial.index,
                current = currentSlot,
                status = status,
                trafficLeft = trafficLeft,
                switchPending = switchPending,
                onSelect = { i ->
                    Pick.pendingId = null
                    dialState.value = dialState.value.withUserSelect(i)
                },
                onCenterTap = ::onCenterTap,
                onCenterLongPress = onOpenList,
                modifier = Modifier.widthIn(max = 560.dp).heightIn(max = 560.dp).fillMaxSize(),
                emptyText = if (useTop && candidates.isNotEmpty() && topIds == null) "замеряю…" else "нет серверов",
                onEmptySlotTap = onOpenList,
            )
        }
        DialControls(controller)
        if (controller.connected && !controller.busy) PopularServicesPanel(controller)
        DialFooter(controller)
    }
}

@Composable
private fun PopularServicesPanel(controller: Controller) {
    val fonts = LimeTheme.fonts
    Column(
        Modifier.fillMaxWidth().padding(horizontal = screenPad(), vertical = Spacing.xs)
            .glass(RoundedCornerShape(Radii.m)).padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "ДОСТУПНОСТЬ СЕРВИСОВ ЧЕРЕЗ VPN",
                style = TextStyle(fontFamily = fonts.mono, fontSize = 9.sp, color = LimeColors.TextSecondary),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                if (controller.serviceCheckRunning) "проверяю…" else "обновить",
                style = TextStyle(fontFamily = fonts.mono, fontSize = 9.sp, color = LimeColors.Accent),
                modifier = Modifier.pressable(onClick = controller::checkPopularServices).padding(horizontal = Spacing.xs),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            POPULAR_SERVICES.forEach { service ->
                val result = controller.serviceResults[service.id]
                val value = when {
                    result?.latencyMs != null -> "${result.latencyMs} мс"
                    result != null -> "нет связи"
                    controller.serviceCheckRunning -> "…"
                    else -> "—"
                }
                val color = when {
                    result?.latencyMs != null -> LimeColors.Accent
                    result != null -> LimeColors.Error
                    else -> LimeColors.TextSecondary
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(service.title, style = TextStyle(fontFamily = fonts.sans, fontSize = 9.sp, color = LimeColors.TextSecondary))
                    BasicText(value, style = TextStyle(fontFamily = fonts.mono, fontSize = 10.sp, color = color))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "СКОРОСТЬ VPN: ${controller.speedTestResult?.speedBps?.let(Format::speed) ?: "—"}",
                style = TextStyle(
                    fontFamily = fonts.mono,
                    fontSize = 9.sp,
                    color = controller.speedTestResult?.speedBps?.let { LimeColors.Accent } ?: LimeColors.TextSecondary,
                ),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                when {
                    controller.speedTestRunning -> "замер…"
                    controller.speedTestResult?.speedBps != null -> "повторить"
                    controller.speedTestResult != null -> "ошибка · повторить"
                    else -> "СПИДТЕСТ"
                },
                style = TextStyle(fontFamily = fonts.mono, fontSize = 9.sp, color = LimeColors.Accent),
                modifier = Modifier.pressable(enabled = !controller.speedTestRunning, onClick = controller::runSpeedTest)
                    .padding(horizontal = Spacing.xs),
            )
        }
    }
}

/**
 * Полное название сервера над колесом: флаг + очищенное имя в одну строку.
 * Бегущая строка включается сама, только если имя не помещается.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerTitle(node: ProxyNode?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
    Row(
        Modifier
            .widthIn(max = 520.dp)
            .glass(RoundedCornerShape(Radii.l))
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node?.country != null) {
            FlagIcon(node.country, 20.dp)
            Spacer(Modifier.width(Spacing.s))
        }
        BasicText(
            if (node == null) "сервер не выбран" else cleanServerName(node.shortName),
            modifier = Modifier.weight(1f, fill = false).basicMarquee(iterations = Int.MAX_VALUE),
            style = TextStyle(
                fontFamily = LimeTheme.fonts.sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = if (node == null) LimeColors.TextSecondary else LimeColors.Text,
            ),
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
    }
}

/** Топ-12 по задержке; недоступные и неизмеренные — в конце. Сортировка стабильна. */
private fun topByPing(
    nodes: List<ProxyNode>,
    latency: (String) -> Int?,
): List<String> = nodes
    .sortedBy { latency(it.id) ?: Int.MAX_VALUE }
    .take(DIAL_SEGMENTS)
    .map { it.id }

private fun toSlot(
    node: ProxyNode,
    index: Int,
    controller: Controller,
): DialSlot {
    val url = controller.urlResults[node.id]
    return DialSlot(
        id = node.id,
        code = if (node.autoBalancerTag != null) "AUTO" else node.country ?: "·${index + 1}",
        country = if (node.autoBalancerTag != null) "Авто" else cleanServerName(node.shortName),
        pingMs = controller.latencyOf(node.id),
        measured = node.id in controller.pings || url?.latencyMs != null || url?.error != null,
        flagCode = node.country,
    )
}

@Composable
private fun DialControls(controller: Controller) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = screenPad(), vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val compact = LocalCompact.current
        val checkSubscriptionId = controller.selectedId?.let(controller::subscriptionOf)?.id
            ?: controller.settings.activeSubscriptionId
            ?: controller.subscriptions.firstOrNull()?.id
        val actions: @Composable () -> Unit = { TestControls(controller, checkSubscriptionId) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Chip("tun", controller.settings.mode == Mode.TUN) { controller.updateMode(Mode.TUN) }
            Spacer(Modifier.width(Spacing.s))
            Chip("socks + http", controller.settings.mode == Mode.PROXY) { controller.updateMode(Mode.PROXY) }
            if (!compact) {
                Spacer(Modifier.width(Spacing.l))
                actions()
            }
        }
        if (compact) {
            // телефон: режимы и замеры не помещаются в одну строку
            Spacer(Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                actions()
            }
        }
        if (controller.settings.mode == Mode.TUN && !controller.isAdmin) {
            Spacer(Modifier.height(Spacing.s))
            SmallButton("tun требует прав администратора — перезапустить") { controller.relaunchElevated() }
        }
        if (controller.connected && controller.settings.mode == Mode.PROXY) {
            Spacer(Modifier.height(Spacing.s))
            BasicText(
                "socks 127.0.0.1:${Controller.SOCKS_PORT} · http 127.0.0.1:${Controller.HTTP_PORT}",
                style = TextStyle(
                    fontFamily = LimeTheme.fonts.mono, fontSize = 10.sp, color = LimeColors.TextSecondary,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun DialFooter(controller: Controller) {
    val fonts = LimeTheme.fonts
    val mono = TextStyle(fontFamily = fonts.mono, fontSize = 11.sp, color = LimeColors.TextSecondary)
    Box(Modifier.fillMaxWidth().height(1.dp).background(LimeColors.Line))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = screenPad(), vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f)) {
            BasicText("сессия ", style = mono)
            SessionClock(controller.connected, controller.connectedAt, mono)
        }
        val protocol = controller.selected?.protocol?.wire ?: "—"
        val mode = if (controller.settings.mode == Mode.TUN) "tun" else "proxy"
        BasicText("$protocol · $mode", style = mono)
        BasicText(
            "узлов ${controller.nodes.size}",
            style = mono.copy(textAlign = TextAlign.End),
            modifier = Modifier.weight(1f),
        )
    }
}

/** Таймер сессии: тикает раз в секунду и только пока есть соединение. */
@Composable
private fun SessionClock(connected: Boolean, connectedAt: Long, style: TextStyle) {
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected, connectedAt) {
        if (!connected) {
            return@LaunchedEffect
        }
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text = if (connected && connectedAt > 0L) {
        val sec = ((now - connectedAt) / 1000).coerceAtLeast(0)
        "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    } else {
        "—"
    }
    BasicText(text, style = style)
}
