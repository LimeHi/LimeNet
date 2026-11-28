package com.limeprod.limenet

import app.limenet.core.ProxyNode
import app.limenet.core.model.TestFailure
import app.limenet.core.render.XrayConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class UrlResult(
    val latencyMs: Int? = null,
    val speedBps: Long? = null,
    val error: String? = null,
    val failure: TestFailure? = null,
) {
    val ok: Boolean get() = error == null && latencyMs != null
}

/**
 * Проверка узла «по-настоящему»: поднимаем временное ядро Xray с одним
 * SOCKS-входом, через него делаем HTTP-запрос к тестовому URL и меряем
 * задержку и скорость. Ядро стартует как отдельный процесс из
 * nativeLibraryDir (`libxray.so`).
 */
class MobileUrlTester(private val exe: File, private val workDir: File) {

    private val json = Json { prettyPrint = true }
    private val counter = AtomicInteger()
    private val cancelled = AtomicBoolean(false)
    private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    fun beginCheck() {
        cancelled.set(false)
    }

    /** Stops blocking network operations and any temporary Xray instances. */
    fun cancelActive() {
        cancelled.set(true)
        activeSockets.toList().forEach { runCatching { it.close() } }
        activeProcesses.toList().forEach { runCatching { it.destroyForcibly() } }
    }

    fun test(
        node: ProxyNode,
        testUrl: String,
        speedUrl: String? = null,
        timeoutMs: Int = URL_TIMEOUT_MS,
        configFactory: ((socksPort: Int) -> JsonObject)? = null,
    ): UrlResult {
        if (!node.supported) return UrlResult(error = node.unsupportedReason ?: "нет движка")
        if (!exe.exists()) return UrlResult(error = "не найдено ядро Xray")
        if (cancelled.get()) return UrlResult(error = "проверка остановлена")

        val dir = File(workDir, "node-${node.id}-${counter.incrementAndGet()}")
        var process: Process? = null
        try {
            dir.mkdirs()
            val port = freePort()
            val config = configFactory?.invoke(port) ?: XrayConfig.renderTest(node, port)
            val file = File(dir, "config.json")
            file.writeText(json.encodeToString(JsonObject.serializer(), config))

            val builder = ProcessBuilder(exe.absolutePath, "run", "-c", file.absolutePath)
                .directory(AndroidEnv.dataDir)
                .redirectErrorStream(true)
                .redirectOutput(File(dir, "out.log"))
            builder.environment()["XRAY_LOCATION_ASSET"] = AndroidEnv.assetsDir.absolutePath
            process = builder.start()
            activeProcesses.add(process)
            if (cancelled.get()) process.destroyForcibly()

            if (cancelled.get() || !waitForPort(port, process, STARTUP_TIMEOUT_MS)) {
                val tail = runCatching {
                    File(dir, "out.log").readLines().takeLast(3).joinToString(" ")
                }.getOrDefault("")
                val timedOut = !cancelled.get() && process?.isAlive == true
                return UrlResult(
                    error = tail.ifBlank { if (timedOut) "тайм-аут запуска ядра" else "ядро не поднялось" },
                    failure = if (timedOut) TestFailure.TIMEOUT else TestFailure.UNAVAILABLE,
                )
            }

            val uri = URI.create(testUrl)
            val latency = withTunnel(port, uri, timeoutMs) { socket ->
                val first = request(socket, uri, timeoutMs, readBody = false, keepAlive = true)
                if (first.reusable) {
                    runCatching { request(socket, uri, timeoutMs, readBody = false, keepAlive = true).latencyMs }
                        .getOrNull() ?: first.latencyMs
                } else first.latencyMs
            }

            val speed = if (speedUrl.isNullOrBlank()) {
                null
            } else {
                runCatching {
                    val speedUri = URI.create(speedUrl)
                    withTunnel(port, speedUri, timeoutMs * 2) { socket ->
                        request(socket, speedUri, timeoutMs * 2, readBody = true).speedBps
                    }
                }.getOrNull()
            }

            return UrlResult(latencyMs = latency, speedBps = speed)
        } catch (error: Exception) {
            val timeout = generateSequence<Throwable>(error) { it.cause }.any {
                it is SocketTimeoutException ||
                    it.message?.contains("таймаут", ignoreCase = true) == true ||
                    it.message?.contains("timed out", ignoreCase = true) == true
            }
            return UrlResult(
                error = error.message ?: error.javaClass.simpleName,
                failure = if (timeout) TestFailure.TIMEOUT else TestFailure.UNAVAILABLE,
            )
        } finally {
            process?.let(activeProcesses::remove)
            runCatching { process?.destroy() }
            runCatching { process?.waitFor(1, TimeUnit.SECONDS) }
            if (process?.isAlive == true) runCatching { process.destroyForcibly() }
            runCatching { dir.deleteRecursively() }
        }
    }

