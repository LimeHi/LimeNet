package com.limeprod.limenet

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.limenet.core.Codec
import app.limenet.core.CatalogEntry
import app.limenet.core.DeepLink
import app.limenet.core.Mode
import app.limenet.core.ProxyNode
import app.limenet.core.autoNode
import app.limenet.core.SubMeta
import app.limenet.core.TlsMode
import app.limenet.core.withServerName
import app.limenet.core.model.AppState
import app.limenet.core.model.DEFAULT_TEST_URL
import app.limenet.core.model.LEGACY_TEST_URL
import app.limenet.core.model.Settings
import app.limenet.core.model.SubscriptionInfo
import app.limenet.core.model.TestMethod
import app.limenet.core.model.TestFailure
import app.limenet.core.model.UserAgentPreset
import app.limenet.core.model.POPULAR_SERVICES
import app.limenet.core.parse.Subscription
import app.limenet.core.parseCatalog
import app.limenet.core.render.XrayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private const val CATALOG_URL = "https://raw.githubusercontent.com/LimeHi/LimeNet/refs/heads/main/needlinks.txt"

/**
 * Контроллер Android-клиента. Логика та же, что на десктопе (подписки, выбор
 * узла, проверки, автопереключение), но вместо системного прокси — VPN-сервис,
 * а сеть ходит через `HttpURLConnection`.
 */
class AndroidController(private val scope: CoroutineScope) {

    private val tester = MobileUrlTester(AndroidEnv.xrayBin, AndroidEnv.testsDir)
    private val availabilityTester = MobileUrlTester(
        AndroidEnv.xrayBin,
        AndroidEnv.testsDir.resolve("connectivity"),
    )
    private val serviceTester = MobileUrlTester(
        AndroidEnv.xrayBin,
        AndroidEnv.testsDir.resolve("service-checks"),
    )
    private val store = MobileStore()

    var settings by mutableStateOf(Settings())
        private set
    var subscriptions by mutableStateOf<List<SubscriptionInfo>>(emptyList())
        private set
    var catalog by mutableStateOf<List<CatalogEntry>>(emptyList())
        private set
    var catalogLoading by mutableStateOf(false)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    var nodes by mutableStateOf<List<ProxyNode>>(emptyList())
        private set
    var pings by mutableStateOf<Map<String, Int?>>(emptyMap())
        private set
    var tcpFailures by mutableStateOf<Map<String, TestFailure>>(emptyMap())
        private set
    var urlResults by mutableStateOf<Map<String, UrlResult>>(emptyMap())
        private set
    var serviceResults by mutableStateOf<Map<String, UrlResult>>(emptyMap())
        private set
    var serviceCheckRunning by mutableStateOf(false)
        private set
    var speedTestRunning by mutableStateOf(false)
        private set
    var speedTestResult by mutableStateOf<UrlResult?>(null)
        private set
    var testing by mutableStateOf(false)
        private set
    var checkQueue by mutableStateOf<Set<String>>(emptySet())
        private set
    var checkActive by mutableStateOf<Set<String>>(emptySet())
        private set
    var checkDone by mutableStateOf(0)
        private set
    var checkTotal by mutableStateOf(0)
        private set
    var checkScope by mutableStateOf<String?>(null)
        private set
    var updatingAll by mutableStateOf(false)
        private set
    var refreshOutcomes by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set
    var updating by mutableStateOf<String?>(null)
        private set
    var certNotes by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Запрос VPN-разрешения и запуск сервиса; ставится из MainActivity. */
    var onNeedVpn: (() -> Unit)? = null

    private var owners: Map<String, String> = emptyMap()
    private var pinging = false
    private var checkJob: Job? = null
    private val checkSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var failStreak = 0
    private var restartJob: Job? = null
    private var messageJob: Job? = null
    private var configJob: Job? = null
    private var serviceCheckJob: Job? = null
    private var serviceCheckNodeId: String? = null
    private var serviceCheckRun = 0
    private var speedTestJob: Job? = null
    private var speedTestRun = 0
    private var lastAppEntryChecksAt = 0L
    /** Новый запуск/отключение отменяет начатую, но ещё не завершённую попытку подключения. */
    private var attemptToken = 0
    private val repaired = mutableMapOf<String, ProxyNode>()

    val selectedId: String? get() = settings.selectedNodeId
    val selected: ProxyNode? get() = nodes.firstOrNull { it.id == selectedId }

    /** Подключено, если живо ядро VPN-сервиса. */
    val connected: Boolean get() = VpnBridge.running
    val connecting: Boolean get() = VpnBridge.starting
    val screenPaused: Boolean get() = VpnBridge.screenPaused ||
        LimeNetVpnService.state(AndroidEnv.context) == LimeNetVpnService.STATE_PAUSED

    init {
        VpnBridge.onCoreFailure = {
            scope.launch { checkCoreFailure() }
        }
        restore()
        loadCatalog()
        scope.launch { checkCoreFailure() }
    }

    private fun loadCatalog() {
        val prefs = AndroidEnv.context.getSharedPreferences("limenet", Context.MODE_PRIVATE)
        prefs.getString("subscription-catalog", null)?.let(::parseCatalog)?.takeIf { it.isNotEmpty() }?.let {
            catalog = it
        }
        refreshCatalog()
    }

