package app.limenet.core.model

import app.limenet.core.Mode
import kotlinx.serialization.Serializable

@Serializable
enum class CoreEngine { XRAY, SING_BOX }

@Serializable
enum class RoutingPreset { ALL_PROXY, RU_DIRECT, CUSTOM }

@Serializable
enum class DnsMode { SYSTEM, DOH, DOT }

@Serializable
enum class RuleKind { GEOIP, GEOSITE, DOMAIN, DOMAIN_SUFFIX, IP_CIDR, PROCESS }

@Serializable
enum class RuleAction { PROXY, DIRECT, BLOCK }

/** Результат проверки доступности. */
@Serializable
enum class TestFailure { TIMEOUT, UNAVAILABLE }

/** Как проверять серверы: URL, TCP или сначала TCP, затем реальный URL-запрос. */
@Serializable
enum class TestMethod { URL, TCP, TCP_URL }

/**
 * Каким клиентом представляться серверу подписки. Многие панели отдают список только
 * известным клиентам, а незнакомому — пустой ответ или HTML-страницу.
 */
@Serializable
enum class UserAgentPreset(val label: String, val value: String) {
    LIMENET("LimeNet", "LimeNet/0.1"),
    HAPP("Happ", "Happ/3.24.1"),
    INCY("Incy", "Incy/1.0"),
}

/** URL проверки по умолчанию. */
const val DEFAULT_TEST_URL = "http://www.gstatic.com/generate_204"

/** Прежний URL по умолчанию: при загрузке старых настроек заменяется на [DEFAULT_TEST_URL]. */
const val LEGACY_TEST_URL = "http://cp.cloudflare.com/generate_204"

/** Готовые URL для проверки: название на кнопке и адрес. Свой адрес вводится в поле. */
val TEST_URL_PRESETS: List<Pair<String, String>> = listOf(
    "gstatic" to DEFAULT_TEST_URL,
    "Cloudflare" to "http://cp.cloudflare.com/generate_204",
    "Google" to "http://www.google.com/generate_204",
    "Apple" to "http://captive.apple.com/hotspot-detect.html",
)

@Serializable
data class RoutingRule(
    val kind: RuleKind = RuleKind.GEOSITE,
    val value: String = "",
    val action: RuleAction = RuleAction.DIRECT,
)

/** Разбивает введённый список доменов/IP на отдельные значения для правил маршрутизации. */
fun parseRoutingRuleValues(input: String): List<String> = input
    .split(Regex("[,;\\s]+"))
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

@Serializable
data class DnsSettings(
    val mode: DnsMode = DnsMode.DOH,
    val remoteServer: String = "https://dns.google/dns-query",
    val directServer: String = "8.8.8.8",
    val queryStrategy: String = "UseIP",
)

/** Разделение трафика по приложениям (Split Tunneling). */
@Serializable
data class SplitTunnel(
    val directApps: List<String> = emptyList(),
    val proxyApps: List<String> = emptyList(),
)

@Serializable
data class Settings(
    val engine: CoreEngine = CoreEngine.XRAY,
    val mode: Mode = Mode.TUN,
    val routing: RoutingPreset = RoutingPreset.RU_DIRECT,
    val customRules: List<RoutingRule> = emptyList(),
    val dns: DnsSettings = DnsSettings(),
    /** null = automatic/default MTU; otherwise one of the supported TUN MTUs. */
    val tunMtu: Int? = null,
    /** Route UDP/443 to block so apps such as YouTube fall back from QUIC to TCP. */
    val blockQuic: Boolean = false,
    /** Sing-box-only TLS ClientHello fragmentation; ignored by Xray. */
    val tlsFragment: Boolean = false,
    /** Sing-box-only TLS record fragmentation; ignored by Xray. */
    val tlsRecordFragment: Boolean = false,
    val killSwitch: Boolean = true,
    val autoSwitch: Boolean = true,
    /** Pause an active Android VPN while the display is off, then resume it when it turns on. */
    val pauseVpnWithScreen: Boolean = false,
    val autoUpdateHours: Int = 12,
    /** Обновлять просроченные по интервалу подписки при входе в приложение. */
    val updateSubscriptionsOnLaunch: Boolean = true,
    /** Проверять задержку всех серверов при входе в приложение. */
    val pingServersOnLaunch: Boolean = true,
    /** Сокращает фоновые сетевые проверки, не приостанавливая VPN. */
    val batterySaver: Boolean = false,
    val splitTunnel: SplitTunnel = SplitTunnel(),
    val selectedNodeId: String? = null,
    val chainEntryNodeId: String? = null,
    val showSpeed: Boolean = true,
    val activeSubscriptionId: String? = null,
    val testUrl: String = DEFAULT_TEST_URL,
    val testMethod: TestMethod = TestMethod.URL,
    val userAgent: UserAgentPreset = UserAgentPreset.LIMENET,
    /**
     * Привязка к устройству (HWID): отправлять панели заголовки `x-hwid`,
     * `x-device-os`, `x-ver-os`, `x-device-model`. Нужно для панелей
     * Remnawave/Marzban с лимитом устройств на подписку.
     */
    val hwid: Boolean = true,
)

/** Сохранённая подписка: храним ссылки, а не разобранные узлы. */
@Serializable
data class SubscriptionInfo(
    val id: String,
    val title: String = "",
    val url: String? = null,
    val raw: List<String> = emptyList(),
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val updatedAt: Long = 0,
    /** Объявление провайдера; пусто — нет. */
    val announce: String = "",
    /** Original provider Xray config, retained alongside separately listed server nodes. */
    val xrayConfigJson: String? = null,
    /** Provider or generated Xray balancer exposed as the subscription's "Авто" entry. */
    val autoBalancerTag: String? = null,
    /** UI id of the synthetic provider auto entry. */
    val autoNodeId: String? = null,
    /** Последняя ошибка автообновления; очищается при успешном обновлении. */
    val refreshError: String? = null,
) {
    val used: Long get() = upload + download
}

@Serializable
data class AppState(
    val subscriptions: List<SubscriptionInfo> = emptyList(),
    val settings: Settings = Settings(),
)