    /** Выполняет HTTPS-пробу через SOCKS-вход работающего VPN-ядра. */
    fun testViaSocks(
        testUrl: String,
        socksPort: Int,
        timeoutMs: Int = URL_TIMEOUT_MS,
        speedUrl: String? = null,
    ): UrlResult {
        if (cancelled.get()) return UrlResult(error = "проверка остановлена")
        return try {
            val uri = URI.create(testUrl)
            val latency = withTunnel(socksPort, uri, timeoutMs) { socket ->
                val first = request(socket, uri, timeoutMs, readBody = false, keepAlive = true, acceptAnyStatus = true)
                if (first.reusable) {
                    runCatching {
                        request(socket, uri, timeoutMs, readBody = false, keepAlive = true, acceptAnyStatus = true).latencyMs
                    }.getOrNull() ?: first.latencyMs
                } else first.latencyMs
            }
            val speed = speedUrl?.let { url ->
                runCatching {
                    val speedUri = URI.create(url)
                    withTunnel(socksPort, speedUri, timeoutMs * 2) { socket ->
                        request(socket, speedUri, timeoutMs * 2, readBody = true).speedBps
                    }
                }.getOrNull()
            }
            UrlResult(
                latencyMs = latency,
                speedBps = speed,
                error = if (speedUrl != null && speed == null) "не удалось измерить скорость" else null,
                failure = if (speedUrl != null && speed == null) TestFailure.UNAVAILABLE else null,
            )
        } catch (error: Exception) {
            val timeout = generateSequence<Throwable>(error) { it.cause }.any {
                it is SocketTimeoutException ||
                    it.message?.contains("таймаут", ignoreCase = true) == true ||
                    it.message?.contains("timed out", ignoreCase = true) == true
            }
            UrlResult(error = error.message ?: error.javaClass.simpleName,
                failure = if (timeout) TestFailure.TIMEOUT else TestFailure.UNAVAILABLE)
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitForPort(port: Int, process: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cancelled.get()) return false
            if (!process.isAlive) return false
            val ready = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
            }.isSuccess
            if (ready) return true
            Thread.sleep(120)
        }
        return false
    }

    private fun <T> withTunnel(
        socksPort: Int,
        uri: URI,
        timeoutMs: Int,
        block: (Socket) -> T,
    ): T {
        val host = uri.host ?: error("в тестовом URL нет хоста")
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80

        val raw = Socket()
        activeSockets.add(raw)
        try {
            check(!cancelled.get()) { "проверка остановлена" }
            raw.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            raw.soTimeout = timeoutMs
            socksConnect(raw, host, port, timeoutMs)

            val socket = if (uri.scheme == "https") {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
                ssl.startHandshake()
                ssl
            } else {
                raw
            }
            return block(socket)
        } finally {
            activeSockets.remove(raw)
            runCatching { raw.close() }
        }
    }

    private fun socksConnect(socket: Socket, host: String, port: Int, timeoutMs: Int) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()

        output.write(byteArrayOf(5, 1, 0))
        output.flush()
        val greeting = readExact(input, 2, timeoutMs)
        require(greeting[0].toInt() == 5 && greeting[1].toInt() == 0) { "SOCKS5 не принял подключение" }

        val hostBytes = host.encodeToByteArray()
        val request = ByteArray(7 + hostBytes.size)
        request[0] = 5
        request[1] = 1
        request[2] = 0
        request[3] = 3
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, 5)
        request[5 + hostBytes.size] = ((port shr 8) and 0xFF).toByte()
        request[6 + hostBytes.size] = (port and 0xFF).toByte()
        output.write(request)
        output.flush()

        val head = readExact(input, 4, timeoutMs)
        require(head[0].toInt() == 5) { "SOCKS5 ответил мусором" }
        val code = head[1].toInt() and 0xFF
        require(code == 0) { "SOCKS5 отклонил соединение (код $code)" }

        when (head[3].toInt() and 0xFF) {
            1 -> readExact(input, 4 + 2, timeoutMs)
            3 -> {
                val length = readExact(input, 1, timeoutMs)[0].toInt() and 0xFF
                readExact(input, length + 2, timeoutMs)
            }
            4 -> readExact(input, 16 + 2, timeoutMs)
            else -> error("неизвестный тип адреса SOCKS5")
        }
    }

    private data class HttpProbe(val latencyMs: Int, val speedBps: Long?, val reusable: Boolean)

    /** Измеряет запрос; для ответов без тела помечает соединение пригодным для повторного запроса. */
    private fun request(
        socket: Socket,
        uri: URI,
        timeoutMs: Int,
        readBody: Boolean,
        keepAlive: Boolean = false,
        acceptAnyStatus: Boolean = false,
    ): HttpProbe {
        val path = buildString {
            append(uri.rawPath?.ifEmpty { "/" } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(uri.host).append("\r\n")
            append("User-Agent: LimeNet/0.1\r\n")
            append("Accept: */*\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        }

        val started = System.nanoTime()
        val output = socket.getOutputStream()
        output.write(request.toByteArray())
        output.flush()

        val input = socket.getInputStream()
        val header = readHeader(input, 16 * 1024, timeoutMs)
        val latency = ((System.nanoTime() - started) / 1_000_000).toInt()

        val status = header.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
        require(status != null && if (acceptAnyStatus) status in 200..499 else status in 200..399) {
            "тестовый URL ответил ${status ?: "?"}"
        }

        val length = header.lineSequence()
            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull()
        val statusHasNoBody = status == 204 || status == 304 || status in 100..199
        val connectionCloses = header.lineSequence().any {
            it.startsWith("connection:", ignoreCase = true) && it.substringAfter(':').trim().equals("close", ignoreCase = true)
        }
        val http10 = header.lineSequence().firstOrNull()?.startsWith("HTTP/1.0") == true
        val reusable = keepAlive && !connectionCloses && !http10 && (statusHasNoBody || length == 0L)
        if (!readBody) return HttpProbe(latency, null, reusable)

        val bodyStarted = System.nanoTime()
        var read = 0L
        val buffer = ByteArray(16 * 1024)
        val limit = length?.takeIf { it in 1..20_000_000 } ?: 20_000_000L
        while (read < limit) {
            val count = input.read(buffer)
            if (count <= 0) break
            read += count
        }
        val elapsedMs = ((System.nanoTime() - bodyStarted) / 1_000_000).coerceAtLeast(1)
        val speed = read * 1000 / elapsedMs
        return HttpProbe(latency, speed, false)
    }

    private fun readHeader(input: InputStream, limit: Int, timeoutMs: Int): String {
        val out = java.io.ByteArrayOutputStream(1024)
        var tail = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size() < limit && System.currentTimeMillis() < deadline) {
            val next = input.read()
            if (next < 0) throw EOFException("сервер закрыл соединение до HTTP-ответа")
            out.write(next)
            tail = (tail shl 8) or (next and 0xFF)
            if (tail == 0x0D0A0D0A) break
        }
        if (tail != 0x0D0A0D0A) throw SocketTimeoutException("тайм-аут ожидания HTTP-ответа")
        return out.toByteArray().decodeToString()
    }

    private fun readExact(input: InputStream, count: Int, timeoutMs: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (offset < count) {
            if (System.currentTimeMillis() > deadline) error("таймаут SOCKS5")
            val read = input.read(result, offset, count - offset)
            if (read < 0) error("SOCKS5 закрыл соединение")
            offset += read
        }
        return result
    }

    companion object {
        const val URL_TIMEOUT_MS = 7_000
        const val STARTUP_TIMEOUT_MS = 4_000L
    }
}
