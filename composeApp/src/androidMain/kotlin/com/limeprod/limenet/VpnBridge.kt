package com.limeprod.limenet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Состояние VPN, общее для сервиса и интерфейса: они живут в одном процессе,
 * отдельный IPC не нужен.
 */
object VpnBridge {
    /** Контроллер может реагировать на падение ядра без фонового polling. */
    @Volatile
    var onCoreFailure: (() -> Unit)? = null

    /** Ядро поднято и TUN создан. */
    var running by mutableStateOf(false)

    /** Идёт подготовка (рендер конфига, запрос разрешения, старт ядра). */
    var starting by mutableStateOf(false)

    /** Service is retained while VPN is paused until the screen turns on. */
    var screenPaused by mutableStateOf(false)

    /** Причина сбоя для интерфейса; `null` — всё хорошо. */
    var message by mutableStateOf<String?>(null)

    /** Имя выбранного сервера, показываемое в системном уведомлении. */
    var serverName by mutableStateOf<String?>(null)

    var startedAt by mutableStateOf(0L)

    fun reset() {
        running = false
        starting = false
        screenPaused = false
        startedAt = 0L
        serverName = null
    }

    fun reportCoreFailure(reason: String) {
        message = reason
        onCoreFailure?.invoke()
    }
}
