package app.limenet.desktop

import app.limenet.core.Mode
import app.limenet.core.model.DnsMode
import app.limenet.core.model.CoreEngine
import app.limenet.core.model.RuleAction
import app.limenet.core.model.RuleKind
import app.limenet.core.model.RoutingRule
import app.limenet.core.model.RoutingPreset
import app.limenet.core.model.Settings
import app.limenet.core.model.SplitTunnel
import app.limenet.core.parse.UriParser
import app.limenet.core.render.XrayConfig
import app.limenet.core.render.SingBoxConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверяем не только форму JSON, но и то, что сам Xray принимает конфиг:
 * `xray run -test`. Тест пропускается, если рядом нет vendor/xray.
 */
class ConfigValidityTest {

    private val exe = File(Platform.root, "vendor/xray/xray.exe")
    private val singBoxExe = File(Platform.root, "vendor/sing-box/sing-box.exe")

    private val uri =
        "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?security=reality&sni=www.microsoft.com&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0" +
            "&sid=ab12&type=tcp&flow=xtls-rprx-vision#Test"

    @Test
    fun xrayAcceptsWsWithIpSni() {
        if (!exe.exists()) return

        // Узел как в реальной подписке: sni — IP, домен только в Host.
        val node = assertNotNull(
            UriParser.parse(
                "vless://11111111-1111-1111-1111-111111111111@185.178.208.140:443" +
                    "?encryption=none&type=ws&security=tls&sni=185.178.208.140" +
                    "&path=%2Fshop%2Fcatalog&host=marshalltmp.top.#WS",
            ),
        )

        val config = XrayConfig.render(node, Settings(mode = Mode.TUN), 10809, 10808)
        val dir = File(System.getProperty("java.io.tmpdir"), "limenet-config-test").apply { mkdirs() }
        val file = File(dir, "ws-ip-sni.json")
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config))

        val process = ProcessBuilder(exe.absolutePath, "run", "-test", "-c", file.absolutePath)
            .directory(exe.parentFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "xray не ответил вовремя")
        assertEquals(0, process.exitValue(), output.takeLast(600))
    }

    @Test
    fun xrayAcceptsRenderedConfigs() {
        if (!exe.exists()) return

        val node = assertNotNull(UriParser.parse(uri))

        val variants = listOf(
            "tun-ru-doh" to Settings(mode = Mode.TUN, routing = RoutingPreset.RU_DIRECT),
            "proxy-all" to Settings(mode = Mode.PROXY, routing = RoutingPreset.ALL_PROXY),
            "tun-dot-split" to Settings(
                mode = Mode.TUN,
                routing = RoutingPreset.RU_DIRECT,
                dns = Settings().dns.copy(mode = DnsMode.DOT, remoteServer = "1.1.1.1"),
                splitTunnel = SplitTunnel(directApps = listOf("discord.exe")),
            ),
            "proxy-system-dns" to Settings(
                mode = Mode.PROXY,
                routing = RoutingPreset.ALL_PROXY,
                dns = Settings().dns.copy(mode = DnsMode.SYSTEM),
            ),
        )

        val dir = File(System.getProperty("java.io.tmpdir"), "limenet-config-test").apply { mkdirs() }
        val json = Json { prettyPrint = true }

        for ((name, settings) in variants) {
            val config = XrayConfig.render(node, settings, 10809, 10808)
            val file = File(dir, "$name.json")
            file.writeText(json.encodeToString(JsonObject.serializer(), config))

            val process = ProcessBuilder(exe.absolutePath, "run", "-test", "-c", file.absolutePath)
                .directory(exe.parentFile)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)

            assertTrue(finished, "$name: xray не ответил вовремя")
            assertEquals(0, process.exitValue(), "$name: ${output.takeLast(600)}")
        }
    }

    @Test
    fun singBoxAcceptsSupportedRenderedConfigs() {
        if (!singBoxExe.exists()) return

        val sources = listOf(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=reality&sni=www.microsoft.com&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=ab12&type=tcp&flow=xtls-rprx-vision#VLESS",
            "vmess://eyJhZGQiOiJleGFtcGxlLmNvbSIsInBvcnQiOjQ0MywiaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEiLCJhaWQiOjAsInNjeSI6ImF1dG8iLCJuZXQiOiJ3cyIsInRscyI6InRscyIsInBhdGgiOiIvd3MiLCJob3N0IjoiZXhhbXBsZS5jb20ifQ==",
            "trojan://password@example.com:443?sni=example.com#Trojan",
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@example.com:443#Shadowsocks",
        )
        val settingsVariants = listOf(
            "proxy-all" to Settings(mode = Mode.PROXY, routing = RoutingPreset.ALL_PROXY),
            "tun-ru" to Settings(mode = Mode.TUN, routing = RoutingPreset.RU_DIRECT),
            "tun-custom" to Settings(
                mode = Mode.TUN,
                routing = RoutingPreset.CUSTOM,
                customRules = listOf(
                    RoutingRule(RuleKind.GEOSITE, "category-ads-all", RuleAction.BLOCK),
                    RoutingRule(RuleKind.DOMAIN_SUFFIX, "example.org", RuleAction.DIRECT),
                    RoutingRule(RuleKind.GEOIP, "ru", RuleAction.PROXY),
                ),
            ),
            "tun-network-options" to Settings(
                mode = Mode.TUN,
                tunMtu = 1400,
                blockQuic = true,
            ),
        )
        val dir = File(System.getProperty("java.io.tmpdir"), "limenet-singbox-config-test").apply { mkdirs() }
        val json = Json { prettyPrint = true }

        for ((variant, settings) in settingsVariants) {
            for ((index, source) in sources.withIndex()) {
                val node = assertNotNull(UriParser.parse(source), "Could not parse fixture $index")
                val config = SingBoxConfig.render(node, settings.copy(engine = CoreEngine.SING_BOX), 10809, 10808)
                val file = File(dir, "singbox-$variant-$index.json")
                file.writeText(json.encodeToString(JsonObject.serializer(), config))
                val process = ProcessBuilder(singBoxExe.absolutePath, "check", "-c", file.absolutePath)
                    .directory(singBoxExe.parentFile)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Sing-box did not finish checking $variant fixture $index")
                assertEquals(0, process.exitValue(), "$variant fixture $index: ${output.takeLast(800)}")
            }
        }

        val fragmentedNode = assertNotNull(UriParser.parse(sources.first()))
        val fragmentedSettings = Settings(
            mode = Mode.TUN,
            tlsFragment = true,
            tlsRecordFragment = true,
        )
        val fragmentedConfig = SingBoxConfig.render(
            fragmentedNode, fragmentedSettings, 10809, 10808,
        )
        val fragmentedFile = File(dir, "singbox-fragmented-tls.json")
        fragmentedFile.writeText(json.encodeToString(JsonObject.serializer(), fragmentedConfig))
        val fragmentedCheck = ProcessBuilder(singBoxExe.absolutePath, "check", "-c", fragmentedFile.absolutePath)
            .directory(singBoxExe.parentFile)
            .redirectErrorStream(true)
            .start()
        val fragmentedOutput = fragmentedCheck.inputStream.bufferedReader().readText()
        assertTrue(fragmentedCheck.waitFor(30, TimeUnit.SECONDS), "Sing-box TLS fragment config check timed out")
        assertEquals(0, fragmentedCheck.exitValue(), "fragmented TLS config: ${fragmentedOutput.takeLast(800)}")
    }
}
