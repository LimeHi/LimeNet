package app.limenet.desktop

import app.limenet.ui.APP_NAME

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

object Platform {
    val root: File by lazy { findRoot() }
    val dataDir: File by lazy {
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), APP_NAME).apply { mkdirs() }
    }
    val coreDir: File by lazy { File(dataDir, "core").apply { mkdirs() } }
    val appVersion: Pair<String, Int> by lazy {
        runCatching {
            val bundled = File(root, "version.txt")
            val common = File(root, "common/version.txt")
            val versionFile = if (bundled.exists()) bundled else common
            val lines = versionFile.readLines()
            (lines.getOrNull(0)?.trim().orEmpty() to lines.getOrNull(1)?.trim()?.toIntOrNull())
        }.getOrNull()?.let { (name, code) ->
            if (name.isNotBlank() && code != null) name to code else "0.1.0" to 1
        } ?: ("0.1.0" to 1)
    }
    val xrayExe: File get() = File(root, "vendor/xray/xray.exe")
    val singBoxExe: File get() = File(root, "vendor/sing-box/sing-box.exe")
    val proxyScript: File get() = File(root, "scripts/sysproxy.ps1")

    /** Новый формат состояния (JSON). */
    val stateFile: File get() = File(dataDir, "state.json")

    /** Старый формат, из него мигрируем при первом запуске. */
    val legacyStateFile: File get() = File(dataDir, "state-kotlin.txt")

    /**
     * Корень приложения ищем не только от рабочей директории: при запуске по
     * диплинку из реестра рабочая директория — system32, зато в classpath есть
     * путь к нашему jar. Поэтому проверяем и его.
     */
    private fun findRoot(): File {
        val starts = mutableListOf<File>()
        System.getProperty("user.dir")?.let { starts += File(it).absoluteFile }
        System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { entry ->
                val file = File(entry).absoluteFile
                starts += if (file.isDirectory) file else file.parentFile
            }
        System.getProperty("jpackage.app-path")?.let { starts += File(it).absoluteFile.parentFile }
        // jpackage кладёт упакованные ресурсы (vendor/xray, scripts) в <app>/resources
        System.getProperty("compose.application.resources.dir")
            ?.let { starts += File(it).absoluteFile }

        for (start in starts) {
            var dir: File? = start
            var depth = 0
            while (dir != null && depth < 10) {
                if (File(dir, "vendor/xray/xray.exe").exists() || File(dir, "vendor/sing-box/sing-box.exe").exists()) return dir
                if (File(dir, "common/vendor/xray/xray.exe").exists() || File(dir, "common/vendor/sing-box/sing-box.exe").exists()) return File(dir, "common")
                dir = dir.parentFile
                depth++
            }
        }
        return File(System.getProperty("user.dir") ?: ".").absoluteFile
    }

    fun isElevated(): Boolean = runCatching {
        val process = ProcessBuilder("net", "session").redirectErrorStream(true).start()
        process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    fun setSystemProxy(on: Boolean, server: String = "127.0.0.1:10809") {
        if (!proxyScript.exists()) return
        runCatching {
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", proxyScript.absolutePath,
                "-Mode", if (on) "on" else "off",
                "-Server", server,
            ).redirectErrorStream(true).start().waitFor(15, TimeUnit.SECONDS)
        }
    }

    /** Имена запущенных процессов — подсказки для разделения трафика по приложениям. */
    fun runningApps(): List<String> = runCatching {
        ProcessHandle.allProcesses()
            .map { handle -> handle.info().command().orElse("") }
            .filter { path -> path.isNotEmpty() }
            .map { path -> File(path).name.lowercase() }
            .filter { name -> name.endsWith(".exe") && name != "system" }
            .distinct()
            .sorted()
            .toList()
    }.getOrDefault(emptyList())

    /**
     * Регистрируем схему limenet:// в HKCU (без прав администратора), чтобы
     * подписку можно было добавить кликом по ссылке.
     */
    fun registerProtocol(): Boolean = runCatching {
        val command = launchCommand()
        // reg.exe разбирает командную строку сам, поэтому кавычки внутри
        // значения приходится экранировать обратным слешем.
        val escaped = command.replace("\"", "\\\"")
        reg("add", "HKCU\\Software\\Classes\\limenet", "/ve", "/d", "URL:$APP_NAME", "/f")
        reg("add", "HKCU\\Software\\Classes\\limenet", "/v", "URL Protocol", "/d", "", "/f")
        reg("add", "HKCU\\Software\\Classes\\limenet\\shell\\open\\command", "/ve", "/d", escaped, "/f")

        Platform.logDebug("protocol: зарегистрирована схема limenet:// → $command")
        true
    }.onFailure { Platform.logDebug("protocol: не удалось зарегистрировать limenet://: $it") }
        .getOrDefault(false)

    private fun launchCommand(): String {
        val appPath = System.getProperty("jpackage.app-path")
        if (!appPath.isNullOrBlank()) return "\"$appPath\" \"%1\""

        val bin = File(System.getProperty("java.home"), "bin")
        val javaw = File(bin, "javaw.exe").takeIf { it.exists() } ?: File(bin, "java.exe")
        val classpath = System.getProperty("java.class.path") ?: ""
        return "\"${javaw.absolutePath}\" -cp \"$classpath\" $MAIN_CLASS \"%1\""
    }

    private fun reg(vararg args: String) {
        val process = ProcessBuilder(listOf("reg.exe") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream
            .bufferedReader(java.nio.charset.Charset.forName("IBM866"))
            .readText()
        process.waitFor(10, TimeUnit.SECONDS)
        if (process.exitValue() != 0) error("reg.exe: ${output.trim()}")
    }

    /** Метка «уже перезапущены с правами»: по ней приложение не просит UAC повторно (защита от цикла). */
    const val ELEVATED_FLAG = "--elevated"

    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

    /**
     * Перезапуск с запросом UAC. [extraArgs] (например, ссылка limenet://) передаются новому процессу.
     * Успех — новый процесс запущен, текущий можно закрывать; ошибка — UAC отклонён или недоступен.
     */
    fun relaunchElevated(extraArgs: List<String> = emptyList()): Result<Unit> = runCatching {
        val workingDir = root.absolutePath
        val appPath = System.getProperty("jpackage.app-path")

        // Когда приложение упаковано через jpackage — перезапускаем сам exe.
        // Когда запущено из jar/классов (разработка) — поднимаем тот же javaw
        // с тем же classpath: путь к java и аргументы берём у текущей JVM.
        val exe: String
        val arguments: List<String>
        if (!appPath.isNullOrBlank()) {
            exe = appPath
            arguments = listOf(ELEVATED_FLAG) + extraArgs
        } else {
            val bin = File(System.getProperty("java.home"), "bin")
            val javaw = File(bin, "javaw.exe").takeIf { it.exists() }
                ?: File(bin, "java.exe")
            exe = javaw.absolutePath
            arguments = buildList {
                add("-Dcompose.application.configure.swing.globals=true")
                System.getProperty("compose.application.resources.dir")?.let {
                    add("-Dcompose.application.resources.dir=$it")
                }
                add("-cp")
                add(System.getProperty("java.class.path"))
                add(MAIN_CLASS)
                add(ELEVATED_FLAG)
                addAll(extraArgs)
            }
        }

        fun quote(value: String) = "'" + value.replace("'", "''") + "'"
        val argLine = arguments.joinToString(" ") { "\"" + it + "\"" }

        val script = buildString {
            append("\$ErrorActionPreference='Stop'; ")
            append("Start-Process -FilePath ").append(quote(exe))
            if (argLine.isNotEmpty()) append(" -ArgumentList ").append(quote(argLine))
            if (workingDir.isNotEmpty()) append(" -WorkingDirectory ").append(quote(workingDir))
            append(" -Verb RunAs")
        }

        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        val code = if (finished) process.exitValue().toString() else "timeout"

        logDebug(
            buildString {
                appendLine("exe=$exe")
                appendLine("args=$argLine")
                appendLine("cwd=$workingDir")
                appendLine("script=$script")
                appendLine("output=$output")
                appendLine("exit=$code")
            },
        )

        if (!finished || process.exitValue() != 0) {
            error(output.trim().ifEmpty { "UAC отклонён или недоступен" })
        }
    }

    fun logDebug(text: String) {
        runCatching {
            File(dataDir, "elevate.log").appendText(
                "=== ${java.time.LocalDateTime.now()} ===\n$text\n",
            )
        }
    }

    private const val MAIN_CLASS = "app.limenet.desktop.MainKt"
}

/**
 * Один экземпляр приложения: второй запуск (например, по limenet://) просто
 * передаёт ссылку уже открытому окну через localhost-сокет.
 */
object DeepLinks {
    private const val PORT = 47821

    fun send(link: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 800)
            socket.getOutputStream().apply {
                write((link.replace("\n", " ") + "\n").encodeToByteArray())
                flush()
            }
        }
        true
    }.getOrDefault(false)

    fun listen(onLink: (String) -> Unit): Boolean = runCatching {
        val server = ServerSocket(PORT, 8, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { client ->
                        client.soTimeout = 2000
                        val line = client.getInputStream().bufferedReader().readLine()
                        if (!line.isNullOrBlank()) onLink(line)
                    }
                }
            }
        }.apply {
            name = "limenet-deeplink"
            isDaemon = true
        }.start()
        true
    }.onFailure { Platform.logDebug("deeplink: слушатель не поднялся: $it") }
        .getOrDefault(false)
}
