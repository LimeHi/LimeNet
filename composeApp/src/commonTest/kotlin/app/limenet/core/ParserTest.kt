package app.limenet.core

import app.limenet.core.model.DnsMode
import app.limenet.core.model.RoutingPreset
import app.limenet.core.model.Settings
import app.limenet.core.model.SplitTunnel
import app.limenet.core.model.parseRoutingRuleValues
import app.limenet.core.parse.Subscription
import app.limenet.core.parse.UriParser
import app.limenet.core.render.XrayConfig
import app.limenet.core.render.SingBoxConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParserTest {

    @Test
    fun routingRuleListsSplitOnCommonSeparatorsAndDeduplicate() {
        assertEquals(
            listOf("example.org", "test.example", "10.0.0.0/8"),
            parseRoutingRuleValues(" example.org, test.example;\n10.0.0.0/8 example.org "),
        )
    }

    @Test
    fun announceFromCommentLine() {
        val body = "#profile-title: test\n#announce: Технические работы в пятницу\nvless://u@h.example:443?security=none#a"
        assertEquals("Технические работы в пятницу", Subscription.announce(body))
        assertEquals(null, Subscription.announce("vless://u@h.example:443?security=none#a"))
        assertEquals(null, Subscription.announce("#announcement-of-something\nvless://u@h.example:443?security=none#a"))
    }

    @Test
    fun announceValueDecodesBase64Prefix() {
        assertEquals("hello", Subscription.announceValue("base64:aGVsbG8="))
        assertEquals("plain text", Subscription.announceValue("  plain text "))
        assertEquals(null, Subscription.announceValue("  "))
    }

    private val uuid = "11111111-1111-1111-1111-111111111111"
    private val pbk = "jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0"

    @Test
    fun vlessReality() {
        val node = UriParser.parse(
            "vless://$uuid@example.com:443?encryption=none&security=reality&sni=www.microsoft.com" +
                "&fp=chrome&pbk=$pbk&sid=ab12&type=tcp&flow=xtls-rprx-vision#Test%20Node",
        )
        assertNotNull(node)
        assertEquals(ProxyProtocol.VLESS, node.protocol)
        assertEquals("example.com", node.server)
        assertEquals(443, node.port)
        assertEquals("xtls-rprx-vision", node.flow)
        assertEquals(TlsMode.REALITY, node.tls.mode)
        assertEquals("Test Node", node.name)
        assertTrue(node.supported)
    }

    @Test
    fun shadowsocksBothForms() {
        val modern = UriParser.parse("ss://YWVzLTI1Ni1nY206c2VjcmV0@example.org:8388#SS")
        assertNotNull(modern)
        assertEquals("aes-256-gcm", modern.method)
        assertEquals("secret", modern.password)

        val legacy = UriParser.parse(
            "ss://" + "aWVzLTI1Ni1nY206c2VjcmV0QGV4YW1wbGUub3JnOjgzODg=" + "#Legacy",
        )
        assertNotNull(legacy)
    }

    @Test
    fun hysteriaIsDetectedButUnsupported() {
        val node = UriParser.parse("hysteria2://pass@example.com:443?sni=example.com#Hy2")
        assertNotNull(node)
        assertEquals(ProxyProtocol.HYSTERIA2, node.protocol)
        assertTrue(!node.supported)
    }

    @Test
    fun base64Subscription() {
        val lines = listOf(
            "vless://$uuid@a.example.com:443?security=tls&sni=a.example.com&type=ws&path=%2Fws#A",
            "trojan://secret@b.example.com:443?sni=b.example.com#B",
            "мусор",
        ).joinToString("\n")

        val encoded = base64(lines)
        val result = Subscription.parse(encoded)
        assertEquals(2, result.nodes.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun xrayOutboundJsonSubscription() {
        val body = """
            {
              "protocol": "vless",
              "settings": { "vnext": [{ "address": "node.example", "port": 443,
                "users": [{ "id": "$uuid", "encryption": "none", "flow": "xtls-rprx-vision" }] }] },
              "streamSettings": { "network": "tcp", "security": "reality",
                "realitySettings": { "fingerprint": "edge", "publicKey": "$pbk", "serverName": "sni.example", "shortId": "abcd" },
                "tcpSettings": {} },
              "tag": "provider-auto"
            }
        """.trimIndent()
        val result = Subscription.parse(body)
        assertEquals(1, result.nodes.size)
        val node = result.nodes.single()
        assertEquals("provider-auto", node.name)
        assertEquals("node.example", node.server)
        assertEquals(443, node.port)
        assertEquals(uuid, node.uuid)
        assertEquals("xtls-rprx-vision", node.flow)
        assertEquals(TlsMode.REALITY, node.tls.mode)
        assertEquals("sni.example", node.tls.sni)
        assertEquals("edge", node.tls.fingerprint)
        assertEquals(pbk, node.tls.publicKey)
        assertEquals("abcd", node.tls.shortId)
        assertTrue(node.supported)
    }

    @Test
    fun xrayOutboundArrayAndFullConfigJsonSubscription() {
        val outbound = """{"protocol":"vless","tag":"one","settings":{"vnext":[{"address":"one.example","port":443,"users":[{"id":"$uuid"}]}]}}"""
        assertEquals(1, Subscription.parse("[$outbound]").nodes.size)
        assertEquals(1, Subscription.parse("""{"outbounds":[$outbound]}""").nodes.size)
    }

    @Test
    fun fullXrayConfigWithoutBalancerDoesNotInventProviderAuto() {
        val body = """{"outbounds":[{"tag":"proxy-1","protocol":"vless","settings":{"vnext":[{"address":"one.example","port":443,"users":[{"id":"$uuid"}]}]}}],"routing":{"rules":[{"type":"field","outboundTag":"proxy-1"}]}}"""
        val parsed = Subscription.parse(body)
        assertEquals(null, parsed.autoBalancerTag)
        assertTrue("routing" in parsed.xrayConfigJson!!)
    }

    @Test
    fun xrayProviderBalancerIsRetainedAndExposedAsAuto() {
        val body = """
            {
              "outbounds": [
                {"tag":"proxy-1","protocol":"vless","settings":{"vnext":[{"address":"one.example","port":443,"users":[{"id":"$uuid"}]}]}},
                {"tag":"proxy-2","protocol":"vless","settings":{"vnext":[{"address":"two.example","port":443,"users":[{"id":"$uuid"}]}]}},
                {"tag":"direct","protocol":"freedom"}
              ],
              "observatory":{"subjectSelector":["proxy-"],"probeUrl":"https://probe.example/204","probeInterval":"30s"},
              "routing":{"balancers":[{"tag":"provider-auto","selector":["proxy-"],"strategy":{"type":"leastLoad"}}],
                "rules":[{"type":"field","network":"tcp,udp","balancerTag":"provider-auto"}]}
            }
        """.trimIndent()
        val parsed = Subscription.parse(body)
        assertEquals(2, parsed.nodes.size)
        assertEquals("provider-auto", parsed.autoBalancerTag)
        val retained = Json.parseToJsonElement(parsed.xrayConfigJson!!).jsonObject
        val routing = retained["routing"]!!.jsonObject
        assertEquals("leastLoad", routing["balancers"]!!.jsonArray[0].jsonObject
            .getValue("strategy").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue("provider-auto" in routing["rules"].toString())
        assertEquals("https://probe.example/204", retained["observatory"]!!.jsonObject
            .getValue("probeUrl").jsonPrimitive.content)
    }

    @Test
    fun xrayBalancerSelectorCanTargetOutboundTypesBeyondSupportedNodeProtocols() {
        val body = """
            {"outbounds":[
              {"tag":"edge-special","protocol":"hysteria2","settings":{"server":"edge.example","server_port":443,"password":"test"}},
              {"tag":"direct","protocol":"freedom"}],
             "routing":{"balancers":[{"tag":"owner-auto","selector":["edge-"]}],
             "rules":[{"type":"field","balancerTag":"owner-auto"}]}}
        """.trimIndent()

        val parsed = Subscription.parse(body)

        assertEquals("owner-auto", parsed.autoBalancerTag)
        assertTrue("owner-auto" in parsed.xrayConfigJson.orEmpty())
    }

    @Test
    fun randomProviderBalancerGetsDefaultObservatoryOnlyWhenMissing() {
        val body = """
            {"outbounds":[{"tag":"proxy-1","protocol":"vless","settings":{"vnext":[{"address":"one.example","port":443,"users":[{"id":"$uuid"}]}]}}],
             "routing":{"balancers":[{"tag":"auto","selector":["proxy-"],"strategy":{"type":"random"}}],
             "rules":[{"type":"field","balancerTag":"auto"}]}}
        """.trimIndent()
        val parsed = Subscription.parse(body)
        val retained = Json.parseToJsonElement(parsed.xrayConfigJson!!).jsonObject
        assertEquals(Subscription.DEFAULT_PROBE_URL, retained["observatory"]!!.jsonObject
            .getValue("probeUrl").jsonPrimitive.content)
        assertEquals("1m", retained["observatory"]!!.jsonObject
            .getValue("probeInterval").jsonPrimitive.content)
    }

    @Test
    fun ordinaryUriSubscriptionGetsLeastPingAutoConfig() {
        val parsed = Subscription.parse(
            "vless://$uuid@one.example:443?security=tls#One\nvless://$uuid@two.example:443?security=tls#Two",
        )
        assertEquals(2, parsed.nodes.size)
        assertEquals(Subscription.GENERATED_BALANCER_TAG, parsed.autoBalancerTag)
        val config = Json.parseToJsonElement(parsed.xrayConfigJson!!).jsonObject
        val routing = config["routing"]!!.jsonObject
        assertEquals("leastPing", routing["balancers"]!!.jsonArray[0].jsonObject
            .getValue("strategy").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(2, config["outbounds"]!!.jsonArray.count {
            it.jsonObject["tag"]?.jsonPrimitive?.content?.startsWith("proxy-") == true
        })
    }

    @Test
    fun unsupportedJsonFormatHasUsefulError() {
        assertFailsWith<IllegalArgumentException> {
            Subscription.parse("{\"proxies\":[]}")
        }
    }

    @Test
    fun tunConfigHasRouting() {
        val node = realityNode()
        val config = XrayConfig.render(node, Settings(mode = Mode.TUN), 10809, 10808)

        val inbounds = config["inbounds"]!!.jsonArray
        assertTrue(inbounds.any { it.jsonObject["protocol"]?.jsonPrimitive?.content == "tun" })
        assertTrue(inbounds.any { it.jsonObject["protocol"]?.jsonPrimitive?.content == "socks" })

        val rules = config["routing"]!!.jsonObject["rules"]!!.jsonArray
        val flat = rules.joinToString(" ") { it.toString() }
        assertTrue("geosite:category-ru" in flat)
        assertTrue("geoip:ru" in flat)

        val dns = config["dns"]!!.jsonObject
        assertTrue("https://dns.google/dns-query" in dns.toString())
    }

    @Test
    fun proxyModeHasNoTunInbound() {
        val config = XrayConfig.render(realityNode(), Settings(mode = Mode.PROXY), 10809, 10808)
        val inbounds = config["inbounds"]!!.jsonArray
        assertTrue(inbounds.none { it.jsonObject["protocol"]?.jsonPrimitive?.content == "tun" })
        assertTrue(inbounds.any { it.jsonObject["protocol"]?.jsonPrimitive?.content == "http" })
    }

    @Test
    fun dnsModesRenderCorrectAddress() {
        val dot = Settings(dns = Settings().dns.copy(mode = DnsMode.DOT, remoteServer = "1.1.1.1"))
        val dotConfig = XrayConfig.render(realityNode(), dot, 10809, 10808)
        assertTrue("tls://1.1.1.1" in dotConfig["dns"].toString())

        val system = Settings(dns = Settings().dns.copy(mode = DnsMode.SYSTEM))
        val systemConfig = XrayConfig.render(realityNode(), system, 10809, 10808)
        assertEquals(null, systemConfig["dns"])
    }

    @Test
    fun networkCompatibilitySettingsRender() {
        val settings = Settings(tunMtu = 1400, blockQuic = true)
        val config = XrayConfig.renderTun(realityNode(), settings)
        val inbounds = config["inbounds"]!!.jsonArray
        val tun = inbounds.first().jsonObject
        assertEquals("1400", tun["settings"]!!.jsonObject["mtu"]!!.jsonPrimitive.content)
        val socks = inbounds.first { it.jsonObject["tag"]?.jsonPrimitive?.content == "socks-in" }.jsonObject
        assertEquals("10808", socks["port"]!!.jsonPrimitive.content)
        val rules = config["routing"]!!.jsonObject["rules"]!!.jsonArray
        assertTrue(rules.any { "udp" in it.toString() && "443" in it.toString() && "block" in it.toString() })

        val defaults = XrayConfig.renderTun(realityNode(), Settings())
        assertEquals("1500", defaults["inbounds"]!!.jsonArray.first().jsonObject
            .getValue("settings").jsonObject.getValue("mtu").jsonPrimitive.content)
        assertFalse(defaults["routing"]!!.jsonObject["rules"]!!.jsonArray.any {
            "udp" in it.toString() && "443" in it.toString()
        })

        val singBox = SingBoxConfig.render(realityNode(), settings, 10809, 10808)
        assertTrue("1400" in singBox.toString())
        assertTrue("443" in singBox.toString() && "udp" in singBox.toString())

        val fragmented = SingBoxConfig.render(
            realityNode(),
            settings.copy(tlsFragment = true, tlsRecordFragment = true),
            10809,
            10808,
        )
        val tls = fragmented["outbounds"]!!.jsonArray.first().jsonObject["tls"]!!.jsonObject
        assertEquals("true", tls["fragment"]!!.jsonPrimitive.content)
        assertEquals("true", tls["record_fragment"]!!.jsonPrimitive.content)

        val xrayFragmentFields = XrayConfig.renderTun(
            realityNode(), settings.copy(tlsFragment = true, tlsRecordFragment = true),
        )["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals(null, xrayFragmentFields["tls"]?.jsonObject?.get("fragment"))
    }

    @Test
    fun proxyChainRendersEntryAndLinksTargetForBothEngines() {
        val target = realityNode()
        val entry = target.copy(id = "entry-node", name = "Entry", server = "entry.example.com")

        val xray = XrayConfig.render(target, Settings(), 10809, 10808, entry)["outbounds"]!!.jsonArray
        assertEquals("chain-entry", xray[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("proxy", xray[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("chain-entry", xray[1].jsonObject["streamSettings"]!!.jsonObject
            .getValue("sockopt").jsonObject.getValue("dialerProxy").jsonPrimitive.content)

        val singBox = SingBoxConfig.render(target, Settings(), 10809, 10808, entry)["outbounds"]!!.jsonArray
        assertEquals("chain-entry", singBox[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("proxy", singBox[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("chain-entry", singBox[1].jsonObject["detour"]!!.jsonPrimitive.content)

        val noLoop = XrayConfig.render(target, Settings(), 10809, 10808, target)["outbounds"]!!.jsonArray
        assertEquals("proxy", noLoop[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals(1, noLoop.count { it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy" })
    }

    @Test
    fun knownDnsProvidersUseMatchingAndroidBootstrapAddresses() {
        assertEquals("8.8.8.8", XrayConfig.androidTunDns("https://dns.google/dns-query"))
        assertEquals("1.1.1.1", XrayConfig.androidTunDns("https://1.1.1.1/dns-query"))
        assertEquals("9.9.9.9", XrayConfig.androidTunDns("https://dns.quad9.net/dns-query"))
        assertEquals("4.2.2.2", XrayConfig.androidTunDns("https://4.2.2.2/dns-query"))
    }

    @Test
    fun splitTunnelAndCustomRules() {
        val settings = Settings(
            routing = RoutingPreset.CUSTOM,
            splitTunnel = SplitTunnel(directApps = listOf("discord.exe")),
        )
        val config = XrayConfig.render(realityNode(), settings, 10809, 10808)
        val flat = config["routing"]!!.jsonObject["rules"]!!.jsonArray.joinToString(" ") { it.toString() }
        assertTrue("discord.exe" in flat)
        assertTrue("process" in flat)
    }

    @Test
    fun deepLinkForms() {
        val raw = "vless://$uuid@example.com:443#A"
        assertEquals(raw, DeepLink.parse("limenet://import/" + base64(raw))?.payload)
        assertEquals(null, DeepLink.parse("https://example.com"))
        assertEquals("Моя", DeepLink.parse("limenet://add?url=https%3A%2F%2Fx.dev%2Fs&name=%D0%9C%D0%BE%D1%8F")?.title)
    }

    @Test
    fun deepLinkLimeNetScheme() {
        assertEquals(
            "https://example.com/sub",
            DeepLink.parse("limenet://add?url=https%3A%2F%2Fexample.com%2Fsub")?.payload,
        )
        assertEquals(
            "https://example.com/sub",
            DeepLink.parse("LimeNet://add/aHR0cHM6Ly9leGFtcGxlLmNvbS9zdWI=")?.payload,
        )
    }

    @Test
    fun ipSniFallsBackToHost() {
        val node = assertNotNull(
            UriParser.parse(
                "vless://$uuid@185.178.208.140:443?encryption=none&type=ws&security=tls" +
                    "&sni=185.178.208.140&path=%2Fws&host=marshalltmp.top.#A",
            ),
        )
        val config = XrayConfig.renderTest(node, 10808)
        val tls = config["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!
            .jsonObject["tlsSettings"]!!.jsonObject

        assertEquals("marshalltmp.top.", tls["serverName"]!!.jsonPrimitive.content)
        assertEquals(null, tls["allowInsecure"])
    }

    @Test
    fun ipSniWithoutHostHasNoServerName() {
        val node = assertNotNull(
            UriParser.parse(
                "vless://$uuid@87.242.94.239:443?encryption=none&type=ws&security=tls" +
                    "&sni=87.242.94.239&path=%2Fws#A",
            ),
        )
        val config = XrayConfig.renderTest(node, 10808)
        val tls = config["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!
            .jsonObject["tlsSettings"]!!.jsonObject

        assertEquals(null, tls["serverName"])
        assertEquals(null, tls["allowInsecure"])
    }

    @Test
    fun wsUsesExplicitHostHeader() {
        val node = assertNotNull(
            UriParser.parse(
                "vless://$uuid@example.com:443?encryption=none&type=ws&security=tls" +
                    "&sni=example.com&path=%2Fws&host=cdn.example.com#A",
            ),
        )
        val ws = XrayConfig.renderTest(node, 10808)["outbounds"]!!.jsonArray[0]
            .jsonObject["streamSettings"]!!.jsonObject["wsSettings"]!!.jsonObject

        assertEquals("cdn.example.com", ws["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        assertEquals(null, ws["host"])
    }

    @Test
    fun formatValues() {
        assertEquals("1,5 КБ", Format.bytes(1536))
        assertEquals("2 МБ", Format.bytes(2L * 1024 * 1024))
        assertEquals("—", Format.bytes(null))
        assertEquals("15.11.2026", Format.date(1_794_700_800))
        assertEquals("8 Мбит/с", Format.speed(1_000_000))
    }

    private fun realityNode(): ProxyNode {
        val node = UriParser.parse(
            "vless://$uuid@example.com:443?security=reality&sni=www.microsoft.com&pbk=$pbk&sid=ab12&type=tcp&flow=xtls-rprx-vision#R",
        )
        assertNotNull(node)
        return node
    }

    @Test
    fun flagsDecorate() {
        assertEquals("🇩🇪 Германия", Flags.decorate("DE Германия"))
        assertEquals("🇵🇱 Варшава", Flags.decorate("PL Варшава"))
        assertEquals("Fast Node", Flags.decorate("Fast Node"))
    }

    @Test
    fun flagsSplitKeepsName() {
        assertEquals("DE", Flags.codeOf("DE Германия"))
        assertEquals("Германия", Flags.label("DE Германия"))
        assertEquals("Финляндия", Flags.label("FI-Финляндия"))
        assertEquals("GB", Flags.codeOf("UK London"))
        assertEquals(null, Flags.codeOf("Fast Node"))
        assertEquals("Fast Node", Flags.label("Fast Node"))
    }

    @Test
    fun flagsFromEmojiPrefix() {
        // именно так приходит подписка: флаг уже эмодзи, а Windows рисует его буквами
        assertEquals("DE", Flags.codeOf("🇩🇪 Германия"))
        assertEquals("Германия", Flags.label("🇩🇪 Германия"))
        assertEquals("PL", Flags.codeOf("🇵🇱 Польша"))
        assertEquals("Польша", Flags.label("🇵🇱 Польша"))
        assertEquals("EE", Flags.codeOf("🇪🇪 Эстония"))
        assertEquals("EU", Flags.codeOf("🇪🇺 Обход 2"))
        assertEquals("Обход 2", Flags.label("🇪🇺 Обход 2"))
        assertEquals("🇩🇪 Германия", Flags.decorate("🇩🇪 Германия"))
    }

    private fun base64(value: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val bytes = value.encodeToByteArray()
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(alphabet[(triple shr 18) and 63])
            out.append(alphabet[(triple shr 12) and 63])
            out.append(if (i + 1 < bytes.size) alphabet[(triple shr 6) and 63] else '=')
            out.append(if (i + 2 < bytes.size) alphabet[triple and 63] else '=')
            i += 3
        }
        return out.toString()
    }
}
