package app.limenet.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class SingBoxRunner(private val exe: File, private val workDir: File) : CoreRunner {
    private var process: Process? = null
    private val buffer = mutableListOf<String>()

    override val logs: List<String>
        get() = synchronized(buffer) { buffer.toList() }

    override val running: Boolean
        get() = process?.isAlive == true

    private fun configFile(config: JsonObject): File {
        workDir.mkdirs()
        return File(workDir, "sing-box-config.json").apply {
            writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config))
        }
    }

    override fun validate(config: JsonObject) {
        if (!exe.isFile) error("Sing-box не найден: ${exe.absolutePath}")
        val file = configFile(config)
        val checked = ProcessBuilder(exe.absolutePath, "check", "-c", file.absolutePath)
            .directory(exe.parentFile)
            .redirectErrorStream(true)
            .start()
        val output = checked.inputStream.bufferedReader().readText()
        if (!checked.waitFor(30, TimeUnit.SECONDS) || checked.exitValue() != 0) {
            error(output.trim().ifEmpty { "Sing-box отклонил конфиг" })
        }
    }

    override fun start(config: JsonObject, httpPort: Int) {
        stop()
        synchronized(buffer) { buffer.clear() }
        val file = configFile(config)
        val started = ProcessBuilder(exe.absolutePath, "run", "-c", file.absolutePath)
            .directory(exe.parentFile)
            .redirectErrorStream(true)
            .start()
        process = started
        Thread {
            started.inputStream.bufferedReader().forEachLine { line ->
                synchronized(buffer) {
                    buffer.add(line)
                    if (buffer.size > 200) buffer.removeAt(0)
                }
            }
        }.apply { isDaemon = true }.start()
        waitForPort(httpPort)
        if (!started.isAlive) error(logs.takeLast(3).joinToString(" ").ifEmpty { "Sing-box завершился после старта" })
    }

    override fun stop() {
        val current = process ?: return
        process = null
        current.destroy()
        runCatching { current.waitFor(2, TimeUnit.SECONDS) }
        if (current.isAlive) current.destroyForcibly()
    }

    private fun waitForPort(port: Int, timeoutMs: Long = 12_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!process?.isAlive.orFalse()) error(logs.takeLast(3).joinToString(" ").ifEmpty { "Sing-box завершился при запуске" })
            val listening = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
            }.isSuccess
            if (listening) return
            Thread.sleep(150)
        }
        error("Sing-box не поднял локальный порт за ${timeoutMs / 1000} с")
    }

    private fun Boolean?.orFalse(): Boolean = this == true
}
