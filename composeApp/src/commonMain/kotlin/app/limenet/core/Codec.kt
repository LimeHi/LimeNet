package app.limenet.core

object Codec {
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Decode(input: String): String? {
        val cleaned = input
            .filterNot { it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
            .trimEnd('=')
        if (cleaned.isEmpty()) return null

        val out = ArrayList<Byte>(cleaned.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in cleaned) {
            val value = B64.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray().decodeToString()
    }

    fun percentDecode(value: String): String {
        if ('%' !in value && '+' !in value) return value
        val text = StringBuilder(value.length)
        val bytes = ArrayList<Byte>(8)

        fun flush() {
            if (bytes.isNotEmpty()) {
                text.append(bytes.toByteArray().decodeToString())
                bytes.clear()
            }
        }

        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.add(code.toByte())
                    i += 3
                    continue
                }
            }
            if (ch == '+') {
                bytes.add(' '.code.toByte())
                i++
                continue
            }
            flush()
            text.append(ch)
            i++
        }
        flush()
        return text.toString()
    }

    fun fnv1a(value: String): String {
        var hash = 14695981039346656037uL
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toUByte().toULong())
            hash *= 1099511628211uL
        }
        return hash.toString(16).padStart(16, '0').take(12)
    }
}
