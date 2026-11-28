package com.limeprod.limenet

import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Отпечаток устройства для панелей с привязкой (Remnawave/Marzban, HWID).
 *
 * Значение стабильно между запусками: берём `ANDROID_ID`, а если он недоступен —
 * случайный UUID, и в обоих случаях один раз сохраняем результат в filesDir,
 * чтобы новая строка не считалась новым устройством.
 */
object MobileHwid {

    private val file: File get() = File(AndroidEnv.dataDir, "hwid.txt")

    val value: String by lazy { load() }

    val os: String = "Android"

    val osVersion: String = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" }

    val model: String = buildString {
        append(Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() })
        if (Build.MODEL.orEmpty().isNotBlank()) append(' ').append(Build.MODEL)
    }.trim().ifBlank { "Android" }

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

        // SSAID уникален для пары «приложение+пользователь» и не сбрасывается
        // при обновлении. Значение фабрики бракованных прошивок игнорируем.
        val androidId = runCatching {
            Settings.Secure.getString(
                AndroidEnv.context.contentResolver,
                Settings.Secure.ANDROID_ID,
            )
        }.getOrNull()

        val seed = androidId
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: UUID.randomUUID().toString()

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
