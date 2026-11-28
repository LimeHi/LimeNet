package com.limeprod.limenet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.limenet.core.model.AppState
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Состояние Android-клиента. Храним подписки как исходные ссылки, а узлы
 * разбираем заново при запуске — парсер можно обновлять без миграций.
 * Формат тот же, что на десктопе (`state.json`).
 */
class MobileStore(private val file: File = AndroidEnv.stateFile) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun load(): AppState {
        if (!file.exists()) return AppState()
        return runCatching {
            json.decodeFromString(AppState.serializer(), file.readText())
        }.onFailure { AndroidEnv.log("store: не удалось прочитать ${file.name}: $it") }
            .getOrNull()
            ?: AppState()
    }

    fun save(state: AppState) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(AppState.serializer(), state))
            if (file.exists() && !file.delete()) {
                file.writeText(temp.readText())
                temp.delete()
                return
            }
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { AndroidEnv.log("store: не удалось сохранить состояние: $it") }
    }
}

/**
 * Избранные серверы — дольки на главном экране (до 12). Недостающие позиции
 * заполняются случайными серверами текущей подписки. Хранится отдельным файлом,
 * настройки не затрагиваются.
 */
object MobileFavorites {
    const val MAX = 12
    private val file = AndroidEnv.favoritesFile

    var ids: List<String> by mutableStateOf(load())
        private set

    val full: Boolean get() = ids.size >= MAX

    fun isFav(id: String): Boolean = id in ids

    /** Добавить/убрать. `false` — добавить нельзя: уже 12. */
    fun toggle(id: String): Boolean {
        when {
            id in ids -> save(ids - id)
            ids.size < MAX -> save(ids + id)
            else -> return false
        }
        return true
    }

    private fun load(): List<String> = runCatching {
        if (file.exists()) {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX)
        } else {
            emptyList()
        }
    }.getOrDefault(emptyList())

    private fun save(new: List<String>) {
        ids = new
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(new.joinToString("\n"))
        }
    }
}
