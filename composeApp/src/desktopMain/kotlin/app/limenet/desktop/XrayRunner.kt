package app.limenet.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

interface CoreRunner {
    val logs: List<String>
    val running: Boolean
    fun validate(config: JsonObject)
    fun start(config: JsonObject, httpPort: Int)
    fun stop()
}

class XrayRunner(private val exe: File, private val workDir: File) : CoreRunner {

    private var process: Process? = null
    private val buffer = mutableListOf<String>()

    override val logs: List<String>
        get() = synchronized(buffer) { buffer.toList() }

    override val running: Boolean
        get() = process?.isAlive == true

    private fun configFile(config: JsonObject): File {
        workDir.mkdirs()
        val file = File(workDir, "config.json")
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config))
        return file
    }

    override fun validate(config: JsonObject) {
        val file = configFile(config)
        val process = ProcessBuilder(exe.absolutePath, "run", "-test", "-c", file.absolutePath)
            .directory(exe.parentFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
            error(output.lines().firstOrNull { it.contains("Failed") || it.contains("failed") }
                ?: output.trim().ifEmpty { "ядро отклонило конфиг" })
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
        if (!started.isAlive) {
            val tail = logs.takeLast(3).joinToString(" ")
            error(tail.ifEmpty { "ядро завершилось сразу после старта" })
        }
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
            val alive = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
            }.isSuccess
            if (alive) return
            Thread.sleep(150)
        }
        error("ядро не подняло порт за ${timeoutMs / 1000} с")
    }
}
