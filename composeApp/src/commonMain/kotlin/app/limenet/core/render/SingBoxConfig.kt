package app.limenet.core.render

import app.limenet.core.ProxyNode
import app.limenet.core.ProxyProtocol
import app.limenet.core.TlsMode
import app.limenet.core.TransportType
import app.limenet.core.Mode
import app.limenet.core.model.DnsMode
import app.limenet.core.model.RoutingPreset
import app.limenet.core.model.RuleAction
import app.limenet.core.model.RuleKind
import app.limenet.core.model.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Sing-box 1.14 JSON config for the supported client protocols. */
object SingBoxConfig {
    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"

    private fun tls(node: ProxyNode, settings: Settings): JsonObject? {
        if (node.tls.mode == TlsMode.NONE) return null
        return buildJsonObject {
            put("enabled", true)
            XrayConfig.effectiveServerName(node)?.let { put("server_name", it) }
            if (node.tls.alpn.isNotEmpty()) putJsonArray("alpn") { node.tls.alpn.forEach { add(it) } }
            if (node.tls.fingerprint != null || node.tls.mode == TlsMode.REALITY) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", node.tls.fingerprint ?: "chrome")
                }
            }
            if (node.tls.mode == TlsMode.REALITY) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", node.tls.publicKey ?: "")
                    put("short_id", node.tls.shortId ?: "")
                }
            }
            if (node.tls.pinnedSha256.isNotEmpty()) {
                putJsonArray("certificate_public_key_sha256") {
                    node.tls.pinnedSha256.forEach { add(it) }
                }
            }
            if (settings.tlsFragment) put("fragment", true)
            if (settings.tlsRecordFragment) put("record_fragment", true)
        }
    }

    private fun transport(node: ProxyNode): JsonObject? = when (node.transport.type) {
        TransportType.TCP -> null
        TransportType.WS -> buildJsonObject {
            put("type", "ws")
            put("path", node.transport.path ?: "/")
            node.transport.host?.let { host -> putJsonObject("headers") { put("Host", host) } }
        }
        TransportType.GRPC -> buildJsonObject {
            put("type", "grpc")
            put("service_name", node.transport.serviceName ?: "")
        }
        TransportType.HTTPUPGRADE -> buildJsonObject {
            put("type", "httpupgrade")
            put("path", node.transport.path ?: "/")
            node.transport.host?.let { put("host", it) }
        }
        TransportType.H2, TransportType.SPLITHTP -> buildJsonObject {
            put("type", "http")
            put("path", node.transport.path ?: "/")
            node.transport.host?.let { host -> putJsonArray("host") { add(host) } }
        }
    }

    fun outbound(node: ProxyNode, settings: Settings = Settings(), tag: String = TAG_PROXY, detour: String? = null): JsonObject = buildJsonObject {
        put("tag", tag)
        detour?.let { put("detour", it) }
        put("server", node.server)
        put("server_port", node.port)
        if (node.server.any { it.isLetter() }) putJsonObject("domain_resolver") { put("server", "remote") }
        when (node.protocol) {
            ProxyProtocol.VLESS -> {
                put("type", "vless")
                put("uuid", node.uuid ?: "")
                node.flow?.let { put("flow", it) }
            }
            ProxyProtocol.VMESS -> {
                put("type", "vmess")
                put("uuid", node.uuid ?: "")
                put("security", node.cipher ?: "auto")
                put("alter_id", node.alterId)
            }
            ProxyProtocol.TROJAN -> {
                put("type", "trojan")
                put("password", node.password ?: "")
            }
            ProxyProtocol.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("method", node.method ?: "")
                put("password", node.password ?: "")
            }
            else -> error("Протокол ${node.protocol.wire} пока не поддерживается Sing-box")
        }
        tls(node, settings)?.let { put("tls", it) }
        transport(node)?.let { put("transport", it) }
    }

    private fun dnsServer(raw: String, tag: String, mode: DnsMode): JsonObject {
        val input = raw.trim()
        val scheme = input.substringBefore("://", "")
        val address = if (scheme.isEmpty()) input else input.substringAfter("://")
        val host = address.substringBefore('/').substringBefore(':')
        val port = address.substringBefore('/').substringAfter(':', "").toIntOrNull()
        val isTls = mode == DnsMode.DOT || scheme == "tls"
        val isHttps = mode == DnsMode.DOH || scheme == "https" || scheme == "http"
        return buildJsonObject {
            put("type", when { isHttps -> "https"; isTls -> "tls"; else -> "udp" })
            put("tag", tag)
            put("server", host)
            if (port != null) put("server_port", port)
            if (host.any { it.isLetter() }) putJsonObject("domain_resolver") { put("server", "remote") }
            if (isHttps) {
                put("path", "/" + address.substringAfter('/', "dns-query").ifEmpty { "dns-query" })
                putJsonObject("tls") {
                    put("enabled", true)
                    if (host.isNotEmpty() && host.any { it.isLetter() }) put("server_name", host)
                }
            } else if (isTls) {
                putJsonObject("tls") {
                    put("enabled", true)
                    if (host.isNotEmpty() && host.any { it.isLetter() }) put("server_name", host)
                }
            }
        }
    }

    private fun routeRule(kind: RuleKind, value: String, action: RuleAction, tag: String): JsonObject? {
        val clean = value.trim()
        if (clean.isEmpty()) return null
        return buildJsonObject {
            when (kind) {
                RuleKind.DOMAIN -> putJsonArray("domain") { add(clean) }
                RuleKind.DOMAIN_SUFFIX -> putJsonArray("domain_suffix") { add(clean) }
                RuleKind.IP_CIDR -> putJsonArray("ip_cidr") { add(clean) }
                RuleKind.PROCESS -> putJsonArray("process_name") { add(clean) }
                RuleKind.GEOIP, RuleKind.GEOSITE -> putJsonArray("rule_set") { add(tag) }
            }
            when (action) {
                RuleAction.BLOCK -> put("action", "reject")
                else -> {
                    put("action", "route")
                    put("outbound", if (action == RuleAction.PROXY) TAG_PROXY else TAG_DIRECT)
                }
            }
        }
    }

    private fun ruleSetTag(kind: RuleKind, value: String): String = when (kind) {
        RuleKind.GEOIP -> "geoip-${value.trim().lowercase()}"
        RuleKind.GEOSITE -> "geosite-${value.trim().lowercase()}"
        else -> error("Rule-set поддерживает только GEOIP/GEOSITE")
    }

    private fun ruleSet(kind: RuleKind, value: String): JsonObject = buildJsonObject {
        val tag = ruleSetTag(kind, value)
        put("type", "remote")
        put("tag", tag)
        put("format", "binary")
        val repo = if (kind == RuleKind.GEOIP) "sing-geoip" else "sing-geosite"
        put("url", "https://raw.githubusercontent.com/SagerNet/$repo/rule-set/$tag.srs")
        put("download_detour", TAG_PROXY)
    }

    fun render(node: ProxyNode, settings: Settings, httpPort: Int, socksPort: Int, chainEntryNode: ProxyNode? = null): JsonObject {
        val entry = chainEntryNode?.takeIf { it.supported && it.id != node.id }
        require(node.supported) { node.unsupportedReason ?: "узел не поддерживается" }
        val tun = settings.mode == Mode.TUN
        val dnsEnabled = settings.dns.mode != DnsMode.SYSTEM
        val configuredGeoRules = if (settings.routing == RoutingPreset.CUSTOM) {
            settings.customRules.filter {
                (it.kind == RuleKind.GEOIP || it.kind == RuleKind.GEOSITE) && it.value.isNotBlank()
            }
        } else emptyList()
        val ruleSets = buildList {
            if (settings.routing == RoutingPreset.RU_DIRECT) add(RuleKind.GEOIP to "ru")
            configuredGeoRules.forEach { add(it.kind to it.value.trim()) }
        }.distinctBy { (kind, value) -> ruleSetTag(kind, value) }
        return buildJsonObject {
            putJsonObject("log") { put("level", "warn") }
            putJsonArray("inbounds") {
                if (tun) addJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("interface_name", "LimeNet")
                    putJsonArray("address") { add("172.19.0.1/30"); add("fdfe:dcba:9876::1/126") }
                    put("mtu", settings.tunMtu ?: 1500)
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "system")
                }
                addJsonObject {
                    put("type", "mixed")
                    put("tag", "mixed-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", socksPort)
                }
                addJsonObject {
                    put("type", "http")
                    put("tag", "http-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", httpPort)
                }
            }
            putJsonArray("outbounds") {
                if (entry != null) add(outbound(entry, settings, tag = "chain-entry"))
                add(outbound(node, settings, detour = entry?.let { "chain-entry" }))
                addJsonObject { put("type", "direct"); put("tag", TAG_DIRECT) }
            }
            if (dnsEnabled) putJsonObject("dns") {
                putJsonArray("servers") {
                    add(dnsServer(settings.dns.remoteServer, "remote", settings.dns.mode))
                    if (settings.routing == RoutingPreset.RU_DIRECT) {
                        add(dnsServer(settings.dns.directServer, "direct-dns", DnsMode.SYSTEM))
                    }
                }
                putJsonArray("rules") {
                    if (settings.routing == RoutingPreset.RU_DIRECT) addJsonObject {
                        putJsonArray("domain_suffix") { add(".ru"); add(".su") }
                        put("server", "direct-dns")
                    }
                }
                put("final", "remote")
                put("strategy", when (settings.dns.queryStrategy) {
                    "UseIPv4" -> "ipv4_only"
                    "UseIPv6" -> "ipv6_only"
                    else -> "prefer_ipv4"
                })
            }
            putJsonObject("route") {
                put("auto_detect_interface", true)
                put("default_domain_resolver", "remote")
                put("final", TAG_PROXY)
                if (ruleSets.isNotEmpty()) putJsonArray("rule_set") {
                    ruleSets.forEach { (kind, value) -> add(ruleSet(kind, value)) }
                }
                putJsonArray("rules") {
                    if (dnsEnabled) addJsonObject { put("protocol", "dns"); put("action", "hijack-dns") }
                    if (settings.blockQuic) addJsonObject {
                        putJsonArray("network") { add("udp") }
                        putJsonArray("port") { add(443) }
                        put("action", "reject")
                    }
                    addJsonObject { put("ip_is_private", true); put("action", "route"); put("outbound", TAG_DIRECT) }
                    if (settings.routing == RoutingPreset.RU_DIRECT) {
                        add(routeRule(RuleKind.DOMAIN_SUFFIX, "ru", RuleAction.DIRECT, "")!!)
                        add(routeRule(RuleKind.DOMAIN_SUFFIX, "su", RuleAction.DIRECT, "")!!)
                        add(routeRule(RuleKind.GEOIP, "ru", RuleAction.DIRECT, "geoip-ru")!!)
                    }
                    if (settings.routing == RoutingPreset.CUSTOM) {
                        settings.customRules.forEach { rule ->
                            val tag = if (rule.kind == RuleKind.GEOIP || rule.kind == RuleKind.GEOSITE) {
                                ruleSetTag(rule.kind, rule.value)
                            } else ""
                            routeRule(rule.kind, rule.value, rule.action, tag)?.let { add(it) }
                        }
                    }
                    settings.splitTunnel.directApps.forEach { name ->
                        addJsonObject { putJsonArray("process_name") { add(name) }; put("action", "route"); put("outbound", TAG_DIRECT) }
                    }
                    settings.splitTunnel.proxyApps.forEach { name ->
                        addJsonObject { putJsonArray("process_name") { add(name) }; put("action", "route"); put("outbound", TAG_PROXY) }
                    }
                }
            }
        }
    }
}
