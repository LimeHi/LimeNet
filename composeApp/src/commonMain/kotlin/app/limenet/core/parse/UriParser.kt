package app.limenet.core.parse

import app.limenet.core.Codec
import app.limenet.core.ProxyNode
import app.limenet.core.ProxyProtocol
import app.limenet.core.TlsMode
import app.limenet.core.TlsOptions
import app.limenet.core.TransportOptions
import app.limenet.core.TransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object UriParser {

    private data class Parts(
        val user: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
    )

    fun parse(line: String): ProxyNode? {
        val value = line.trim()
        if (value.isEmpty() || value.startsWith("#") || value.startsWith("//")) return null

        return when {
            value.startsWith("vless://") -> parseVless(value)
            value.startsWith("vmess://") -> parseVmess(value)
            value.startsWith("trojan://") -> parseTrojan(value)
            value.startsWith("ss://") -> parseShadowsocks(value)
            value.startsWith("hysteria2://") || value.startsWith("hy2://") ->
                unsupported(value, ProxyProtocol.HYSTERIA2, "Hysteria2",
                    if (value.startsWith("hy2://")) "hy2://" else "hysteria2://")
            value.startsWith("tuic://") -> unsupported(value, ProxyProtocol.TUIC, "TUIC", "tuic://")
            else -> null
        }
    }

    /** Parses one Xray outbound JSON object (the format used by exported server profiles). */
    fun parseJson(element: JsonElement, raw: String = element.toString()): ProxyNode? {
        val outbound = element as? JsonObject ?: return null
        fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
        fun JsonObject.text(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
        fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()

        val protocol = outbound.text("protocol")?.lowercase() ?: return null
        val tag = outbound.text("tag").orEmpty()
        val settings = outbound.obj("settings") ?: JsonObject(emptyMap())
        val stream = outbound.obj("streamSettings") ?: JsonObject(emptyMap())
        val security = stream.text("security")?.lowercase()
        val reality = stream.obj("realitySettings") ?: JsonObject(emptyMap())
        val tlsJson = stream.obj("tlsSettings") ?: JsonObject(emptyMap())
        val tls = TlsOptions(
            mode = when (security) {
                "reality" -> TlsMode.REALITY
                "tls" -> TlsMode.TLS
                else -> TlsMode.NONE
            },
            sni = reality.text("serverName") ?: tlsJson.text("serverName"),
            alpn = tlsJson.array("alpn")?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            fingerprint = reality.text("fingerprint") ?: tlsJson.text("fingerprint"),
            publicKey = reality.text("publicKey"),
            shortId = reality.text("shortId"),
            spiderX = reality.text("spiderX"),
            verifyName = tlsJson.text("verifyPeerCertByName"),
            pinnedSha256 = tlsJson.array("pinnedPeerCertSha256")?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
        )
        val network = stream.text("network")?.lowercase() ?: "tcp"
        val transportType = when (network) {
            "ws", "websocket" -> TransportType.WS
            "grpc" -> TransportType.GRPC
            "httpupgrade" -> TransportType.HTTPUPGRADE
            "xhttp", "splithttp" -> TransportType.SPLITHTP
            "h2", "http" -> TransportType.H2
            else -> TransportType.TCP
        }
        val transportJson = when (transportType) {
            TransportType.WS -> stream.obj("wsSettings")
            TransportType.GRPC -> stream.obj("grpcSettings")
            TransportType.HTTPUPGRADE -> stream.obj("httpupgradeSettings")
            TransportType.SPLITHTP -> stream.obj("splithttpSettings")
            TransportType.H2 -> stream.obj("httpSettings")
            TransportType.TCP -> stream.obj("tcpSettings")
        } ?: JsonObject(emptyMap())
        val host = transportJson.obj("headers")?.text("Host")
            ?: transportJson.array("host")?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: transportJson.text("host")
            ?: transportJson.text("authority")
        val transport = TransportOptions(
            type = transportType,
            path = transportJson.text("path"),
            host = host,
            serviceName = transportJson.text("serviceName") ?: transportJson.text("service_name"),
        )

        val address: String
        val port: Int
        var uuid: String? = null
        var password: String? = null
        var method: String? = null
        var flow: String? = null
        var alterId = 0
        var cipher: String? = null
        val proxyProtocol = when (protocol) {
            "vless" -> ProxyProtocol.VLESS
            "vmess" -> ProxyProtocol.VMESS
            "trojan" -> ProxyProtocol.TROJAN
            "shadowsocks" -> ProxyProtocol.SHADOWSOCKS
            "hysteria2", "hy2" -> ProxyProtocol.HYSTERIA2
            "tuic" -> ProxyProtocol.TUIC
            else -> return null
        }
        when (proxyProtocol) {
            ProxyProtocol.VLESS, ProxyProtocol.VMESS -> {
                val server = settings.array("vnext")?.firstOrNull()?.jsonObject ?: return null
                val user = server.array("users")?.firstOrNull()?.jsonObject ?: return null
                address = server.text("address").orEmpty()
                port = server.int("port") ?: 0
                uuid = user.text("id")
                flow = user.text("flow")
                alterId = user.int("alterId") ?: 0
                cipher = user.text("security")
            }
            ProxyProtocol.TROJAN, ProxyProtocol.SHADOWSOCKS -> {
                val server = settings.array("servers")?.firstOrNull()?.jsonObject ?: return null
                address = server.text("address").orEmpty()
                port = server.int("port") ?: 0
                password = server.text("password")
                method = server.text("method")
                flow = server.text("flow")
            }
            else -> {
                address = settings.text("server").orEmpty()
                port = settings.int("server_port") ?: 0
                password = settings.text("password")
            }
        }
        val unsupportedReason = when {
            address.isBlank() -> "нет адреса сервера"
            port !in 1..65535 -> "неверный порт сервера"
            proxyProtocol == ProxyProtocol.HYSTERIA2 -> "Hysteria2: движок ещё не подключён"
            proxyProtocol == ProxyProtocol.TUIC -> "TUIC: движок ещё не подключён"
            proxyProtocol == ProxyProtocol.VLESS && uuid.isNullOrBlank() -> "нет uuid"
            proxyProtocol == ProxyProtocol.VMESS && uuid.isNullOrBlank() -> "нет uuid"
            proxyProtocol == ProxyProtocol.TROJAN && password.isNullOrBlank() -> "нет пароля"
            proxyProtocol == ProxyProtocol.SHADOWSOCKS && (method.isNullOrBlank() || password.isNullOrBlank()) -> "нет метода или пароля"
            proxyProtocol == ProxyProtocol.VLESS && tls.mode == TlsMode.REALITY && tls.publicKey.isNullOrBlank() -> "для Reality нет publicKey"
            else -> null
        }
        return ProxyNode(
            id = Codec.fnv1a(raw),
            name = tag.ifBlank { "$protocol $address:$port" },
            protocol = proxyProtocol,
            server = address,
            port = port,
            uuid = uuid,
            password = password,
            method = method,
            flow = flow,
            alterId = alterId,
            cipher = cipher,
            tls = tls,
            transport = transport,
            supported = unsupportedReason == null,
            unsupportedReason = unsupportedReason,
            raw = raw,
        )
    }

    private fun split(value: String, scheme: String): Parts? {
        var rest = value.removePrefix(scheme)

        val hash = rest.indexOf('#')
        val fragment = if (hash >= 0) Codec.percentDecode(rest.substring(hash + 1)) else ""
        if (hash >= 0) rest = rest.substring(0, hash)

        var queryRaw = ""
        val q = rest.indexOf('?')
        if (q >= 0) {
            queryRaw = rest.substring(q + 1)
            rest = rest.substring(0, q)
        }

        val at = rest.lastIndexOf('@')
        val user = if (at >= 0) Codec.percentDecode(rest.substring(0, at)) else ""
        val hostPort = if (at >= 0) rest.substring(at + 1) else rest

        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return null
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull() ?: 0
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon < 0) {
                host = hostPort
                port = 0
            } else {
                host = hostPort.substring(0, colon)
                port = hostPort.substring(colon + 1).toIntOrNull() ?: 0
            }
        }

        if (host.isEmpty()) return null
        return Parts(user, host, port, query(queryRaw), fragment)
    }

    private fun query(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) result[Codec.percentDecode(pair)] = ""
            else result[Codec.percentDecode(pair.substring(0, eq))] =
                Codec.percentDecode(pair.substring(eq + 1))
        }
        return result
    }

    private fun transport(raw: String?): TransportType = when (raw?.lowercase()) {
        "ws", "websocket" -> TransportType.WS
        "grpc" -> TransportType.GRPC
        "httpupgrade" -> TransportType.HTTPUPGRADE
        "xhttp", "splithttp" -> TransportType.SPLITHTP
        "h2", "http" -> TransportType.H2
        else -> TransportType.TCP
    }

    private fun tlsMode(raw: String?, fallback: TlsMode = TlsMode.NONE): TlsMode =
        when (raw?.lowercase()) {
            "reality" -> TlsMode.REALITY
            "tls", "xtls" -> TlsMode.TLS
            "none", "", null -> fallback
            else -> fallback
        }

    private fun build(
        raw: String,
        name: String,
        protocol: ProxyProtocol,
        server: String,
        port: Int,
        uuid: String? = null,
        password: String? = null,
        method: String? = null,
        flow: String? = null,
        alterId: Int = 0,
        cipher: String? = null,
        tls: TlsOptions = TlsOptions(),
        transport: TransportOptions = TransportOptions(),
        unsupportedReason: String? = null,
    ): ProxyNode {
        val reason = unsupportedReason ?: when {
            server.isEmpty() -> "нет адреса сервера"
            port == 0 -> "нет порта"
            protocol == ProxyProtocol.VLESS && uuid.isNullOrEmpty() -> "нет uuid"
            protocol == ProxyProtocol.VMESS && uuid.isNullOrEmpty() -> "нет uuid"
            protocol == ProxyProtocol.TROJAN && password.isNullOrEmpty() -> "нет пароля"
            protocol == ProxyProtocol.SHADOWSOCKS && (method.isNullOrEmpty() || password.isNullOrEmpty()) ->
                "нет метода или пароля"
            else -> null
        }

        return ProxyNode(
            id = Codec.fnv1a(raw),
            name = name.trim().ifEmpty { "$server:$port" },
            protocol = protocol,
            server = server,
            port = port,
            uuid = uuid,
            password = password,
            method = method,
            flow = flow,
            alterId = alterId,
            cipher = cipher,
            tls = tls,
            transport = transport,
            supported = reason == null,
            unsupportedReason = reason,
            raw = raw,
        )
    }

    private fun parseVless(value: String): ProxyNode? {
        val parts = split(value, "vless://") ?: return null
        val q = parts.query
        return build(
            raw = value,
            name = parts.fragment,
            protocol = ProxyProtocol.VLESS,
            server = parts.host,
            port = parts.port,
            uuid = parts.user,
            flow = q["flow"],
            tls = TlsOptions(
                mode = tlsMode(q["security"]),
                sni = q["sni"] ?: q["host"],
                alpn = q["alpn"]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
                fingerprint = q["fp"],
                allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
                publicKey = q["pbk"],
                shortId = q["sid"],
                spiderX = q["spx"],
                verifyName = q["vcn"] ?: q["verifyPeerCertByName"],
                pinnedSha256 = (q["pcs"] ?: q["pinnedPeerCertSha256"])
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
            ),
            transport = TransportOptions(
                type = transport(q["type"]),
                path = q["path"],
                host = q["host"],
                serviceName = q["serviceName"],
            ),
        )
    }

    private fun parseVmess(value: String): ProxyNode? {
        val payload = Codec.base64Decode(value.removePrefix("vmess://")) ?: return null
        val json: JsonObject = runCatching {
            Json.parseToJsonElement(payload).jsonObject
        }.getOrNull() ?: return null

        fun field(key: String): String? =
            runCatching { json[key]?.jsonPrimitive?.content }.getOrNull()

        val network = transport(field("net"))
        val tlsEnabled = field("tls") == "tls"
        return build(
            raw = value,
            name = field("ps") ?: "",
            protocol = ProxyProtocol.VMESS,
            server = field("add") ?: "",
            port = field("port")?.toIntOrNull() ?: 0,
            uuid = field("id"),
            alterId = field("aid")?.toIntOrNull() ?: 0,
            cipher = field("scy") ?: "auto",
            tls = TlsOptions(
                mode = if (tlsEnabled) TlsMode.TLS else TlsMode.NONE,
                sni = field("sni") ?: field("host"),
                alpn = field("alpn")?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
                fingerprint = field("fp"),
            ),
            transport = TransportOptions(
                type = network,
                path = field("path"),
                host = field("host"),
                serviceName = if (network == TransportType.GRPC) field("path") else null,
            ),
        )
    }

    private fun parseTrojan(value: String): ProxyNode? {
        val parts = split(value, "trojan://") ?: return null
        val q = parts.query
        return build(
            raw = value,
            name = parts.fragment,
            protocol = ProxyProtocol.TROJAN,
            server = parts.host,
            port = parts.port,
            password = parts.user,
            flow = q["flow"],
            tls = TlsOptions(
                mode = tlsMode(q["security"], TlsMode.TLS),
                sni = q["sni"] ?: q["peer"],
                alpn = q["alpn"]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
                fingerprint = q["fp"],
                allowInsecure = q["allowInsecure"] == "1",
            ),
            transport = TransportOptions(
                type = transport(q["type"]),
                path = q["path"],
                host = q["host"],
                serviceName = q["serviceName"],
            ),
        )
    }

    private fun parseShadowsocks(value: String): ProxyNode? {
        var body = value.removePrefix("ss://")

        val hash = body.indexOf('#')
        val name = if (hash >= 0) Codec.percentDecode(body.substring(hash + 1)) else ""
        if (hash >= 0) body = body.substring(0, hash)

        var plugin: String? = null
        val q = body.indexOf('?')
        if (q >= 0) {
            plugin = query(body.substring(q + 1))["plugin"]
            body = body.substring(0, q)
        }

        var method = ""
        var password = ""
        var host: String
        var port: Int

        val at = body.lastIndexOf('@')
        if (at >= 0) {
            var credentials = body.substring(0, at)
            val hostPort = body.substring(at + 1)
            if (':' !in credentials) credentials = Codec.base64Decode(credentials) ?: credentials
            val colon = credentials.indexOf(':')
            if (colon < 0) return null
            method = credentials.substring(0, colon)
            password = credentials.substring(colon + 1)

            val split = hostPort.lastIndexOf(':')
            if (split < 0) return null
            host = hostPort.substring(0, split)
            port = hostPort.substring(split + 1).toIntOrNull() ?: 0
        } else {
            val decoded = Codec.base64Decode(body) ?: return null
            val atDecoded = decoded.lastIndexOf('@')
            if (atDecoded < 0) return null
            val credentials = decoded.substring(0, atDecoded)
            val colon = credentials.indexOf(':')
            if (colon < 0) return null
            method = credentials.substring(0, colon)
            password = credentials.substring(colon + 1)

            val hostPort = decoded.substring(atDecoded + 1)
            val split = hostPort.lastIndexOf(':')
            if (split < 0) return null
            host = hostPort.substring(0, split)
            port = hostPort.substring(split + 1).toIntOrNull() ?: 0
        }

        return build(
            raw = value,
            name = name,
            protocol = ProxyProtocol.SHADOWSOCKS,
            server = host,
            port = port,
            method = method.lowercase(),
            password = password,
            unsupportedReason = if (plugin != null) "плагин Shadowsocks не поддерживается" else null,
        )
    }

    private fun unsupported(
        value: String,
        protocol: ProxyProtocol,
        label: String,
        scheme: String,
    ): ProxyNode {
        val parts = runCatching { split(value, scheme) }.getOrNull()
        return build(
            raw = value,
            name = parts?.fragment ?: "",
            protocol = protocol,
            server = parts?.host ?: "",
            port = parts?.port ?: 0,
            unsupportedReason = "$label: движок ещё не подключён",
        )
    }
}
