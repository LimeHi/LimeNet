package app.limenet.core

import app.limenet.core.parse.UriParser

/** Validates QR contents as an offline URI/list import, never as a subscription URL. */
object QrConfigPayload {
    fun validate(value: String): String {
        val payload = value.removePrefix("\uFEFF").trim()
        require(payload.isNotEmpty()) {
            "QR-код пустой"
        }
        require(payload.encodeToByteArray().size <= 2_800) {
            "Конфигурация слишком большая для одного QR-кода"
        }
        require(payload.lineSequence().none { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
        }) {
            "QR-код должен содержать конфигурацию, а не ссылку на подписку"
        }
        val nodes = payload.lineSequence().mapNotNull(UriParser::parse).toList()
        require(nodes.isNotEmpty()) {
            "QR-код не содержит конфигурацию LimeNet"
        }
        return payload
    }
}
