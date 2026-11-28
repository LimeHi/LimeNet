package app.limenet.core.parse

import app.limenet.core.Codec
import app.limenet.core.ProxyNode
import app.limenet.core.SubResult
import app.limenet.core.ProxyProtocol
import app.limenet.core.render.XrayConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object Subscription {

    fun parse(body: String): SubResult {
        val clean = body.removePrefix("\uFEFF").trim()
        if (clean.isEmpty()) return SubResult()

        if (clean.startsWith("{") || clean.startsWith("[")) {
            return parseJson(clean)
        }

        val decoded = decodeMaybe(clean)
        val nodes = LinkedHashMap<String, ProxyNode>()
        var skipped = 0

        for (line in decoded.lines()) {
            val node = UriParser.parse(line)
            if (node == null) {
                if (line.isNotBlank() && !line.trim().startsWith("#")) skipped++
                continue
            }
            nodes.putIfAbsent(node.id, node)
        }

        val parsedNodes = nodes.values.toList()
        val supported = parsedNodes.filter { it.supported }
        val xrayConfig = if (supported.isEmpty()) null else buildJsonObject {
            putJsonArray("outbounds") {
                supported.forEachIndexed { index, node -> add(XrayConfig.outbound(node, tag = "proxy-${index + 1}")) }
                addJsonObject { put("tag", "direct"); put("protocol", "freedom") }
            }
            putJsonObject("observatory") {
                putJsonArray("subjectSelector") { add("proxy-") }
                put("probeUrl", DEFAULT_PROBE_URL)
                put("probeInterval", "1m")
            }
            putJsonObject("routing") {
                putJsonArray("balancers") {
                    addJsonObject {
                        put("tag", GENERATED_BALANCER_TAG)
                        putJsonArray("selector") { add("proxy-") }
                        putJsonObject("strategy") { put("type", "leastPing") }
                    }
                }
                putJsonArray("rules") {
                    addJsonObject {
                        put("type", "field")
                        put("network", "tcp,udp")
                        put("balancerTag", GENERATED_BALANCER_TAG)
                    }
                }
            }
        }
        return SubResult(
            nodes = parsedNodes,
            skipped = skipped,
            xrayConfigJson = xrayConfig?.let { Json.encodeToString(JsonElement.serializer(), it) },
            autoBalancerTag = if (supported.isNotEmpty()) GENERATED_BALANCER_TAG else null,
        )
    }

    /** Accepts one Xray outbound, an array of outbounds, or a full Xray config. */
    private fun parseJson(body: String): SubResult {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrElse {
            throw IllegalArgumentException("Некорректный JSON подписки")
        }
        val rootObject = root as? JsonObject
        val entries: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> when {
                "protocol" in root -> listOf(root)
                root["outbounds"] is JsonArray -> root["outbounds"]!!.jsonArray
                else -> throw IllegalArgumentException("JSON не содержит Xray outbound или массив outbounds")
            }
            else -> throw IllegalArgumentException("Ожидался JSON-объект или массив серверов")
        }
        val fullConfigRoot = root as? JsonObject
        val nodes = LinkedHashMap<String, ProxyNode>()
        var skipped = 0
        entries.forEach { entry ->
            val node = UriParser.parseJson(entry, entry.toString())
            if (node == null) skipped++ else nodes.putIfAbsent(node.id, node)
        }
        val outbounds = when {
            root is JsonArray -> root
            rootObject?.get("outbounds") is JsonArray -> rootObject["outbounds"]!!.jsonArray
            rootObject != null && "protocol" in rootObject -> JsonArray(listOf(rootObject))
            else -> null
        }
        // Селектор Xray может включать любые outbound-типы, а не только четыре
        // поддерживаемых нами протокола узлов (например, hysteria2 или socks).
        val outboundTags = outbounds.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val tag = (obj["tag"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            tag
        }
        val balancers = (rootObject?.get("routing") as? JsonObject)?.get("balancers") as? JsonArray
        val routingRules = ((rootObject?.get("routing") as? JsonObject)?.get("rules") as? JsonArray).orEmpty()
        val routeBalancers = routingRules.flatMap { rule ->
            val obj = rule as? JsonObject ?: return@flatMap emptyList()
            listOfNotNull((obj["balancerTag"] as? JsonPrimitive)?.contentOrNull)
        }.toSet()
        val providerBalancer = balancers.orEmpty().firstNotNullOfOrNull { element ->
            val obj = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val tag = (obj["tag"] as? JsonPrimitive)?.contentOrNull ?: return@firstNotNullOfOrNull null
            val selector = (obj["selector"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }
            tag.takeIf { it in routeBalancers && selector.any { prefix -> outboundTags.any { it.startsWith(prefix) } } }
        }
        val providerConfig = when {
            rootObject != null && "outbounds" in rootObject && rootObject["outbounds"] is JsonArray -> rootObject
            rootObject != null && "protocol" in rootObject -> buildJsonObject { putJsonArray("outbounds") { add(rootObject) } }
            root is JsonArray -> buildJsonObject { put("outbounds", root) }
            else -> null
        }
        val effectiveBalancer = providerBalancer ?: if (fullConfigRoot != null && outboundTags.isNotEmpty()) {
            balancers.orEmpty().firstNotNullOfOrNull { element ->
                val obj = element as? JsonObject ?: return@firstNotNullOfOrNull null
                val tag = (obj["tag"] as? JsonPrimitive)?.contentOrNull ?: return@firstNotNullOfOrNull null
                val selector = (obj["selector"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val selectedProxy = selector.any { prefix -> outboundTags.any { it.startsWith(prefix) } }
                val defaultOutbound = routingRules.any { rule ->
                    val objRule = rule as? JsonObject ?: return@any false
                    (objRule["outboundTag"] as? JsonPrimitive)?.contentOrNull == tag &&
                        "balancerTag" !in objRule && "balancerMode" !in objRule
                }
                tag.takeIf { selectedProxy && (it in routeBalancers || defaultOutbound) }
            }
        } else providerBalancer
        val finalRoot = when {
            providerConfig == null -> null
            effectiveBalancer != null -> ensureRandomObservatory(providerConfig, effectiveBalancer)
            fullConfigRoot == null && outboundTags.isNotEmpty() -> addGeneratedAutoBalancer(providerConfig, outboundTags)
            else -> providerConfig
        }
        return SubResult(
            nodes = nodes.values.toList(),
            skipped = skipped,
            xrayConfigJson = finalRoot?.let { Json.encodeToString(JsonElement.serializer(), it) },
            autoBalancerTag = effectiveBalancer ?: if (fullConfigRoot == null && outboundTags.isNotEmpty()) GENERATED_BALANCER_TAG else null,
        )
    }

    private fun addGeneratedAutoBalancer(config: JsonObject, proxyTags: List<String>): JsonObject {
        val result = config.toMutableMap()
        if ("observatory" !in result && "burstObservatory" !in result) {
            result["observatory"] = buildJsonObject {
                putJsonArray("subjectSelector") { proxyTags.forEach { add(it) } }
                put("probeUrl", DEFAULT_PROBE_URL)
                put("probeInterval", "1m")
            }
        }
        val routing = ((config["routing"] as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
        val balancers = (routing["balancers"] as? JsonArray) ?: JsonArray(emptyList())
        routing["balancers"] = JsonArray(balancers + buildJsonObject {
            put("tag", GENERATED_BALANCER_TAG)
            putJsonArray("selector") { proxyTags.forEach { add(it) } }
            putJsonObject("strategy") { put("type", "leastPing") }
        })
        val rules = (routing["rules"] as? JsonArray) ?: JsonArray(emptyList())
        routing["rules"] = JsonArray(rules + buildJsonObject {
            put("type", "field")
            put("network", "tcp,udp")
            put("balancerTag", GENERATED_BALANCER_TAG)
        })
        result["routing"] = JsonObject(routing)
        return JsonObject(result)
    }

    private fun ensureRandomObservatory(config: JsonObject, balancerTag: String): JsonObject {
        val routing = config["routing"] as? JsonObject ?: return config
        val balancers = routing["balancers"] as? JsonArray ?: return config
        val randomBalancer = balancers.mapNotNull { it as? JsonObject }.firstOrNull { balancer ->
            val strategy = balancer["strategy"] as? JsonObject
            (balancer["tag"] as? JsonPrimitive)?.contentOrNull == balancerTag &&
                (strategy?.get("type") as? JsonPrimitive)?.contentOrNull == "random"
        }
        if (randomBalancer == null) return config
        if ("observatory" in config || "burstObservatory" in config) return config
        return JsonObject(config + ("observatory" to buildJsonObject {
            val selector = (randomBalancer["selector"] as? JsonArray) ?: JsonArray(emptyList())
            put("subjectSelector", selector)
            put("probeUrl", DEFAULT_PROBE_URL)
            put("probeInterval", "1m")
        }))
    }

    const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"
    const val GENERATED_BALANCER_TAG = "limenet-auto"

    /**
     * Объявление провайдера из тела подписки: строка `#announce: текст`
     * (в том числе внутри base64-списка). Значение вида `base64:…` раскодируется.
     */
    fun announce(body: String): String? {
        val clean = body.removePrefix("\uFEFF").trim()
        if (clean.isEmpty()) return null
        for (line in decodeMaybe(clean).lines()) {
            val text = line.trim().removePrefix("#").trim()
            if (!text.startsWith("announce", ignoreCase = true)) continue
            val rest = text.substring("announce".length)
            if (rest.isNotEmpty() && rest[0] != ':' && rest[0] != '=' && !rest[0].isWhitespace()) continue
            return announceValue(rest.trimStart(':', '=', ' ', '\t'))
        }
        return null
    }

    /** Значение объявления: обычный текст или `base64:…` (так его шлют заголовком `announce`). */
    fun announceValue(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val text = if (value.startsWith("base64:", ignoreCase = true)) {
            Codec.base64Decode(value.substring("base64:".length).trim()) ?: return null
        } else {
            value
        }
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private fun decodeMaybe(value: String): String {
        val compact = value.filterNot { it.isWhitespace() }
        if (compact.length <= 16) return value
        if (compact.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it !in "+/=_-" }) {
            return value
        }
        val decoded = Codec.base64Decode(compact) ?: return value
        return if ("://" in decoded) decoded else value
    }
}
