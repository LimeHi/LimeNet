package app.limenet.desktop

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
import app.limenet.core.model.CoreEngine
import app.limenet.core.model.SubscriptionInfo
import app.limenet.core.model.TestMethod
import app.limenet.core.model.TestFailure
import app.limenet.core.model.UserAgentPreset
import app.limenet.core.model.POPULAR_SERVICES
import app.limenet.core.parse.Subscription
import app.limenet.core.parseCatalog
import app.limenet.core.render.XrayConfig
import app.limenet.core.render.SingBoxConfig
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private const val CATALOG_URL = "https://raw.githubusercontent.com/LimeHi/LimeNet/refs/heads/main/needlinks.txt"

class Controller(private val scope: CoroutineScope) {

    private val xrayRunner = XrayRunner(Platform.xrayExe, Platform.coreDir)
    private val singBoxRunner = SingBoxRunner(Platform.singBoxExe, Platform.coreDir)
    private val runner: CoreRunner get() = if (settings.engine == CoreEngine.SING_BOX) singBoxRunner else xrayRunner
    private val tester = UrlTester(Platform.xrayExe, File(Platform.coreDir, "tests"))
    private val availabilityTester = UrlTester(Platform.xrayExe, File(Platform.coreDir, "connectivity-tests"))
    private val serviceTester = UrlTester(Platform.xrayExe, File(Platform.coreDir, "service-checks"))
    private val store = Store()
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

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
    /** Идёт проверка серверов, запущенная пользователем (кнопка теста). */
    var testing by mutableStateOf(false)
        private set

    /** Узлы, которые ещё ждут своей очереди в текущей проверке. */
    var checkQueue by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Узлы, которые проверяются прямо сейчас. */
    var checkActive by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Прогресс текущей проверки: сколько узлов уже получили результат из скольких. */
    var checkDone by mutableStateOf(0)
        private set
    var checkTotal by mutableStateOf(0)
        private set

    /** Что проверяется: `null` — все подписки, иначе id одной подписки. */
    var checkScope by mutableStateOf<String?>(null)
        private set

    /** Идёт «обновить все». */
    var updatingAll by mutableStateOf(false)
        private set

    /** Итог последнего обновления подписки (id → успех). Держится ~1.6 с ради анимации кнопки. */
    var refreshOutcomes by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set
    var busy by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    var connectedAt by mutableStateOf(0L)
        private set
    var isAdmin by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var logs by mutableStateOf<List<String>>(emptyList())
        private set
    var protocolRegistered by mutableStateOf(false)
        private set
    var updating by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set

    private var owners: Map<String, String> = emptyMap()
    private var pinging = false
    private var checkJob: Job? = null
    private val checkSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var failStreak = 0
    private var restartJob: Job? = null
    private var messageJob: Job? = null
    private var lastAppEntryChecksAt = 0L
    private var serviceCheckJob: Job? = null
    private var serviceCheckNodeId: String? = null
    private var serviceCheckRun = 0
    private var speedTestJob: Job? = null
    private var speedTestRun = 0

    /** Узлы без домена в ссылке, которым имя сертификата подобрали один раз. */
    private val repaired = mutableMapOf<String, ProxyNode>()

    /** Пояснения по сертификатам: что проверено, а что нет. */
    var certNotes by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    val selectedId: String? get() = settings.selectedNodeId
    val selected: ProxyNode? get() = nodes.firstOrNull { it.id == selectedId }

    init {
        isAdmin = Platform.isElevated()
        restore()
        loadCatalog()
    }

    private fun loadCatalog() {
        val cacheFile = File(System.getProperty("user.home"), ".limenet/subscription-catalog.txt")
        runCatching { cacheFile.takeIf { it.isFile }?.readText() }.getOrNull()
            ?.let(::parseCatalog)?.takeIf { it.isNotEmpty() }?.let { catalog = it }
        refreshCatalog()
    }

