package com.limeprod.limenet

import android.app.Application
import java.io.File

/**
 * Пути Android-клиента. Ядро Xray кладётся в jniLibs как `libxray.so`
 * (так его распаковывает система, и оттуда его можно запускать), geo-базы
 * вытаскиваются из assets в filesDir.
 */
object AndroidEnv {
    private var app: Application? = null

    fun attach(application: Application) {
        app = application
    }

    private fun requireApp(): Application =
        app ?: error("AndroidEnv.attach() не вызван")

    /** Контекст приложения — для сервиса и системных API. */
    val context: android.content.Context get() = requireApp()

    val dataDir: File get() = requireApp().filesDir

    /** Исполняемый Xray: системный каталог нативных библиотек (там разрешён exec). */
    val xrayBin: File
        get() = File(requireApp().applicationInfo.nativeLibraryDir, "libxray.so")

    /** Папка с geoip.dat/geosite.dat для XRAY_LOCATION_ASSET. */
    val assetsDir: File get() = File(dataDir, "assets")

    /** Рабочая папка для временных конфигов проверки узлов. */
    val testsDir: File get() = File(dataDir, "tests")

    val configFile: File get() = File(dataDir, "xray-android.json")

    val stateFile: File get() = File(dataDir, "state.json")

    val favoritesFile: File get() = File(dataDir, "favorites.txt")

    val logFile: File get() = File(dataDir, "xray.log")

    /** Отдельный журнал жизненного цикла VPN: его ядро не затирает. */
    val vpnLogFile: File get() = File(dataDir, "vpn.log")

    /** Достаём geo-базы из APK один раз. */
    fun ensureAssets() {
        val target = assetsDir
        if (!target.exists()) target.mkdirs()
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val out = File(target, name)
            if (out.exists() && out.length() > 0) continue
            runCatching {
                requireApp().assets.open(name).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { log("assets: не удалось распаковать $name: $it") }
        }
    }

    /** Последние строки лога ядра — для сообщения о падении. */
    fun tailLog(lines: Int = 3): String = runCatching {
        logFile.readLines().takeLast(lines).joinToString(" ").trim()
    }.getOrDefault("")

    fun log(text: String) {
        runCatching {
            logFile.appendText("[${stamp()}] $text\n")
        }
    }

    /** События сервиса (старт/стоп/код выхода ядра) — в отдельный файл, чтобы не терялись. */
    fun vpnLog(text: String) {
        runCatching {
            vpnLogFile.appendText("[${stamp()}] $text\n")
        }
    }

    /** Последние строки журнала сервиса — для диагностики. */
    fun tailVpnLog(lines: Int = 40): String = runCatching {
        vpnLogFile.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    private fun stamp(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
}