    fun refreshCatalog() {
        if (catalogLoading) return
        val prefs = AndroidEnv.context.getSharedPreferences("limenet", Context.MODE_PRIVATE)
        scope.launch {
            catalogLoading = true
            catalogError = null
            try {
                val remote = withContext(Dispatchers.IO) {
                    val connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
                    connection.apply {
                        connectTimeout = 10_000
                        readTimeout = 20_000
                        instanceFollowRedirects = true
                        useCaches = false
                        setRequestProperty("Cache-Control", "no-cache")
                        setRequestProperty("User-Agent", "LimeNet")
                    }
                    try {
                        val code = connection.responseCode
                        if (code !in 200..299) error("HTTP $code")
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }
                }
                val parsed = parseCatalog(remote)
                check(parsed.isNotEmpty()) { "Файл получен, но каталог пуст или формат записей не распознан" }
                catalog = parsed
                prefs.edit().putString("subscription-catalog", remote).apply()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                catalogError = "Не удалось обновить каталог: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                catalogLoading = false
            }
        }
    }

    fun addCatalogEntry(entry: CatalogEntry) {
        if (entry.status == CatalogEntry.Status.FREE && !entry.opensExternally) addSubscription(entry.url, entry.name)
    }

    // ---------------------------------------------------------------- подписки

    fun nodesOf(subscriptionId: String): List<ProxyNode> =
        nodes.filter { owners[it.id] == subscriptionId }

    fun subscriptionOf(nodeId: String): SubscriptionInfo? {
        val owner = owners[nodeId] ?: return null
        return subscriptions.firstOrNull { it.id == owner }
    }

    fun subscription(id: String): SubscriptionInfo? = subscriptions.firstOrNull { it.id == id }

    fun nodeCount(subscriptionId: String): Int = owners.count { it.value == subscriptionId }

    fun addSubscription(input: String, title: String? = null) {
        val value = input.trim()
        if (value.isEmpty() || busy) return

        busy = true
        clearMessage()
        scope.launch {
            try {
                val isUrl = value.startsWith("http://") || value.startsWith("https://")
                val body: String
                var meta: SubMeta? = null
                if (isUrl) {
                    val fetched = fetch(value)
                    body = fetched.first
                    meta = fetched.second
                } else {
                    body = value
                }

                val parsed = withContext(Dispatchers.Default) { Subscription.parse(body) }
                if (parsed.nodes.isEmpty()) error("в ответе не найдено ни одного узла")

                val id = "sub-" + Codec.fnv1a(value)
                val existing = subscriptions.firstOrNull { it.id == id }
                val now = System.currentTimeMillis()

                val info = SubscriptionInfo(
                    id = id,
                    title = title?.takeIf { it.isNotBlank() }
                        ?: existing?.title?.takeIf { it.isNotBlank() }
                        ?: titleOf(value),
                    url = if (isUrl) value else existing?.url,
                    raw = parsed.nodes.map { it.raw }.distinct(),
                    xrayConfigJson = parsed.xrayConfigJson,
                    autoBalancerTag = parsed.autoBalancerTag,
                    autoNodeId = parsed.autoBalancerTag?.let { autoNode(id, it).id },
                    upload = meta?.upload ?: existing?.upload ?: 0,
                    download = meta?.download ?: existing?.download ?: 0,
                    total = meta?.total ?: existing?.total ?: 0,
                    expire = meta?.expire ?: existing?.expire ?: 0,
                    announce = (meta?.announce ?: Subscription.announce(body)).orEmpty(),
                    updatedAt = now,
                )

                subscriptions = if (existing == null) {
                    subscriptions + info
                } else {
                    subscriptions.map { if (it.id == id) info else it }
                }

                rebuild()
                if (selectedId == null || nodes.none { it.id == selectedId }) {
                    settings = settings.copy(selectedNodeId = pickBest())
                }
                persist()

                notify(
                    if (existing == null) "Добавлено ${parsed.nodes.size} узлов"
                    else "Подписка обновлена: ${parsed.nodes.size} узлов",
                )
                pingAll()
            } catch (error: Exception) {
                notify(error.message ?: "не удалось добавить подписку", error = true)
            } finally {
                busy = false
            }
        }
    }

    fun removeSubscription(id: String) {
        val owned = nodesOf(id).map { it.id }.toSet()
        if (connected && selectedId in owned) disconnect()
        subscriptions = subscriptions.filterNot { it.id == id }
        if (settings.activeSubscriptionId == id) {
            settings = settings.copy(activeSubscriptionId = null)
        }
        rebuild()
        if (nodes.none { it.id == selectedId }) {
            settings = settings.copy(selectedNodeId = pickBest())
        }
        persist()
        notify("Подписка удалена")
    }

    fun renameSubscription(id: String, title: String) {
        val name = title.trim()
        if (name.isEmpty() || subscription(id) == null) return
        subscriptions = subscriptions.map { if (it.id == id) it.copy(title = name) else it }
        persist()
    }

