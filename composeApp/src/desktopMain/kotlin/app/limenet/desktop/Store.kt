package app.limenet.desktop

import app.limenet.core.Codec
import app.limenet.core.Mode
import app.limenet.core.model.AppState
import app.limenet.core.model.Settings
import app.limenet.core.model.SubscriptionInfo
import app.limenet.core.parse.UriParser
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

/**
 * Состояние приложения: подписки храним как исходные ссылки, а узлы
 * разбираем заново при каждом запуске — так обновление парсера сразу
 * подхватывается без миграций.
 */
class Store(private val file: File = File(Platform.dataDir, "state.json")) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun load(): AppState {
        val loaded = readState()
        if (loaded != null && loaded.subscriptions.isNotEmpty()) return loaded

        // Пустой или битый state.json не должен «съедать» старые данные.
        val migrated = migrateLegacy()
        if (migrated != null) return migrated

        return loaded ?: AppState()
    }

    private fun readState(): AppState? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(AppState.serializer(), file.readText())
        }.onFailure { Platform.logDebug("store: не удалось прочитать ${file.name}: $it") }
            .getOrNull()
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
        }.onFailure { Platform.logDebug("store: не удалось сохранить состояние: $it") }
    }

    /** Старый формат state-kotlin.txt: режим, выбранный узел, ссылка, затем URI. */
    private fun migrateLegacy(): AppState? {
        val legacy = Platform.legacyStateFile
        if (!legacy.exists()) return null

        val lines = runCatching { legacy.readLines() }.getOrNull() ?: return null
        if (lines.isEmpty()) return null

        val mode = if (lines.getOrNull(0) == "proxy") Mode.PROXY else Mode.TUN
        val selected = lines.getOrNull(1)?.takeIf { it.isNotBlank() }
        val url = lines.getOrNull(2)?.takeIf { it.isNotBlank() && it.contains("://") }
        val raw = lines.drop(3)
            .mapNotNull { UriParser.parse(it) }
            .distinctBy { it.id }
            .map { it.raw }

        Platform.logDebug("store: миграция legacy, узлов=${raw.size}, url=$url")

        val subscription = if (raw.isEmpty() && url == null) {
            null
        } else {
            SubscriptionInfo(
                id = "sub-" + Codec.fnv1a(url ?: raw.joinToString("\n")),
                title = titleOf(url),
                url = url,
                raw = raw,
                updatedAt = System.currentTimeMillis(),
            )
        }

        return AppState(
            subscriptions = listOfNotNull(subscription),
            settings = Settings(mode = mode, selectedNodeId = selected),
        )
    }

    private fun titleOf(url: String?): String {
        if (url == null) return "Моя подписка"
        return runCatching { URI.create(url).host ?: "Подписка" }.getOrDefault("Подписка")
    }
}
