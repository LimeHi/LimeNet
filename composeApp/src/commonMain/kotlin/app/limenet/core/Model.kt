package app.limenet.core

import kotlinx.serialization.Serializable

enum class ProxyProtocol(val wire: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    SHADOWSOCKS("shadowsocks"),
    HYSTERIA2("hysteria2"),
    TUIC("tuic"),
    UNKNOWN("unknown"),
}

enum class TlsMode { NONE, TLS, REALITY }

enum class TransportType(val wire: String) {
    TCP("tcp"),
    WS("ws"),
    GRPC("grpc"),
    HTTPUPGRADE("httpupgrade"),
    SPLITHTP("splithttp"),
    H2("h2"),
}

@Serializable
enum class Mode { TUN, PROXY }

data class TlsOptions(
    val mode: TlsMode = TlsMode.NONE,
    val sni: String? = null,
    val alpn: List<String> = emptyList(),
    val fingerprint: String? = null,
    /** В Xray 26 поле удалено; храним для совместимости разбора ссылок. */
    val allowInsecure: Boolean = false,
    val publicKey: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null,
    /** verifyPeerCertByName: проверять сертификат по этому имени. */
    val verifyName: String? = null,
    /** pinnedPeerCertSha256: отпечатки сертификата из подписки. */
    val pinnedSha256: List<String> = emptyList(),
)

data class TransportOptions(
    val type: TransportType = TransportType.TCP,
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,
)

data class ProxyNode(
    val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val server: String,
    val port: Int,
    val uuid: String? = null,
    val password: String? = null,
    val method: String? = null,
    val flow: String? = null,
    val alterId: Int = 0,
    val cipher: String? = null,
    val tls: TlsOptions = TlsOptions(),
    val transport: TransportOptions = TransportOptions(),
    val supported: Boolean = true,
    val unsupportedReason: String? = null,
    val raw: String = "",
    /** Non-null only for a provider/client-generated Xray balancer UI entry. */
    val autoBalancerTag: String? = null,
) {
    val country: String? get() = Flags.codeOf(name)
    val shortName: String get() = Flags.label(name)
    val displayName: String get() = Flags.decorate(name)
}

data class SubMeta(
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,
    /** Объявление провайдера (`#announce` / заголовок `announce`). */
    val announce: String? = null,
)

/** Копия узла с подставленным именем для проверки TLS-сертификата. */
fun ProxyNode.withServerName(name: String): ProxyNode = copy(tls = tls.copy(sni = name))

data class SubResult(
    val nodes: List<ProxyNode> = emptyList(),
    val skipped: Int = 0,
    /** Original Xray JSON, retained so balancers, observatories and routing survive import. */
    val xrayConfigJson: String? = null,
    val autoBalancerTag: String? = null,
)

/** UI-only selectable entry; its traffic must be sent to [balancerTag], not a server outbound. */
fun autoNode(subscriptionId: String, balancerTag: String): ProxyNode = ProxyNode(
    id = "auto-${Codec.fnv1a("$subscriptionId:$balancerTag")}",
    name = "Авто",
    protocol = ProxyProtocol.UNKNOWN,
    server = "",
    port = 0,
    supported = true,
    raw = "auto://$balancerTag",
    autoBalancerTag = balancerTag,
)
