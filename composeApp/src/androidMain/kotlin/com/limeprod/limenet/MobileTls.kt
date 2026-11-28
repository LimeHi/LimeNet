package com.limeprod.limenet

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class CertInfo(
    val names: List<String> = emptyList(),
    val sha256: String? = null,
    val error: String? = null,
)

/**
 * Последняя линия защиты для узлов, где в ссылке нет домена (sni и Host — IP).
 * Один раз читаем сертификат с сервера и берём имя, на которое он выписан:
 * дальше TLS-проверка уже настоящая (цепочка + имя). Это TOFU.
 */
object MobileTls {

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun probe(host: String, port: Int, timeoutMs: Int = 4000): CertInfo = runCatching {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), null)

        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs

        val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        try {
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()

            val cert = ssl.session.peerCertificates.first() as X509Certificate
            CertInfo(
                names = namesOf(cert),
                sha256 = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.encoded)),
            )
        } finally {
            runCatching { ssl.close() }
            runCatching { raw.close() }
        }
    }.getOrElse { CertInfo(error = it.message ?: it.javaClass.simpleName) }

    /** DNS-имена из SAN, иначе CN. Шаблоны вида *.example.com не годятся как SNI. */
    private fun namesOf(cert: X509Certificate): List<String> {
        val san = buildList {
            runCatching {
                cert.subjectAlternativeNames?.forEach { entry ->
                    if (entry.size >= 2 && entry[0] == 2) {
                        (entry[1] as? String)?.let { add(it) }
                    }
                }
            }
        }

        val candidates = if (san.isNotEmpty()) {
            san
        } else {
            listOfNotNull(
                cert.subjectX500Principal.name
                    .split(',')
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
                    ?.substringAfter('='),
            )
        }

        return candidates
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.contains(':') }
            .distinct()
    }
}
