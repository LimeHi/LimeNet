package app.limenet.core

/**
 * Разбор диплинков (limenet://) вида:
 *  - limenet://add?url=https%3A%2F%2Fexample.com%2Fsub
 *  - limenet://add/<base64url от ссылки>
 *  - limenet://import/<base64url от списка vless://…>
 *  - limenet://sub/<base64url>
 *  - limenet://<base64url>            (короткий вариант)
 *  - limenet://vless://…              (одна ссылка)
 */
object DeepLink {

    /** Единственная схема приложения. */
    private val SCHEMES = listOf("limenet://")

    data class Request(val payload: String, val title: String? = null)

    fun parse(raw: String): Request? {
        val text = raw.trim()
        if (text.length < 8) return null
        val scheme = SCHEMES.firstOrNull { text.startsWith(it, ignoreCase = true) } ?: return null

        val body = text.substring(scheme.length)
        val queryAt = body.indexOf('?')
        val path = if (queryAt >= 0) body.substring(0, queryAt) else body
        val query = if (queryAt >= 0) body.substring(queryAt + 1) else ""

        val params = query.split('&')
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val key = Codec.percentDecode(part.substringBefore('=')).lowercase()
                val value = Codec.percentDecode(part.substringAfter('=', ""))
                key to value
            }
            .toMap()

        params["url"]?.takeIf { it.isNotBlank() }?.let {
            return Request(it, params["name"]?.takeIf { value -> value.isNotBlank() })
        }

        val trimmedPath = path.trim('/')
        if (trimmedPath.isEmpty()) return null

        // limenet://vless://… — после схемы сразу ссылка
        if (trimmedPath.contains("://")) {
            return Request(Codec.percentDecode(trimmedPath), params["name"]?.takeIf { it.isNotBlank() })
        }

        val slash = trimmedPath.indexOf('/')
        val action = if (slash >= 0) trimmedPath.substring(0, slash).lowercase() else trimmedPath.lowercase()
        val value = if (slash >= 0) trimmedPath.substring(slash + 1) else ""

        val known = setOf("add", "import", "sub", "subscribe", "config", "link")
        val payload = if (action in known) value else trimmedPath
        if (payload.isBlank()) return null

        return Request(decodePayload(payload), params["name"]?.takeIf { it.isNotBlank() })
    }

    private fun decodePayload(value: String): String {
        val trimmed = value.trim()
        val percent = Codec.percentDecode(trimmed)
        if (percent.contains("://")) return percent

        Codec.base64Decode(trimmed)?.takeIf { it.contains("://") }?.let { return it.trim() }
        Codec.base64Decode(percent)?.takeIf { it.contains("://") }?.let { return it.trim() }
        return percent
    }
}
