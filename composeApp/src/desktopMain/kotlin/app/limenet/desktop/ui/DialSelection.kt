package app.limenet.desktop.ui

import app.limenet.ui.dial.DIAL_SEGMENTS

/**
 * Что делает тап по центру колеса.
 *
 * Отдельный тип, чтобы решение можно было проверить тестом без Compose.
 */
internal sealed interface DialAction {
    /** Ничего: идёт подключение/переподключение. */
    data object None : DialAction

    /** Узлов нет вообще - открыть подписки. */
    data object OpenSubscriptions : DialAction

    /** Долька пустая (или её нет) - открыть список серверов. */
    data object OpenList : DialAction

    /** Выбрать узел и подключиться (были отключены). */
    data class SelectAndConnect(val id: String) : DialAction

    /** Выбрать узел, оставаясь подключённым (Controller сам перезапустит ядро). */
    data class Select(val id: String) : DialAction

    /** Узел уже выбран - включить/выключить соединение. */
    data class Connect(val id: String) : DialAction
}

/**
 * Состояние выбора на колесе: порядок долей, активная долька, «пользователь трогал колесо».
 *
 * Класс намеренно без Compose и неизменяемый: он - единственный источник правды о том,
 * какая долька подсвечена и какой узел подключит центр. UI хранит его в `mutableStateOf`,
 * поэтому обработчики читают живое значение, а не замыкание композиции.
 *
 * Центр действует на подсвеченную дольку, если она есть. Если выбранный сервер отсутствует
 * на колесе избранного ([index] == -1), центр действует на выбранный сервер, а не открывает
 * список. Пустой слот с валидным индексом по-прежнему открывает список.
 */
internal data class DialSelection(
    val ordered: List<String?> = emptyList(),
    val index: Int = -1,
    val touched: Boolean = false,
    val segments: Int = DIAL_SEGMENTS,
) {

    /** Есть ли хоть один узел на колесе. */
    val hasNodes: Boolean get() = ordered.any { it != null }

    /** Узел, на который показывает колесо; `null` - долька пустая. */
    val dialId: String? get() = ordered.getOrNull(index)

    /**
     * Новый порядок долей. Позиция активной дольки сохраняется по узлу, а не по номеру:
     * если порядок пересобрался, колесо остаётся на том же сервере.
     */
    fun withOrder(ids: List<String?>): DialSelection {
        val normalized = List(segments) { ids.getOrNull(it) }
        if (normalized == ordered) return this

        val currentId = dialId
        val next = when {
            currentId == null -> -1
            else -> normalized.indexOf(currentId)
        }
        return copy(ordered = normalized, index = next)
    }

    /** Тап по дольке: выбор пользователя. */
    fun withUserSelect(i: Int): DialSelection {
        if (i !in 0 until segments) return this
        if (ordered.getOrNull(i) == null) return this
        if (touched && i == index) return this
        return copy(index = i, touched = true)
    }

    /**
     * Синхронизация с Controller: колесо показывает выбранный узел, если пользователь ещё
     * ничего не выбрал, или если Controller сменил сервер «снаружи» (автопереключение,
     * восстановление состояния).
     */
    fun withSync(selectedId: String?, pendingId: String? = null): DialSelection {
        val target = pendingId ?: selectedId
        val next = when {
            target != null -> ordered.indexOf(target)
            else -> ordered.indexOfFirst { it != null }
        }
        return if (next == index) this else copy(index = next)
    }

    /** Что делает тап по центру. Читает только собственное состояние и живые флаги. */
    fun centerTap(
        selectedId: String?,
        connected: Boolean,
        busy: Boolean,
        anyNodes: Boolean = hasNodes,
        fallbackId: String? = null,
    ): DialAction {
        if (busy) return DialAction.None
        if (!anyNodes) return DialAction.OpenSubscriptions

        // Если выбранный сервер не входит в избранные дольки, индекс равен -1.
        // В этом состоянии центр должен подключать текущий выбор, а не снова открывать список.
        // Но явный тап по существующему пустому слоту остаётся командой открыть список.
        val target = dialId ?: (if (index !in 0 until segments) fallbackId else null)
            ?: return DialAction.OpenList

        return when {
            target != selectedId && !connected -> DialAction.SelectAndConnect(target)
            target != selectedId -> DialAction.Select(target)
            else -> DialAction.Connect(target)
        }
    }
}
