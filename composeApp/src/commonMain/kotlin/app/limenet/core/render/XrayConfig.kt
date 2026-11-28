package app.limenet.core.render

import app.limenet.ui.APP_NAME

import app.limenet.core.Mode
import app.limenet.core.ProxyNode
import app.limenet.core.ProxyProtocol
import app.limenet.core.TlsMode
import app.limenet.core.TransportType
import app.limenet.core.model.DnsMode
import app.limenet.core.model.RoutingPreset
import app.limenet.core.model.RuleAction
import app.limenet.core.model.RuleKind
import app.limenet.core.model.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object XrayConfig {

    const val TUN_NAME = "limenet0"
    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"
    const val TAG_DNS = "dns-out"

    /** Wrap a retained provider Xray config with LimeNet's platform-specific inbounds. */
    fun renderSubscription(
        providerConfig: JsonObject,
        settings: Settings,
        httpPort: Int,
        socksPort: Int,
        androidTun: Boolean = false,
    ): JsonObject {
        val config = providerConfig.toMutableMap()
        config["inbounds"] = buildJsonArray {
            if (settings.mode == Mode.TUN && androidTun) {
                add(buildJsonObject {
                    put("tag", "tun-in")
                    put("protocol", "tun")
                    putJsonObject("settings") { put("name", TUN_NAME); put("mtu", settings.tunMtu ?: 1500) }
                    put("sniffing", sniffing())
                })
                add(socksInbound(socksPort))
            } else {
                if (settings.mode == Mode.TUN) add(tunInbound(settings))
                localInbounds(httpPort, socksPort).forEach { add(it) }
            }
        }
        if ("log" !in config) config["log"] = buildJsonObject { put("loglevel", if (settings.mode == Mode.TUN) "info" else "warning") }
        return JsonObject(config)
    }

    private fun sniffing(): JsonObject = buildJsonObject {
        put("enabled", true)
        putJsonArray("destOverride") {
            add("http")
            add("tls")
            add("quic")
        }
    }

    /** IP-адрес в SNI недопустим: TLS-сертификат выписан на домен. */
    private fun isIpLiteral(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty()) return true
        if (text.contains(':')) return true
        val parts = text.split('.')
        return parts.size == 4 && parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    /**
     * Подписки часто присылают sni=<IP> и настоящий домен только в Host.
     * Берём первый домен из sni → host → адреса сервера.
     *
     * Возвращает null, если домена нет нигде: тогда проверять сертификат
     * не по чему, и вызывающий слой должен либо подставить имя из сертификата,
     * либо честно пометить узел как «сертификат не проверен».
     */
    fun effectiveServerName(node: ProxyNode): String? =
        listOfNotNull(node.tls.sni, node.transport.host, node.server)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !isIpLiteral(it) }

    private fun stream(node: ProxyNode, dialerProxy: String? = null): JsonObject = buildJsonObject {
        dialerProxy?.let { putJsonObject("sockopt") { put("dialerProxy", it) } }
        put("network", node.transport.type.wire)

        when (node.tls.mode) {
            TlsMode.REALITY -> {
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("serverName", effectiveServerName(node) ?: node.tls.sni ?: node.server)
                    put("fingerprint", node.tls.fingerprint ?: "chrome")
                    put("publicKey", node.tls.publicKey ?: "")
                    put("shortId", node.tls.shortId ?: "")
                    put("spiderX", node.tls.spiderX ?: "/")
                }
            }

            TlsMode.TLS -> {
                put("security", "tls")
                val sni = effectiveServerName(node)
                putJsonObject("tlsSettings") {
                    // IP в serverName недопустим — TLS-сертификат выписан на домен.
                    // Если домена нет, serverName не пишем: Xray возьмёт адрес
                    // сервера, проверка честно провалится, и узел будет помечен
                    // как «сертификат не проверен» (см. TlsProbe на desktop).
                    sni?.let { put("serverName", it) }
                    // allowInsecure в Xray 26 удалён (конфиг не запускается),
                    // вместо него — проверка по имени и пининг отпечатка.
                    node.tls.verifyName?.let { put("verifyPeerCertByName", it) }
                    if (node.tls.pinnedSha256.isNotEmpty()) {
                        putJsonArray("pinnedPeerCertSha256") {
                            node.tls.pinnedSha256.forEach { add(it) }
                        }
                    }
                    if (node.tls.alpn.isNotEmpty()) {
                        putJsonArray("alpn") { node.tls.alpn.forEach { add(it) } }
                    }
                    node.tls.fingerprint?.let { put("fingerprint", it) }
                }
            }

            TlsMode.NONE -> put("security", "none")
        }

        when (node.transport.type) {
            // Xray 26 больше не превращает независимое поле host в заголовок
            // Host, поэтому пишем его явно — как это делает рабочий клиент.
            TransportType.WS -> putJsonObject("wsSettings") {
                put("path", node.transport.path ?: "/")
                node.transport.host?.let {
                    putJsonObject("headers") { put("Host", it) }
                }
            }

            TransportType.GRPC -> putJsonObject("grpcSettings") {
                put("serviceName", node.transport.serviceName ?: "")
            }

            TransportType.HTTPUPGRADE -> putJsonObject("httpupgradeSettings") {
                put("path", node.transport.path ?: "/")
                put("host", node.transport.host ?: "")
            }

            TransportType.SPLITHTP -> putJsonObject("splithttpSettings") {
                put("path", node.transport.path ?: "/")
                put("host", node.transport.host ?: "")
            }

            TransportType.H2 -> putJsonObject("httpSettings") {
                put("path", node.transport.path ?: "/")
                putJsonArray("host") { node.transport.host?.let { add(it) } }
            }

            TransportType.TCP -> Unit
        }
    }

    fun outbound(node: ProxyNode, tag: String = TAG_PROXY, dialerProxy: String? = null): JsonObject = buildJsonObject {
        put("tag", tag)
        put("streamSettings", stream(node, dialerProxy))

        when (node.protocol) {
            ProxyProtocol.VLESS -> {
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", node.server)
                            put("port", node.port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", node.uuid ?: "")
                                    put("encryption", "none")
                                    node.flow?.let { put("flow", it) }
                                }
                            }
                        }
                    }
                }
            }

            ProxyProtocol.VMESS -> {
                put("protocol", "vmess")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", node.server)
                            put("port", node.port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", node.uuid ?: "")
                                    put("alterId", node.alterId)
                                    put("security", node.cipher ?: "auto")
                                }
                            }
                        }
                    }
                }
            }

            ProxyProtocol.TROJAN -> {
                put("protocol", "trojan")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", node.server)
                            put("port", node.port)
                            put("password", node.password ?: "")
                            node.flow?.let { put("flow", it) }
                        }
                    }
                }
            }

            ProxyProtocol.SHADOWSOCKS -> {
                put("protocol", "shadowsocks")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", node.server)
                            put("port", node.port)
                            put("method", node.method ?: "")
                            put("password", node.password ?: "")
                        }
                    }
                }
            }

            else -> throw IllegalArgumentException(
                "Протокол ${node.protocol.wire} пока не поддерживается движком",
            )
        }
    }

    private fun tunInbound(settings: Settings): JsonObject = buildJsonObject {
        put("tag", "tun-in")
        put("protocol", "tun")
        putJsonObject("settings") {
            put("name", TUN_NAME)
            put("desc", APP_NAME)
            put("mtu", settings.tunMtu ?: 1500)
            putJsonArray("gateway") {
                add("10.66.0.1/16")
                add("fc00:66::1/64")
            }
            putJsonArray("dns") { add(androidTunDns(settings.dns.remoteServer)) }
            putJsonArray("autoSystemRoutingTable") {
                add("0.0.0.0/0")
                add("::/0")
            }
            put("autoOutboundsInterface", "auto")
            if (settings.killSwitch) {
                putJsonArray("autoSystemWfpBlockLeak") {
                    add("dns")
                    add("misconfigtun")
                }
            }
        }
        put("sniffing", sniffing())
    }

    private fun localInbounds(httpPort: Int, socksPort: Int): List<JsonObject> = listOf(
        buildJsonObject {
            put("tag", "http-in")
            put("listen", "127.0.0.1")
            put("port", httpPort)
            put("protocol", "http")
            putJsonObject("settings") { put("allowTransparent", false) }
            put("sniffing", sniffing())
        },
        socksInbound(socksPort),
    )

    private fun socksInbound(socksPort: Int): JsonObject = buildJsonObject {
        put("tag", "socks-in")
        put("listen", "127.0.0.1")
        put("port", socksPort)
        put("protocol", "socks")
        putJsonObject("settings") {
            put("auth", "noauth")
            put("udp", true)
        }
        put("sniffing", sniffing())
    }

    private fun tagOf(action: RuleAction): String = when (action) {
        RuleAction.PROXY -> TAG_PROXY
        RuleAction.DIRECT -> TAG_DIRECT
        RuleAction.BLOCK -> TAG_BLOCK
    }

    private fun fieldRule(
        outbound: String,
        ip: List<String> = emptyList(),
        domain: List<String> = emptyList(),
        process: List<String> = emptyList(),
        port: String? = null,
        network: String? = null,
        inbound: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "field")
        if (ip.isNotEmpty()) putJsonArray("ip") { ip.forEach { add(it) } }
        if (domain.isNotEmpty()) putJsonArray("domain") { domain.forEach { add(it) } }
        if (process.isNotEmpty()) putJsonArray("process") { process.forEach { add(it) } }
        if (inbound.isNotEmpty()) putJsonArray("inboundTag") { inbound.forEach { add(it) } }
        port?.let { put("port", it) }
        network?.let { put("network", it) }
        put("outboundTag", outbound)
    }

    private fun customRule(rule: app.limenet.core.model.RoutingRule): JsonObject? {
        val value = rule.value.trim()
        if (value.isEmpty()) return null
        val outbound = tagOf(rule.action)
        return when (rule.kind) {
            RuleKind.GEOIP -> fieldRule(outbound, ip = listOf("geoip:$value"))
            RuleKind.GEOSITE -> fieldRule(outbound, domain = listOf("geosite:$value"))
            RuleKind.DOMAIN -> fieldRule(outbound, domain = listOf("full:$value"))
            RuleKind.DOMAIN_SUFFIX -> fieldRule(outbound, domain = listOf(value))
            RuleKind.IP_CIDR -> fieldRule(outbound, ip = listOf(value))
            RuleKind.PROCESS -> fieldRule(outbound, process = listOf(value))
        }
    }

    private fun routingRules(settings: Settings, dnsEnabled: Boolean): List<JsonObject> {
        val rules = mutableListOf<JsonObject>()

        // локальная сеть и localhost всегда напрямую
        rules += fieldRule(TAG_DIRECT, ip = listOf("geoip:private"))

        // DNS-запросы заворачиваем в dns-out, чтобы не было утечек
        if (dnsEnabled) rules += fieldRule(TAG_DNS, port = "53")

        // QUIC/HTTP3: при проблемах с UDP пользователь может принудить
        // приложения использовать TCP fallback.
        if (settings.blockQuic) rules += fieldRule(TAG_BLOCK, port = "443", network = "udp")

        // разделение трафика по приложениям
        if (settings.splitTunnel.directApps.isNotEmpty()) {
            rules += fieldRule(TAG_DIRECT, process = settings.splitTunnel.directApps)
        }
        if (settings.splitTunnel.proxyApps.isNotEmpty()) {
            rules += fieldRule(TAG_PROXY, process = settings.splitTunnel.proxyApps)
        }

        when (settings.routing) {
            RoutingPreset.RU_DIRECT -> {
                rules += fieldRule(TAG_DIRECT, domain = listOf("geosite:category-ru"))
                rules += fieldRule(TAG_DIRECT, ip = listOf("geoip:ru"))
            }

            RoutingPreset.CUSTOM -> settings.customRules.forEach { rule ->
                customRule(rule)?.let { rules += it }
            }

            RoutingPreset.ALL_PROXY -> Unit
        }

        // Всё, что не совпало, уходит в первый outbound — узел VPN.
        return rules
    }

    /**
     * Приводим адрес DNS-сервера к формату, который понимает Xray:
     * DoH — https://host/dns-query, DoT — tls://host.
     */
    private fun dnsAddress(mode: DnsMode, raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return value
        return when (mode) {
            DnsMode.DOH -> when {
                value.startsWith("https://") || value.startsWith("http://") -> value
                value.startsWith("tls://") ->
                    "https://" + value.removePrefix("tls://").trimEnd('/') + "/dns-query"
                else -> "https://" + value.trimEnd('/') + "/dns-query"
            }

            DnsMode.DOT -> when {
                value.startsWith("tls://") -> value
                value.startsWith("https://") || value.startsWith("http://") ->
                    "tls://" + value.substringAfter("://").substringBefore("/")
                else -> "tls://$value"
            }

            DnsMode.SYSTEM -> value
        }
    }

    fun androidTunDns(raw: String): String {
        val value = raw.lowercase()
        val address = raw.trim()
            .substringAfter("://", raw.trim())
            .substringBefore('/')
            .substringBefore('?')
            .removePrefix("[")
            .substringBefore(']')
            .substringBefore(':').takeIf { '.' in it }
            ?: raw.trim().substringAfter("://", raw.trim())
                .substringBefore('/')
                .substringBefore('?')
                .removePrefix("[")
                .substringBefore(']')
        val validIpv4 = address.split('.').let { octets ->
            octets.size == 4 && octets.all { part -> part.toIntOrNull() in 0..255 }
        }
        return when {
            "cloudflare" in value || "1.1.1.1" in value -> "1.1.1.1"
            "quad9" in value || "9.9.9.9" in value -> "9.9.9.9"
            "google" in value || "8.8.8.8" in value -> "8.8.8.8"
            validIpv4 -> address
            address.count { it == ':' } >= 2 && address.all { it == ':' || it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> address
            else -> "8.8.8.8"
        }
    }

    private fun dnsSection(settings: Settings): JsonObject = buildJsonObject {
        putJsonArray("servers") {
            // домены РФ резолвим напрямую, остальное — через защищённый DNS
            if (settings.routing == RoutingPreset.RU_DIRECT) {
                addJsonObject {
                    put("address", settings.dns.directServer.trim())
                    putJsonArray("domains") { add("geosite:category-ru") }
                }
            }
            addJsonObject {
                put("address", dnsAddress(settings.dns.mode, settings.dns.remoteServer))
            }
        }
        put("queryStrategy", settings.dns.queryStrategy)
    }

    fun render(node: ProxyNode, settings: Settings, httpPort: Int, socksPort: Int, chainEntryNode: ProxyNode? = null): JsonObject {
        val entry = chainEntryNode?.takeIf { it.supported && it.id != node.id }
        require(node.supported) { node.unsupportedReason ?: "узел не поддерживается" }

        val isTun = settings.mode == Mode.TUN
        val dnsEnabled = settings.dns.mode != DnsMode.SYSTEM

        return buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }

            putJsonArray("inbounds") {
                if (isTun) add(tunInbound(settings))
                localInbounds(httpPort, socksPort).forEach { add(it) }
            }

            putJsonArray("outbounds") {
                if (entry != null) add(outbound(entry, tag = "chain-entry"))
                add(outbound(node, dialerProxy = entry?.let { "chain-entry" }))
                if (dnsEnabled) {
                    addJsonObject {
                        put("tag", TAG_DNS)
                        put("protocol", "dns")
                    }
                }
                addJsonObject {
                    put("tag", TAG_DIRECT)
                    put("protocol", "freedom")
                }
                addJsonObject {
                    put("tag", TAG_BLOCK)
                    put("protocol", "blackhole")
                }
            }

            putJsonObject("routing") {
                put("domainStrategy", "IPIfNonMatch")
                putJsonArray("rules") {
                    routingRules(settings, dnsEnabled).forEach { add(it) }
                }
            }

            if (dnsEnabled) put("dns", dnsSection(settings))
        }
    }

    /**
     * Конфиг Android-клиента: TUN получает дескриптор от VpnService через
     * `XRAY_TUN_FD`; локальный SOCKS-вход нужен приложению для проб через этот движок.
     */
    fun renderTun(
        node: ProxyNode,
        settings: Settings,
        chainEntryNode: ProxyNode? = null,
        socksPort: Int = 10808,
    ): JsonObject {
        val entry = chainEntryNode?.takeIf { it.supported && it.id != node.id }
        require(node.supported) { node.unsupportedReason ?: "узел не поддерживается" }

        val dnsEnabled = settings.dns.mode != DnsMode.SYSTEM

        return buildJsonObject {
            // AndroidTun пишет принятый XRAY_TUN_FD и ошибки инициализации на
            // info; оставляем этот уровень, чтобы отличить старт процесса от
            // реально подключённого системного TUN.
            putJsonObject("log") { put("loglevel", "info") }

            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "tun-in")
                    put("protocol", "tun")
                    putJsonObject("settings") {
                        put("name", TUN_NAME)
                        put("mtu", settings.tunMtu ?: 1500)
                    }
                    put("sniffing", sniffing())
                }
                add(socksInbound(socksPort))
            }

            putJsonArray("outbounds") {
                if (entry != null) add(outbound(entry, tag = "chain-entry"))
                add(outbound(node, dialerProxy = entry?.let { "chain-entry" }))
                if (dnsEnabled) {
                    addJsonObject {
                        put("tag", TAG_DNS)
                        put("protocol", "dns")
                    }
                }
                addJsonObject {
                    put("tag", TAG_DIRECT)
                    put("protocol", "freedom")
                }
                addJsonObject {
                    put("tag", TAG_BLOCK)
                    put("protocol", "blackhole")
                }
            }

            putJsonObject("routing") {
                put("domainStrategy", "IPIfNonMatch")
                putJsonArray("rules") {
                    routingRules(settings, dnsEnabled).forEach { add(it) }
                }
            }

            if (dnsEnabled) put("dns", dnsSection(settings))
        }
    }

    /**
     * Минимальный конфиг для проверки узла: только локальный SOCKS-вход и
     * выбранный узел. Так тест не зависит от системных настроек и не мешает
     * основному подключению.
     */
    fun renderTest(node: ProxyNode, socksPort: Int): JsonObject {
        require(node.supported) { node.unsupportedReason ?: "узел не поддерживается" }

        return buildJsonObject {
            putJsonObject("log") { put("loglevel", "error") }

            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "socks-in")
                    put("listen", "127.0.0.1")
                    put("port", socksPort)
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        put("auth", "noauth")
                        put("udp", false)
                    }
                }
            }

            putJsonArray("outbounds") {
                add(outbound(node))
                addJsonObject {
                    put("tag", TAG_DIRECT)
                    put("protocol", "freedom")
                }
            }
        }
    }
}
