package app.limenet.core

/** One public subscription or bot offered in the in-app catalog. */
data class CatalogEntry(
    val name: String,
    val url: String,
    val description: String,
    val status: Status,
    val channelUrl: String? = null,
) {
    enum class Status { FREE, PAID }

    val opensExternally: Boolean
        get() = status == Status.PAID || url.isTelegramLink()
}

/** Parses records separated by `---`; malformed or incomplete records are skipped. */
fun parseCatalog(text: String): List<CatalogEntry> = text
    .removePrefix("\uFEFF")
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .split(Regex("(?m)^\\s*---\\s*$"))
    .mapNotNull { block ->
        val lines = block.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size != 4 || lines.any(String::isEmpty)) return@mapNotNull null

        val title = lines[0]
        val separator = title.indexOf('|')
        val name = if (separator >= 0) title.substring(0, separator).trim() else ""
        val url = if (separator >= 0) title.substring(separator + 1).trim() else title
        if (url.isEmpty()) return@mapNotNull null

        val status = when (lines[2].lowercase()) {
            "free" -> CatalogEntry.Status.FREE
            "paid" -> CatalogEntry.Status.PAID
            else -> return@mapNotNull null
        }
        CatalogEntry(
            name = name.ifEmpty { url.catalogHost().ifEmpty { url } },
            url = url,
            description = lines[1],
            status = status,
            channelUrl = lines[3].takeUnless { it == "-" || it.isEmpty() },
        )
    }

private fun String.isTelegramLink(): Boolean = catalogHost().equals("t.me", ignoreCase = true)

private fun String.catalogHost(): String = substringAfter("://", this)
    .substringBefore('/')
    .substringBefore('?')
    .substringBefore('#')
    .substringBefore(':')
    .trim()