    fun nodeConfigJson(node: ProxyNode): String {
        if (node.autoBalancerTag != null) {
            val providerConfig = subscriptionOf(node.id)?.xrayConfigJson
                ?.let { Json.parseToJsonElement(it) as? JsonObject }
                ?: error("Конфигурация авто-группы не сохранена")
            val config = XrayConfig.renderSubscription(providerConfig, settings, HTTP_PORT, SOCKS_PORT, androidTun = true)
            return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config)
        }
        val entry = settings.chainEntryNodeId?.let { id -> nodes.firstOrNull { it.id == id } }
        val config = XrayConfig.renderTun(repaired[node.id] ?: node, settings, entry?.let { repaired[it.id] ?: it })
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), config)
    }

    fun refreshSubscription(id: String) {
        val sub = subscription(id) ?: return
        if (updating != null || updatingAll) return
        scope.launch { doRefresh(sub, silent = false) }
    }

    fun updateAll() {
        if (subscriptions.isEmpty() || updating != null || updatingAll) return
        updatingAll = true
        scope.launch {
            var ok = 0
            var failed = 0
            try {
                for (sub in subscriptions.toList()) {
                    if (sub.url == null) continue
                    if (doRefresh(sub, silent = true)) ok++ else failed++
                }
            } finally {
                updatingAll = false
            }
            when {
                ok + failed == 0 -> notify("нечего обновлять: нет подписок со ссылкой", error = true)
                failed == 0 -> notify("обновлено · подписок: $ok")
                ok == 0 -> notify("не удалось обновить подписки", error = true)
                else -> notify("обновлено $ok из ${ok + failed}", error = true)
            }
        }
    }

    private suspend fun doRefresh(sub: SubscriptionInfo, silent: Boolean): Boolean {
        if (sub.url == null) {
            if (!silent) notify("Локальная подписка обновляется только вручную", error = true)
            return false
        }
        updating = sub.id
        return try {
            val (body, meta) = fetch(sub.url)
            val parsed = withContext(Dispatchers.Default) { Subscription.parse(body) }
            if (parsed.nodes.isEmpty()) error("в ответе не найдено ни одного узла")

            val fresh = sub.copy(
                raw = parsed.nodes.map { it.raw }.distinct(),
                xrayConfigJson = parsed.xrayConfigJson,
                autoBalancerTag = parsed.autoBalancerTag,
                autoNodeId = parsed.autoBalancerTag?.let { autoNode(sub.id, it).id },
                refreshError = null,
                upload = meta?.upload ?: sub.upload,
                download = meta?.download ?: sub.download,
                total = meta?.total ?: sub.total,
                expire = meta?.expire ?: sub.expire,
                announce = meta?.announce.orEmpty(),
                updatedAt = System.currentTimeMillis(),
            )
            subscriptions = subscriptions.map { if (it.id == sub.id) fresh else it }
            rebuild()
            if (nodes.none { it.id == selectedId }) {
                settings = settings.copy(selectedNodeId = pickBest())
            }
            persist()
            flashOutcome(sub.id, true)
            if (!silent) notify("обновлено · узлов: ${parsed.nodes.size}")
            true
        } catch (error: Exception) {
            subscriptions = subscriptions.map {
                if (it.id == sub.id) it.copy(refreshError = describe(error)) else it
            }
            persist()
            flashOutcome(sub.id, false)
            if (!silent) notify("не удалось обновить · ${describe(error)}", error = true)
            false
        } finally {
            updating = null
        }
    }

    private fun flashOutcome(id: String, ok: Boolean) {
        refreshOutcomes = refreshOutcomes + (id to ok)
        scope.launch {
            delay(1_600)
            if (refreshOutcomes[id] == ok) refreshOutcomes = refreshOutcomes - id
        }
    }

    private fun describe(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "превышено время ожидания"
        is java.net.UnknownHostException -> "адрес не найден"
        is java.net.ConnectException -> "нет соединения"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "неизвестная ошибка"
    }

    private suspend fun updateStaleSubscriptions() {
        val hours = settings.autoUpdateHours
        if (hours <= 0) return
        val now = System.currentTimeMillis()
        val stale = subscriptions.filter { it.url != null && now - it.updatedAt > hours * 3_600_000L }
        for (sub in stale) doRefresh(sub, silent = true)
    }

    /** Запускается при появлении главного окна; короткий cooldown защищает от повторного onResume. */
    fun onAppEntered() {
        val now = System.currentTimeMillis()
        if (now - lastAppEntryChecksAt < 30_000L) return
        lastAppEntryChecksAt = now
        scope.launch {
            if (settings.updateSubscriptionsOnLaunch) updateStaleSubscriptions()
            if (settings.pingServersOnLaunch && !settings.batterySaver && !testing && !pinging) runPings()
        }
    }

    // ------------------------------------------------------------------- узлы

    fun selectNode(id: String) {
        val node = nodes.firstOrNull { it.id == id } ?: return
        if (!node.supported || id == selectedId) return
        if (node.autoBalancerTag != null && settings.engine != app.limenet.core.model.CoreEngine.XRAY) {
            notify("Авто-группа подписки требует движок Xray", error = true)
            return
        }
        settings = settings.copy(selectedNodeId = id)
        persist()
        // Сразу обновляем файл конфига: именно его читает запуск из шторки.
        prepareConfig()
        when {
            connected -> restartConnection()
            connecting -> applyAfterStart(id)
        }
    }

    /** Узел сменили во время подключения: применим выбор сразу после старта ядра. */
    private fun applyAfterStart(id: String) {
        scope.launch {
            repeat(160) {
                if (settings.selectedNodeId != id) return@launch
                if (!connecting) {
                    if (connected) restartConnection() else prepareConfig()
                    return@launch
                }
                delay(250)
            }
        }
    }

    fun updateMode(mode: Mode) = updateSettings { it.copy(mode = Mode.TUN) }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = settings
        val after = transform(before).copy(mode = Mode.TUN)
        if (after == before) return
        if (selected?.autoBalancerTag != null && after.engine != app.limenet.core.model.CoreEngine.XRAY) {
            notify("Сначала выберите движок Xray для авто-группы подписки", error = true)
            return
        }
        val changesConnection = before.mode != after.mode ||
            before.routing != after.routing ||
            before.customRules != after.customRules ||
            before.dns != after.dns ||
            before.tunMtu != after.tunMtu ||
            before.blockQuic != after.blockQuic ||
            before.chainEntryNodeId != after.chainEntryNodeId ||
            before.killSwitch != after.killSwitch ||
            before.splitTunnel != after.splitTunnel
        settings = after
        persist()
        AndroidEnv.context.getSharedPreferences("limenet", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(LimeNetVpnService.KEY_PAUSE_VPN_WITH_SCREEN, after.pauseVpnWithScreen).apply()
        if (before.pauseVpnWithScreen && !after.pauseVpnWithScreen) {
            LimeNetVpnService.resumeIfPauseDisabled(AndroidEnv.context)
        } else if (!before.pauseVpnWithScreen && after.pauseVpnWithScreen && connected) {
            LimeNetVpnService.pauseIfScreenOff(AndroidEnv.context)
        }

        if (connected && changesConnection) {
            restartConnection()
        } else if (changesConnection) {
            // Пока VPN выключен, шторка запускает сервис с готового файла конфига.
            // Перегенерируем его сразу, иначе там останутся прежние правила/узел.
            prepareConfig()
        }
    }

    fun clearMessage() {
        messageJob?.cancel()
        message = null
        messageIsError = false
    }

    fun toast(text: String, error: Boolean = false) = notify(text, error)

    private fun notify(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(if (error) 6_000 else 3_500)
            if (message == text) {
                message = null
                messageIsError = false
            }
        }
    }

    fun handleDeepLink(raw: String) {
        val request = DeepLink.parse(raw)
        if (request == null) {
            notify("Не удалось разобрать ссылку", error = true)
            return
        }
        addSubscription(request.payload, request.title)
    }

    // ------------------------------------------------------------ подключение

    fun toggleConnection() {
        if (busy || connecting) return
        if (connected || screenPaused) disconnect() else connect()
    }

    /** Перезапуск ядра с текущими настройками (например, после выбора узла). */
    private fun restartConnection() {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(400)
            if (!connected || busy) return@launch
            applyAndStart(reason = "Настройки применены")
        }
    }

    private fun connect() {
        if (VpnBridge.starting) return
        val node = selected ?: nodes.firstOrNull { it.supported }
        if (node == null) {
            notify("Нет узлов — добавьте подписку", error = true)
            return
        }
        scope.launch { applyAndStart() }
    }

    /** Отрендерить конфиг под выбранный узел и запустить ядро/сервис. */
    private suspend fun applyAndStart(reason: String? = null) {
        configJob?.cancel()
        configJob = null
        val initial = selected?.takeIf { it.supported } ?: nodes.firstOrNull { it.supported }
        if (initial == null) {
            notify("Нет доступных узлов — добавьте или обновите подписку", error = true)
            return
        }
        if (initial.autoBalancerTag != null && settings.engine != app.limenet.core.model.CoreEngine.XRAY) {
            notify("Авто-группа подписки требует движок Xray", error = true)
            return
        }
        val token = ++attemptToken
        busy = true
        VpnBridge.starting = true
        VpnBridge.message = null
        try {
            val node = if (initial.autoBalancerTag != null) initial else withContext(Dispatchers.IO) { findResponsiveNode(initial) }
            if (token != attemptToken) return
            if (node == null) {
                VpnBridge.starting = false
                notify("Не удалось подключиться: узлы подписки не прошли проверку", error = true)
                return
            }
            if (node.id != selectedId) {
                settings = settings.copy(selectedNodeId = node.id)
                persist()
            }
            writeConfig(node)
            if (token != attemptToken) return
            if (node.id != settings.selectedNodeId) {
                // Выбор изменился во время проверки: запуском займётся applyAfterStart/restart.
                VpnBridge.starting = false
                return
            }
            if (settings.mode == Mode.TUN) {
                // разрешение запрашивает Activity: отсюда до системного диалога не дотянуться
                onNeedVpn?.invoke() ?: run {
                    VpnBridge.starting = false
                    notify("VPN недоступен на этом устройстве", error = true)
                }
            } else {
                LimeNetVpnService.start(AndroidEnv.context, vpn = false, serverName = VpnBridge.serverName)
            }
            if (reason != null) {
                scope.launch {
                    delay(500)
                    if (VpnBridge.running) notify(reason)
                }
            }
        } catch (error: Exception) {
            if (token == attemptToken) {
                VpnBridge.starting = false
                notify(error.message ?: "ошибка подключения", error = true)
            }
        } finally {
            if (token == attemptToken) busy = false
        }
    }

    /**
     * Готовит конфиг выбранного узла, пока VPN выключен. Без этого запуск из
     * шторки/плитки использует файл от прошлого подключения — со старым сервером.
     */
    private fun prepareConfig() {
        val node = selected?.takeIf { it.supported } ?: return
        configJob?.cancel()
        configJob = scope.launch {
            try {
                writeConfig(node)
            } catch (error: Exception) {
                AndroidEnv.log("config: не удалось подготовить конфиг для плитки: $error")
            }
        }
    }

    /** Рендерит конфиг, пишет его атомарно и запоминает, для какого узла он создан. */
    private suspend fun writeConfig(node: ProxyNode) {
        if (node.autoBalancerTag != null) {
            if (node.id != settings.selectedNodeId) return
            val snapshot = settings
            if (snapshot.engine != app.limenet.core.model.CoreEngine.XRAY) error("Авто-группа подписки требует движок Xray")
            val sub = subscriptionOf(node.id) ?: error("подписка авто-узла не найдена")
            val rawConfig = sub.xrayConfigJson ?: error("конфигурация авто-группы не сохранена")
            val providerConfig = Json.parseToJsonElement(rawConfig) as? JsonObject
                ?: error("конфигурация авто-группы повреждена")
            val config = XrayConfig.renderSubscription(providerConfig, snapshot, HTTP_PORT, SOCKS_PORT, androidTun = true)
            withContext(Dispatchers.IO) {
                AndroidEnv.ensureAssets()
                writeConfigAtomically(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config))
                AndroidEnv.context.getSharedPreferences("limenet", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("last_config_is_tun", snapshot.mode == Mode.TUN)
                    .putInt("last_tun_mtu", snapshot.tunMtu ?: 0)
                    .putString("last_tun_dns", XrayConfig.androidTunDns(snapshot.dns.remoteServer))
                    .putStringSet(LimeNetVpnService.KEY_DIRECT_APPS, snapshot.splitTunnel.directApps.toSet())
                    .putBoolean(LimeNetVpnService.KEY_PAUSE_VPN_WITH_SCREEN, snapshot.pauseVpnWithScreen)
                    .putString(LimeNetVpnService.KEY_CONFIG_NODE_ID, node.id)
                    .putString("last_server_name", "Авто")
                    .apply()
            }
            VpnBridge.serverName = "Авто"
            return
        }
        // Пока проверялся старый узел, пользователь мог выбрать другой.
        if (node.id != settings.selectedNodeId) return
        val snapshot = settings
        val prepared = withContext(Dispatchers.IO) { repair(node) }
        val config = withContext(Dispatchers.Default) {
            if (snapshot.mode == Mode.TUN) {
                XrayConfig.renderTun(
                    prepared,
                    snapshot,
                    snapshot.chainEntryNodeId?.let { id -> nodes.firstOrNull { it.id == id } }
                        ?.let { repaired[it.id] ?: it },
                    SOCKS_PORT,
                )
            } else {
                XrayConfig.render(
                    prepared,
                    snapshot,
                    HTTP_PORT,
                    SOCKS_PORT,
                    snapshot.chainEntryNodeId?.let { id -> nodes.firstOrNull { it.id == id } }
                        ?.let { repaired[it.id] ?: it },
                )
            }
        }
        val name = node.displayName.ifBlank { node.shortName }
        withContext(Dispatchers.IO) {
            AndroidEnv.ensureAssets()
            writeConfigAtomically(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config))
            AndroidEnv.context.getSharedPreferences("limenet", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("last_config_is_tun", snapshot.mode == Mode.TUN)
                .putInt("last_tun_mtu", snapshot.tunMtu ?: 0)
                .putString("last_tun_dns", XrayConfig.androidTunDns(snapshot.dns.remoteServer))
                .putStringSet(LimeNetVpnService.KEY_DIRECT_APPS, snapshot.splitTunnel.directApps.toSet())
                .putBoolean(LimeNetVpnService.KEY_PAUSE_VPN_WITH_SCREEN, snapshot.pauseVpnWithScreen)
                .putString(LimeNetVpnService.KEY_CONFIG_NODE_ID, node.id)
                .putString("last_server_name", name)
                .apply()
        }
        VpnBridge.serverName = name
    }

    /** Ядро читает файл из другого процесса: не отдаём ему недописанный конфиг. */
    private fun writeConfigAtomically(text: String) {
        val target = AndroidEnv.configFile
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
    }

    /** Проверяет выбранный сервер реальным URL-запросом и при сбое ищет замену в той же подписке. */
    private fun findResponsiveNode(initial: ProxyNode): ProxyNode? {
        val testUrl = settings.testUrl.ifBlank { DEFAULT_TEST_URL }
        val owner = owners[initial.id]
        val candidates = buildList {
            add(initial)
            if (settings.autoSwitch) {
                nodes.asSequence()
                    .filter { it.supported && it.autoBalancerTag == null && it.id != initial.id }
                    .filter { owner != null && owners[it.id] == owner }
                    .forEach(::add)
            }
        }
        availabilityTester.beginCheck()
        return candidates.firstOrNull { candidate ->
            availabilityTester.test(repair(candidate), testUrl).ok
        }
    }

    /** Вызывается Activity после разрешения VPN. */
    fun launchVpnService() {
        LimeNetVpnService.start(AndroidEnv.context, vpn = true, serverName = VpnBridge.serverName)
    }

    fun vpnDenied() {
        VpnBridge.starting = false
        notify("VPN-разрешение не выдано", error = true)
    }

    private fun disconnect() {
        // Отложенный перезапуск не должен включить VPN после ручного отключения.
        restartJob?.cancel()
        restartJob = null
        attemptToken++
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { LimeNetVpnService.stop(AndroidEnv.context) }
                VpnBridge.reset()
            } finally {
                busy = false
            }
        }
    }

    /**
     * Если в ссылке нет домена для проверки сертификата, один раз выясняем имя
     * у самого сервера и запоминаем результат (TOFU).
     */
    @Synchronized
    private fun repair(node: ProxyNode): ProxyNode {
        if (node.tls.mode == TlsMode.NONE) return node
        if (XrayConfig.effectiveServerName(node) != null) return node
        repaired[node.id]?.let { return it }

        val info = MobileTls.probe(node.server, node.port)
        val name = info.names.firstOrNull()
        val fixed = if (name != null) node.withServerName(name) else node
        repaired[node.id] = fixed

        certNotes = certNotes + (
            node.id to if (name != null) {
                "Имя из сертификата: $name (получено с сервера)"
            } else {
                "Сертификат не проверен: ${info.error ?: "в сертификате нет домена"}"
            }
        )
        return fixed
    }

    // ------------------------------------------------------------------- тесты

    fun testAll() = runCheck(null)

    fun testSubscription(id: String) = runCheck(id)

    /** Отменяет очередь проверок и немедленно закрывает ресурсы URL-тестов. */
    fun cancelCheck() {
        if (!testing) return
        tester.cancelActive()
        checkSockets.toList().forEach { runCatching { it.close() } }
        checkJob?.cancel()
        notify("проверка остановлена")
    }

    fun isChecking(subscriptionId: String): Boolean =
        testing && (checkScope == null || checkScope == subscriptionId)

    /** Проверяет популярные сайты через SOCKS-вход активного VPN-движка. */
    fun checkPopularServices() {
        val node = selected ?: return
        if (!connected || busy) return
        speedTestRun++
        speedTestJob?.cancel()
        speedTestJob = null
        speedTestRunning = false
        serviceCheckJob?.cancel()
        serviceCheckJob = null
        serviceTester.cancelActive()
        serviceTester.beginCheck()
        val run = ++serviceCheckRun
        serviceCheckNodeId = node.id
        serviceResults = emptyMap()
        serviceCheckRunning = true
        serviceCheckJob = scope.launch {
            try {
                for (service in POPULAR_SERVICES) {
                    if (serviceCheckRun != run || !connected || selected?.id != node.id) break
                    val result = withContext(Dispatchers.IO) {
                        serviceTester.testViaSocks(service.url, SOCKS_PORT, timeoutMs = 4_000)
                    }
                    if (serviceCheckRun == run && connected && selected?.id == node.id) {
                        serviceResults = serviceResults + (service.id to result)
                    }
                }
            } finally {
                if (serviceCheckRun == run) serviceCheckRunning = false
            }
        }
    }

    fun cancelPopularServiceChecks() {
        serviceCheckRun++
        speedTestRun++
        serviceCheckJob?.cancel()
        serviceCheckJob = null
        speedTestJob?.cancel()
        speedTestJob = null
        serviceTester.cancelActive()
        serviceCheckRunning = false
        speedTestRunning = false
    }

    fun runSpeedTest() {
        val node = selected ?: return
        if (!connected || busy || speedTestRunning) return
        serviceCheckRun++
        serviceCheckJob?.cancel()
        serviceCheckJob = null
        serviceCheckRunning = false
        serviceTester.cancelActive()
        val run = ++speedTestRun
        speedTestJob?.cancel()
        serviceTester.beginCheck()
        speedTestRunning = true
        speedTestResult = null
        speedTestJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    serviceTester.testViaSocks(
                        testUrl = SPEED_TEST_URL,
                        socksPort = SOCKS_PORT,
                        timeoutMs = 8_000,
                        speedUrl = SPEED_URL,
                    )
                }
                if (speedTestRun == run && connected && selected?.id == node.id) speedTestResult = result
            } finally {
                if (speedTestRun == run) speedTestRunning = false
            }
        }
    }

    private fun runCheck(scopeId: String?) {
        if (testing) return
        val targets = (if (scopeId == null) nodes else nodesOf(scopeId))
            .filter { it.supported && it.autoBalancerTag == null }
        if (targets.isEmpty()) {
            notify("нет серверов для проверки", error = true)
            return
        }
        val method = settings.testMethod
        val testUrl = settings.testUrl.ifBlank { DEFAULT_TEST_URL }
        val parallel = if (method == TestMethod.TCP) TCP_TEST_PARALLEL else URL_TEST_PARALLEL

        testing = true
        checkScope = scopeId
        checkTotal = targets.size
        checkDone = 0
        checkQueue = targets.mapTo(HashSet()) { it.id }
        checkActive = emptySet()
        tester.beginCheck()

        checkJob = scope.launch {
            val started = System.currentTimeMillis()
            var ok = 0
            try {
                coroutineScope {
                    val gate = Semaphore(parallel)
                    targets.map { node ->
                        async {
                            gate.withPermit {
                                checkActive = checkActive + node.id
                                when (method) {
                                    TestMethod.URL -> {
                                        tcpFailures = tcpFailures - node.id
                                        val result = withContext(Dispatchers.IO) {
                                            tester.test(repair(node), testUrl)
                                        }
                                        urlResults = urlResults + (node.id to result)
                                        if (result.ok) ok++
                                    }
                                    TestMethod.TCP -> {
                                        val probe = tcpProbe(node.server, node.port)
                                        pings = pings + (node.id to probe.latencyMs)
                                        if (probe.failure == null) tcpFailures = tcpFailures - node.id
                                        else tcpFailures = tcpFailures + (node.id to probe.failure)
                                        if (probe.latencyMs != null) ok++
                                    }
                                    TestMethod.TCP_URL -> {
                                        val probe = tcpProbe(node.server, node.port)
                                        pings = pings + (node.id to probe.latencyMs)
                                        if (probe.failure == null) {
                                            tcpFailures = tcpFailures - node.id
                                            val result = withContext(Dispatchers.IO) {
                                                tester.test(repair(node), testUrl)
                                            }
                                            urlResults = urlResults + (node.id to result)
                                            if (result.ok) ok++
                                        } else {
                                            tcpFailures = tcpFailures + (node.id to probe.failure)
                                            urlResults = urlResults + (node.id to UrlResult(
                                                error = if (probe.failure == TestFailure.TIMEOUT) "тайм-аут TCP" else "TCP недоступен",
                                                failure = probe.failure,
                                            ))
                                        }
                                    }
                                }
                                checkActive = checkActive - node.id
                                checkQueue = checkQueue - node.id
                                checkDone += 1
                            }
                        }
                    }.awaitAll()
                }
                val shown = System.currentTimeMillis() - started
                if (shown < MIN_CHECK_MS) delay(MIN_CHECK_MS - shown)
            } catch (error: Exception) {
                if (error is kotlin.coroutines.cancellation.CancellationException) throw error
                notify("проверка прервана · ${describe(error)}", error = true)
                return@launch
            } finally {
                testing = false
                checkQueue = emptySet()
                checkActive = emptySet()
                checkJob = null
            }
            val title = when (method) {
                TestMethod.URL -> "url-тест"
                TestMethod.TCP -> "tcp-тест"
                TestMethod.TCP_URL -> "tcp + url-тест"
            }
            if (ok == 0) notify("$title · серверы недоступны", error = true)
            else notify("$title · доступно $ok из ${targets.size}")
        }
    }

    fun latencyOf(id: String): Int? = when (settings.testMethod) {
        TestMethod.URL, TestMethod.TCP_URL -> urlResults[id]?.let { it.latencyMs } ?: if (id in urlResults) null else pings[id]
        TestMethod.TCP -> pings[id]
    }

    fun pingAll() {
        scope.launch { runPings() }
    }

    private suspend fun runPings() {
        if (pinging) return
        pinging = true
        try {
            val targets = nodes.filter { it.supported && it.autoBalancerTag == null }
            for (chunk in targets.chunked(24)) {
                val part = withContext(Dispatchers.IO) {
                    coroutineScope {
                        chunk.map { node -> async { node.id to ping(node.server, node.port) } }.awaitAll()
                    }
                }
                pings = pings + part.toMap()
            }
        } finally {
            pinging = false
        }
    }

    private suspend fun ping(host: String, port: Int, timeoutMs: Int = 1500): Int? =
        withContext(Dispatchers.IO) {
            runCatching {
                val started = System.nanoTime()
                val socket = Socket()
                checkSockets.add(socket)
                try {
                    socket.use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                } finally {
                    checkSockets.remove(socket)
                }
                ((System.nanoTime() - started) / 1_000_000).toInt()
            }.getOrNull()
        }

    private data class TcpProbe(val latencyMs: Int?, val failure: TestFailure?)

    private suspend fun tcpProbe(host: String, port: Int, timeoutMs: Int = 1500): TcpProbe =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val socket = Socket()
            checkSockets.add(socket)
            try {
                socket.use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                TcpProbe(((System.nanoTime() - started) / 1_000_000).toInt(), null)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                TcpProbe(
                    latencyMs = null,
                    failure = if (error is java.net.SocketTimeoutException) TestFailure.TIMEOUT else TestFailure.UNAVAILABLE,
                )
            } finally {
                checkSockets.remove(socket)
            }
        }

    // --------------------------------------------------------------- служебное

    fun shutdown() {
        restartJob?.cancel()
        runCatching { LimeNetVpnService.stop(AndroidEnv.context) }
    }

    private fun rebuild() {
        val owner = LinkedHashMap<String, String>()
        val list = LinkedHashMap<String, ProxyNode>()
        for (sub in subscriptions) {
            val parsed = runCatching {
                sub.raw.flatMap { raw -> runCatching { Subscription.parse(raw).nodes }.getOrDefault(emptyList()) }
                    .filter { it.autoBalancerTag == null }
            }.getOrDefault(emptyList())
            for (node in parsed) {
                if (!list.containsKey(node.id)) {
                    list[node.id] = node
                    owner[node.id] = sub.id
                }
            }
            sub.autoBalancerTag?.let { tag ->
                val auto = autoNode(sub.id, tag)
                list[auto.id] = auto
                owner[auto.id] = sub.id
            }
        }
        owners = owner
        nodes = list.values.toList()
    }

    private fun pickBest(): String? {
        nodes.firstOrNull { it.supported && it.autoBalancerTag != null }?.let { return it.id }
        val alive = nodes.filter { it.supported && latencyOf(it.id) != null }
        val picked = if (alive.isNotEmpty()) {
            alive.minByOrNull { latencyOf(it.id) ?: Int.MAX_VALUE }?.id
        } else {
            nodes.firstOrNull { it.supported }?.id
        }
        return picked
    }

    private fun persist() {
        store.save(AppState(subscriptions = subscriptions, settings = settings))
    }

    private fun restore() {
        val state = store.load()
        val loaded = state.settings
        settings = (if (loaded.testUrl.isBlank() || loaded.testUrl == LEGACY_TEST_URL) {
            loaded.copy(testUrl = DEFAULT_TEST_URL)
        } else {
            loaded
        }).copy(mode = Mode.TUN)
        AndroidEnv.context.getSharedPreferences("limenet", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(LimeNetVpnService.KEY_PAUSE_VPN_WITH_SCREEN, settings.pauseVpnWithScreen).apply()
        VpnBridge.screenPaused = LimeNetVpnService.state(AndroidEnv.context) == LimeNetVpnService.STATE_PAUSED
        subscriptions = state.subscriptions
        rebuild()
        persist()
        // После перезапуска процесса конфиг мог остаться от прежнего узла.
        if (LimeNetVpnService.state(AndroidEnv.context) == LimeNetVpnService.STATE_STOPPED) {
            prepareConfig()
        }

        startLoops()
    }

    private fun startLoops() {
        // авто-обновление подписок
        scope.launch {
            while (isActive) {
                delay(if (settings.batterySaver) 2 * 60 * 60_000L else 30 * 60_000L)
                updateStaleSubscriptions()
            }
        }
        // Проверяем только активный узел и редко; остальные узлы пингуются
        // при запуске приложения, добавлении подписки или вручную.
        scope.launch {
            while (isActive) {
                delay(if (settings.batterySaver) 5 * 60_000L else 120_000L)
                if (connected && !testing && !pinging) checkConnectionHealth()
            }
        }
    }

    private suspend fun checkCoreFailure() {
        if (busy) {
            scope.launch {
                delay(2_000)
                checkCoreFailure()
            }
            return
        }
        val node = selected ?: return

        val reason = VpnBridge.message
        if (reason != null && !VpnBridge.running) {
            VpnBridge.message = null
            if (!settings.autoSwitch) {
                notify(reason, error = true)
                return
            }
            switchToBest(exclude = node)
        }
    }

    private suspend fun checkConnectionHealth() {
        if (busy) return
        val node = selected ?: return
        if (!connected) return

        val reachable = ping(node.server, node.port, 2500) != null
        if (reachable) {
            failStreak = 0
            return
        }
        failStreak++
        if (failStreak < 2) return
        failStreak = 0
        if (!settings.autoSwitch) {
            notify("Узел не отвечает", error = true)
            return
        }
        switchToBest(exclude = node)
    }

    private suspend fun switchToBest(exclude: ProxyNode?) {
        val candidates = nodes
            .filter { it.supported && it.id != exclude?.id }
            .sortedBy { latencyOf(it.id) ?: Int.MAX_VALUE }

        for (candidate in candidates.take(8)) {
            if (ping(candidate.server, candidate.port, 2000) == null) continue
            settings = settings.copy(selectedNodeId = candidate.id)
            persist()
            applyAndStart(reason = "Переключено на ${candidate.displayName}")
            return
        }
        notify("Не удалось найти рабочий узел", error = true)
    }

    // -------------------------------------------------------------------- сеть

    private fun fetch(url: String): Pair<String, SubMeta?> {
        val agents = (
            listOf(settings.userAgent.value) +
                UserAgentPreset.entries.map { it.value } +
                listOf("v2rayNG/1.9.16", "ClashMetaForAndroid/2.11.0")
            ).distinct()
        var lastError: Exception? = null

        for (agent in agents) {
            try {
                val (code, body, userInfo, announceHeader) = httpGet(url, agent)
                if (code !in 200..299) {
                    lastError = IllegalStateException("сервер ответил $code")
                    continue
                }
                if (Subscription.parse(body).nodes.isEmpty()) {
                    val page = body.trimStart().startsWith("<")
                    lastError = IllegalStateException(
                        if (page) "сервер вернул веб-страницу — смените User-Agent в настройках"
                        else "в ответе не найдено ни одного узла",
                    )
                    continue
                }
                val base = parseUserInfo(userInfo)
                val announce = announceHeader?.let { Subscription.announceValue(it) }
                    ?: Subscription.announce(body)
                val meta = if (announce != null) (base ?: SubMeta()).copy(announce = announce) else base
                return body to meta
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("не удалось загрузить подписку")
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
        val userInfo: String?,
        val announce: String?,
    )

    private fun httpGet(url: String, agent: String): HttpResult {
        var target = url
        var redirects = 0
        while (true) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 25_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", agent)
                setRequestProperty("Accept", "*/*")
                // Привязка устройства для панелей с HWID-лимитом.
                if (settings.hwid) {
                    MobileHwid.headers().forEach { (name, value) -> setRequestProperty(name, value) }
                }
            }
            try {
                val code = connection.responseCode
                if (code in 301..308 && redirects < 5) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        target = URI.create(target).resolve(location).toString()
                        redirects++
                        continue
                    }
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return HttpResult(
                    code = code,
                    body = body,
                    userInfo = connection.getHeaderField("subscription-userinfo"),
                    announce = connection.getHeaderField("announce"),
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseUserInfo(header: String?): SubMeta? {
        if (header == null) return null
        var meta = SubMeta()
        for (part in header.split(';')) {
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim().toLongOrNull() ?: continue
            meta = when (key) {
                "upload" -> meta.copy(upload = value)
                "download" -> meta.copy(download = value)
                "total" -> meta.copy(total = value)
                "expire" -> meta.copy(expire = value)
                else -> meta
            }
        }
        return meta
    }

    private fun titleOf(value: String): String {
        if (!value.startsWith("http")) return "Локальный конфиг"
        return runCatching { URI.create(value).host ?: "Подписка" }.getOrDefault("Подписка")
    }

    companion object {
        const val HTTP_PORT = 10809
        const val SOCKS_PORT = 10808
        const val SPEED_URL = "http://speed.cloudflare.com/__down?bytes=4000000"
        const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=0"
        const val URL_TEST_PARALLEL = 3
        const val TCP_TEST_PARALLEL = 12
        const val MIN_CHECK_MS = 700L

        /** Файл, куда ядро пишет логи (для диагностики). */
        val logFile: File get() = AndroidEnv.logFile
    }
}