    fun refreshCatalog() {
        if (catalogLoading) return
        val cacheFile = File(System.getProperty("user.home"), ".limenet/subscription-catalog.txt")
        scope.launch {
            catalogLoading = true
            catalogError = null
            try {
                val remote = withContext(Dispatchers.IO) {
                    val response = http.send(
                        HttpRequest.newBuilder(URI.create(CATALOG_URL))
                            .timeout(Duration.ofSeconds(20))
                            .header("Cache-Control", "no-cache")
                            .header("User-Agent", "LimeNet")
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
                    response.body()
                }
                val parsed = parseCatalog(remote)
                check(parsed.isNotEmpty()) { "Файл получен, но каталог пуст или формат записей не распознан" }
                catalog = parsed
                withContext(Dispatchers.IO) {
                    runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(remote) }
                }
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
                    if (existing == null) {
                        "Добавлено ${parsed.nodes.size} узлов"
                    } else {
                        "Подписка обновлена: ${parsed.nodes.size} узлов"
                    },
                )
                pingAll()
            } catch (error: Exception) {
                notify(error.message ?: "не удалось добавить подписку", error = true)
            } finally {
                busy = false
                logs = runner.logs
            }
        }
    }

    fun removeSubscription(id: String) {
        val owned = nodesOf(id).map { it.id }.toSet()
        if (connected && selectedId in owned) {
            scope.launch { disconnect() }
        }
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

    /** Своё название подписки; сохраняется и переживает обновления. */
    fun renameSubscription(id: String, title: String) {
        val name = title.trim()
        if (name.isEmpty() || subscription(id) == null) return
        subscriptions = subscriptions.map { if (it.id == id) it.copy(title = name) else it }
        persist()
    }

    /** Полный клиентский конфиг Xray для узла (тот же, что уходит в ядро) — для копирования. */
    fun nodeConfigJson(node: ProxyNode): String {
        if (node.autoBalancerTag != null) {
            val providerConfig = subscriptionOf(node.id)?.xrayConfigJson
                ?.let { Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }
                ?: error("Конфигурация авто-группы не сохранена")
            val config = XrayConfig.renderSubscription(providerConfig, settings, HTTP_PORT, SOCKS_PORT)
            return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), config)
        }
        val config = when (settings.engine) {
            CoreEngine.XRAY -> XrayConfig.render(repaired[node.id] ?: node, settings, HTTP_PORT, SOCKS_PORT, chainEntry())
            CoreEngine.SING_BOX -> SingBoxConfig.render(repaired[node.id] ?: node, settings, HTTP_PORT, SOCKS_PORT, chainEntry())
        }
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
                    if (sub.url == null) continue // локальные подписки обновляются только вручную
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
            logs = runner.logs
        }
    }

    /** Короткая вспышка результата на кнопке обновления: галочка или крестик. */
    private fun flashOutcome(id: String, ok: Boolean) {
        refreshOutcomes = refreshOutcomes + (id to ok)
        scope.launch {
            delay(1_600)
            if (refreshOutcomes[id] == ok) refreshOutcomes = refreshOutcomes - id
        }
    }

    /** Причина сбоя по-русски и коротко: сырые тексты исключений пользователю не нужны. */
    private fun describe(error: Exception): String = when (error) {
        is java.net.http.HttpTimeoutException -> "превышено время ожидания"
        is java.net.UnknownHostException -> "адрес не найден"
        is java.net.ConnectException -> "нет соединения"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "неизвестная ошибка"
    }

    private suspend fun updateStaleSubscriptions() {
        val hours = settings.autoUpdateHours
        if (hours <= 0) return
        val now = System.currentTimeMillis()
        val stale = subscriptions.filter {
            it.url != null && now - it.updatedAt > hours * 3_600_000L
        }
        for (sub in stale) doRefresh(sub, silent = true)
    }

    /** Проверки при запуске приложения; также защищает от повторного вызова за короткий срок. */
    private fun onAppEntered() {
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
        if (node.autoBalancerTag != null && settings.engine != CoreEngine.XRAY) {
            notify("Авто-группа подписки требует движок Xray", error = true)
            return
        }
        settings = settings.copy(selectedNodeId = id)
        persist()
        if (connected) scheduleRestart()
    }

    fun updateMode(mode: Mode) {
        updateSettings { it.copy(mode = mode) }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = settings
        val after = transform(before)
        if (after == before) return
        if (selected?.autoBalancerTag != null && after.engine != CoreEngine.XRAY) {
            notify("Сначала выберите движок Xray для авто-группы подписки", error = true)
            return
        }
        settings = after
        persist()

        val connectionChanged =
                before.mode != after.mode ||
                before.engine != after.engine ||
                before.routing != after.routing ||
                before.customRules != after.customRules ||
                before.dns != after.dns ||
                before.tunMtu != after.tunMtu ||
                before.blockQuic != after.blockQuic ||
                before.chainEntryNodeId != after.chainEntryNodeId ||
                before.tlsFragment != after.tlsFragment ||
                before.tlsRecordFragment != after.tlsRecordFragment ||
                before.killSwitch != after.killSwitch ||
                before.splitTunnel != after.splitTunnel

        if (connected && connectionChanged) scheduleRestart()
    }

    fun clearMessage() {
        messageJob?.cancel()
        message = null
        messageIsError = false
    }

    /** Короткое сообщение из интерфейса (например, «ссылка скопирована»). */
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

    fun recheckProtocol() {
        scope.launch {
            protocolRegistered = withContext(Dispatchers.IO) { Platform.registerProtocol() }
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
        if (busy) return
        if (connected) {
            scope.launch { disconnect() }
        } else {
            connect()
        }
    }

    private fun connect() {
        val node = selected?.takeIf { it.supported } ?: nodes.firstOrNull { it.supported }
        if (node == null) {
            notify("Нет узлов — добавьте подписку", error = true)
            return
        }
        if (node.autoBalancerTag != null && settings.engine != CoreEngine.XRAY) {
            notify("Авто-группа подписки требует движок Xray", error = true)
            return
        }

        busy = true
        clearMessage()
        scope.launch {
            try {
                if (settings.mode == Mode.TUN && !isAdmin) {
                    error("Для TUN нужны права администратора")
                }
                val responsive = if (node.autoBalancerTag != null) node else withContext(Dispatchers.IO) { findResponsiveNode(node) }
                    ?: error("узлы подписки не прошли проверку доступности")
                if (responsive.id != selectedId) {
                    settings = settings.copy(selectedNodeId = responsive.id)
                    persist()
                }
                startCore(responsive)
                connected = true
                connectedAt = System.currentTimeMillis()
                failStreak = 0
            } catch (error: Exception) {
                connected = false
                connectedAt = 0L
                notify(error.message ?: "ошибка подключения", error = true)
            } finally {
                logs = runner.logs
                busy = false
            }
        }
    }

    /** Проверяет выбранный сервер реальным запросом и ищет замену только в той же подписке. */
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

    private suspend fun disconnect() {
        busy = true
        try {
            withContext(Dispatchers.IO) {
                runner.stop()
                Platform.setSystemProxy(false)
            }
            connected = false
            connectedAt = 0L
        } finally {
            logs = runner.logs
            busy = false
        }
    }

    /**
     * Если в ссылке нет домена для проверки сертификата, один раз выясняем имя
     * у самого сервера и запоминаем результат. Такой узел помечается в
     * [certNotes] — пользователь должен видеть, что доверие получено по TOFU.
     */
    @Synchronized
    private fun repair(node: ProxyNode): ProxyNode {
        if (node.tls.mode == TlsMode.NONE) return node
        if (XrayConfig.effectiveServerName(node) != null) return node
        repaired[node.id]?.let { return it }

        val info = TlsProbe.probe(node.server, node.port)
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

    private suspend fun startCore(node: ProxyNode) {
        val snapshot = settings
        if (snapshot.engine == CoreEngine.SING_BOX && node.autoBalancerTag != null) {
            error("Авто-группа подписки требует движок Xray")
        }
        val prepared = withContext(Dispatchers.IO) { repair(node) }
        val config = withContext(Dispatchers.Default) {
            if (node.autoBalancerTag != null) {
                val sub = subscriptionOf(node.id) ?: error("подписка авто-узла не найдена")
                val rawConfig = sub.xrayConfigJson ?: error("конфигурация авто-группы не сохранена")
                val providerConfig = Json.parseToJsonElement(rawConfig) as? JsonObject
                    ?: error("конфигурация авто-группы повреждена")
                XrayConfig.renderSubscription(providerConfig, snapshot, HTTP_PORT, SOCKS_PORT)
            } else when (snapshot.engine) {
                CoreEngine.XRAY -> XrayConfig.render(prepared, snapshot, HTTP_PORT, SOCKS_PORT, chainEntry(snapshot))
                CoreEngine.SING_BOX -> SingBoxConfig.render(prepared, snapshot, HTTP_PORT, SOCKS_PORT, chainEntry(snapshot))
            }
        }
        withContext(Dispatchers.IO) {
            runner.validate(config)
            runner.start(config, SOCKS_PORT)
            Platform.setSystemProxy(
                on = snapshot.mode == Mode.PROXY,
                server = "127.0.0.1:$HTTP_PORT",
            )
        }
    }

    private fun chainEntry(snapshot: Settings = settings): ProxyNode? = snapshot.chainEntryNodeId
        ?.let { id -> nodes.firstOrNull { it.id == id && it.supported && it.id != snapshot.selectedNodeId } }
        ?.let { repaired[it.id] ?: it }

    private suspend fun reconnect(): Boolean {
        val node = selected ?: return false
        return try {
            if (node.autoBalancerTag != null && settings.engine != CoreEngine.XRAY) error("Авто-группа подписки требует движок Xray")
            startCore(node)
            connected = true
            if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
            failStreak = 0
            true
        } catch (error: Exception) {
            connected = false
            connectedAt = 0L
            notify(error.message ?: "ошибка подключения", error = true)
            false
        }
    }

    private fun scheduleRestart() {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(800)
            if (!connected || busy) return@launch
            busy = true
            try {
                if (reconnect()) notify("Настройки применены")
            } finally {
                logs = runner.logs
                busy = false
            }
        }
    }

    // ------------------------------------------------------------------- тесты

    /** Проверка всех серверов всех подписок способом, выбранным в настройках (URL или TCP). */
    fun testAll() = runCheck(null)

    /** Проверка серверов одной подписки тем же способом. */
    fun testSubscription(id: String) = runCheck(id)

    /** Отменяет очередь проверок и немедленно закрывает ресурсы URL-тестов. */
    fun cancelCheck() {
        if (!testing) return
        tester.cancelActive()
        checkSockets.toList().forEach { runCatching { it.close() } }
        checkJob?.cancel()
        notify("проверка остановлена")
    }

    /** Идёт ли сейчас проверка, затрагивающая эту подписку (её собственная или «все»). */
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

    /**
     * Общий запуск проверки. Узлы идут через семафор, а состояние (очередь, активные,
     * прогресс) обновляется по мере готовности каждого узла — интерфейс видит живой
     * прогресс и не выглядит зависшим.
     */
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
                // слишком быстрая проверка (TCP) мигнула бы и пропала — даём анимации показаться
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
                logs = runner.logs
                checkJob = null
            }
            val title = when (method) {
                TestMethod.URL -> "url-тест"
                TestMethod.TCP -> "tcp-тест"
                TestMethod.TCP_URL -> "tcp + url-тест"
            }
            if (ok == 0) {
                notify("$title · серверы недоступны", error = true)
            } else {
                notify("$title · доступно $ok из ${targets.size}")
            }
        }
    }

    /**
     * Задержка узла для выбора «самого быстрого» и для надписей: в режиме URL — результат
     * URL-теста (если он был, иначе TCP-пинг), в режиме TCP — только TCP-пинг.
     * `null` — не измерено или недоступен.
     */
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
            val result = mutableMapOf<String, Int?>()
            for (chunk in targets.chunked(32)) {
                val part = withContext(Dispatchers.IO) {
                    coroutineScope {
                        chunk.map { node ->
                            async { node.id to ping(node.server, node.port) }
                        }.awaitAll()
                    }
                }
                result.putAll(part.toMap())
                pings = pings + part.toMap()
            }
            if (result.isNotEmpty()) pings = pings + result
        } finally {
            pinging = false
        }
    }

    fun urlTestNode(id: String) {
        val node = nodes.firstOrNull { it.id == id } ?: return
        if (!node.supported || testing) return
        testing = true
        checkScope = owners[id]
        checkTotal = 1
        checkDone = 0
        checkQueue = emptySet()
        checkActive = setOf(id)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    tester.test(repair(node), settings.testUrl.ifBlank { DEFAULT_TEST_URL }, speedUrl = SPEED_URL)
                }
                urlResults = urlResults + (node.id to result)
                checkDone = 1
            } finally {
                testing = false
                checkActive = emptySet()
            }
        }
    }

    fun speedTest(id: String) {
        val node = nodes.firstOrNull { it.id == id } ?: return
        if (!node.supported || testing) return
        testing = true
        checkScope = owners[id]
        checkTotal = 1
        checkDone = 0
        checkQueue = emptySet()
        checkActive = setOf(id)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    tester.test(repair(node), settings.testUrl.ifBlank { DEFAULT_TEST_URL }, speedUrl = SPEED_URL)
                }
                urlResults = urlResults + (node.id to result)
                checkDone = 1
            } finally {
                testing = false
                checkActive = emptySet()
            }
        }
    }

    // --------------------------------------------------------------- служебное

    fun relaunchElevated() {
        val result = Platform.relaunchElevated()
        if (result.isSuccess) {
            shutdown()
            System.exit(0)
        } else {
            notify(
                "Не удалось перезапустить: ${result.exceptionOrNull()?.message}",
                error = true,
            )
        }
    }

    fun shutdown() {
        restartJob?.cancel()
        runCatching { runner.stop() }
        runCatching { Platform.setSystemProxy(false) }
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

    private suspend fun fetch(url: String): Pair<String, SubMeta?> = withContext(Dispatchers.IO) {
        // выбранный в настройках клиент идёт первым, остальные — запасные
        val agents = (
            listOf(settings.userAgent.value) +
                UserAgentPreset.entries.map { it.value } +
                listOf("v2rayNG/1.9.16", "ClashMetaForAndroid/2.11.0")
            ).distinct()
        var lastError: Exception? = null

        for (agent in agents) {
            try {
                val builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", agent)
                    .header("Accept", "*/*")
                // Привязка устройства для панелей с HWID-лимитом.
                if (settings.hwid) {
                    Hwid.headers().forEach { (name, value) -> builder.header(name, value) }
                }
                val request = builder.GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    lastError = IllegalStateException("сервер ответил ${response.statusCode()}")
                    continue
                }
                val body = response.body()
                if (Subscription.parse(body).nodes.isEmpty()) {
                    val page = body.trimStart().startsWith("<")
                    lastError = IllegalStateException(
                        if (page) "сервер вернул веб-страницу — смените User-Agent в настройках" else "в ответе не найдено ни одного узла",
                    )
                    continue
                }
                val base = parseUserInfo(
                    response.headers().firstValue("subscription-userinfo").orElse(null),
                )
                val announce = response.headers().firstValue("announce").orElse(null)
                    ?.let { Subscription.announceValue(it) }
                    ?: Subscription.announce(body)
                val meta = if (announce != null) (base ?: SubMeta()).copy(announce = announce) else base
                return@withContext body to meta
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("не удалось загрузить подписку")
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

    private fun persist() {
        store.save(AppState(subscriptions = subscriptions, settings = settings))
    }

    private fun restore() {
        val state = store.load()
        // Старый URL по умолчанию (cloudflare) и пустое значение заменяем на актуальный.
        val loaded = state.settings
        settings = if (loaded.testUrl.isBlank() || loaded.testUrl == LEGACY_TEST_URL) {
            loaded.copy(testUrl = DEFAULT_TEST_URL)
        } else {
            loaded
        }
        subscriptions = state.subscriptions
        rebuild()
        persist()
        logs = runner.logs

        scope.launch {
            protocolRegistered = withContext(Dispatchers.IO) { Platform.registerProtocol() }
        }
        onAppEntered()
        startLoops()
    }

    private fun startLoops() {
        // авто-пинг: держим задержки свежими
        scope.launch {
            while (isActive) {
                delay(if (settings.batterySaver) 5 * 60_000L else 30_000L)
                if (!settings.batterySaver && !testing && !pinging) runPings()
            }
        }

        // авто-обновление подписок
        scope.launch {
            while (isActive) {
                delay(if (settings.batterySaver) 2 * 60 * 60_000L else 30 * 60_000L)
                updateStaleSubscriptions()
            }
        }

        // watchdog: узел или ядро упали → автопереключение
        scope.launch {
            while (isActive) {
                delay(if (settings.batterySaver) 30_000L else 8_000L)
                checkHealth()
            }
        }
    }

    private suspend fun checkHealth() {
        if (!connected || busy) return
        val node = selected ?: return

        val coreAlive = runner.running
        val reachable = coreAlive && ping(node.server, node.port, 2500) != null
        if (reachable) {
            failStreak = 0
            return
        }

        failStreak++
        if (failStreak < 2) return
        failStreak = 0
        logs = runner.logs

        if (!settings.autoSwitch) {
            notify(
                if (coreAlive) "Узел не отвечает" else "Ядро остановилось",
                error = true,
            )
            return
        }

        if (!coreAlive && reconnect()) {
            notify("Соединение восстановлено")
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
            if (reconnect()) {
                notify("Переключено на ${candidate.displayName}")
                return
            }
        }
        notify("Не удалось найти рабочий узел", error = true)
    }

    companion object {
        const val HTTP_PORT = 10809
        const val SOCKS_PORT = 10808
        const val SPEED_URL = "http://speed.cloudflare.com/__down?bytes=4000000"
        const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=0"

        /** Сколько узлов проверяется URL-тестом одновременно (каждый поднимает свой Xray). */
        const val URL_TEST_PARALLEL = 4

        /** TCP-замер лёгкий, поэтому узлов одновременно больше. */
        const val TCP_TEST_PARALLEL = 12

        /** Минимум времени, которое видна анимация проверки. */
        const val MIN_CHECK_MS = 700L
    }
}
