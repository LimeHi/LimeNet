package app.limenet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import app.limenet.core.parse.UriParser

class QrConfigPayloadTest {
    @Test
    fun acceptsAnOfflineNodeLink() {
        val sample = "trojan://sample-password@example.com:443#Example"

        assertEquals(sample, QrConfigPayload.validate(sample))
    }

    @Test
    fun rejectsSubscriptionUrlsAndUnrecognizedData() {
        assertFailsWith<IllegalArgumentException> {
            QrConfigPayload.validate("https://example.com/subscription")
        }
        assertFailsWith<IllegalArgumentException> {
            QrConfigPayload.validate("https://example.com/subscription\ntrojan://sample-password@example.net:443#Node")
        }
        assertFailsWith<IllegalArgumentException> {
            QrConfigPayload.validate("not a LimeNet config")
        }
    }

    @Test
    fun acceptsMultipleOfflineNodeLinks() {
        val sample = """vless://11111111-1111-1111-1111-111111111111@example.com:443?security=none#One
            trojan://sample-password@example.net:443#Two""".trimIndent()

        assertEquals(2, QrConfigPayload.validate(sample).lineSequence().mapNotNull(UriParser::parse).count())
    }

    @Test
    fun rejectsPayloadsTooLargeForOneQrCode() {
        assertFailsWith<IllegalArgumentException> {
            QrConfigPayload.validate("x".repeat(2_801))
        }
    }
}
