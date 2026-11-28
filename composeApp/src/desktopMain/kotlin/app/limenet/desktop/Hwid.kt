package app.limenet.desktop

import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID

/**
 * Отпечаток устройства для панелей с привязкой (Remnawave/Marzban, HWID).
 *
 * Значение генерируется один раз и хранится в файле `%APPDATA%/LimeNet/hwid.txt`:
 * панель считает каждую новую строку отдельным устройством, поэтому
 * идентификатор должен быть стабильным между запусками.
 */
object Hwid {

    private val file: File get() = File(Platform.dataDir, "hwid.txt")

    val value: String by lazy { load() }

    val os: String = System.getProperty("os.name").orEmpty().let { name ->
        when {
            name.contains("Windows", ignoreCase = true) -> "Windows"
            name.contains("Mac", ignoreCase = true) -> "macOS"
            name.contains("Linux", ignoreCase = true) -> "Linux"
            name.isBlank() -> "Desktop"
            else -> name
        }
    }

    val osVersion: String
        get() = System.getProperty("os.version").orEmpty().ifBlank { "unknown" }

    /** Имя машины; если недоступно — архитектура. */
    val model: String
        get() = runCatching { InetAddress.getLocalHost().hostName?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getProperty("os.arch").orEmpty().ifBlank { "PC" }

    /** Заголовки, которые ждут панели с HWID-привязкой. */
    fun headers(): Map<String, String> = linkedMapOf(
        "x-hwid" to value,
        "x-device-os" to os,
        "x-ver-os" to osVersion,
        "x-device-model" to model,
    )

    private fun load(): String {
        val stored = runCatching { file.takeIf { it.exists() }?.readText()?.trim() }
            .getOrNull()
        if (!stored.isNullOrEmpty()) return stored

        val seed = UUID.randomUUID().toString() + System.getProperty("user.name").orEmpty()
        val generated = digest(seed)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(generated)
        }
        return generated
    }

    /** 32 шестнадцатеричных символа — привычный вид HWID. */
    private fun digest(seed: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(seed.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(32)
}
