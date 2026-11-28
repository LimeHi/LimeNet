package app.limenet.desktop.ui

import app.limenet.ui.dial.DIAL_SEGMENTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Сценарии выбора дольки на главном экране. Проверяем ровно то, на что жалуется
 * пользователь: выбрал соседнюю дольку, нажал центр - подключился именно он.
 */
class DialSelectionTest {

    private fun selection(vararg ids: String?): DialSelection =
        DialSelection(segments = DIAL_SEGMENTS).withOrder(List(DIAL_SEGMENTS) { ids.getOrNull(it) })

    private val germany = "node-de"
    private val netherlands = "node-nl"
    private val poland = "node-pl"

    @Test
    fun tapNeighbourThenCenterSelectsNeighbour_whenDisconnected() {
        var dial = selection(germany, netherlands, poland).withSync(selectedId = germany)

        dial = dial.withUserSelect(1)
        assertEquals(
            DialAction.SelectAndConnect(netherlands),
            dial.centerTap(germany, connected = false, busy = false),
        )
        assertEquals(netherlands, dial.dialId)
    }

    @Test
    fun tapNeighbourThenCenterSwitches_whenConnected() {
        var dial = selection(germany, netherlands, poland).withSync(selectedId = germany)

        dial = dial.withUserSelect(1)
        assertEquals(DialAction.Select(netherlands), dial.centerTap(germany, connected = true, busy = false))
        assertEquals(netherlands, dial.dialId)
    }

    @Test
    fun tapThenCenterImmediately_usesTappedSlot() {
        // Пользователь тыкает дольку и сразу центр - до того, как Controller что-то записал.
        var dial = selection(germany, netherlands, poland).withSync(selectedId = germany)

        dial = dial.withUserSelect(1)
        assertEquals(
            DialAction.SelectAndConnect(netherlands),
            dial.centerTap(germany, connected = false, busy = false),
        )
    }

    @Test
    fun orderChangeMidSelection_keepsChosenNode() {
        var dial = selection(germany, netherlands, poland).withSync(selectedId = germany)
        dial = dial.withUserSelect(1) // Нидерланды

        // Пинги пересчитались, порядок долей пересобрался.
        dial = dial.withOrder(listOf(poland, germany, netherlands))

        assertEquals(netherlands, dial.dialId, "колесо должно остаться на выбранном сервере")
        assertEquals(
            DialAction.SelectAndConnect(netherlands),
            dial.centerTap(germany, connected = false, busy = false),
        )
    }

    @Test
    fun externalSelectedChange_movesWheel() {
        var dial = selection(germany, netherlands, poland).withSync(selectedId = germany)
        assertEquals(germany, dial.dialId)

        // Автопереключение в Controller.
        dial = dial.withSync(selectedId = poland)
        assertEquals(poland, dial.dialId)
        assertEquals(DialAction.Connect(poland), dial.centerTap(poland, connected = true, busy = false))
    }

    @Test
    fun emptySlot_doesNotFallBackToSelected() {
        // Долька, на которую показывает колесо, пустая (порядок пересобрался и долька пропала).
        // Центр не должен молча подключить «текущий выбранный» сервер - это и был откат.
        var dial = selection(germany, netherlands).withSync(selectedId = germany)
        dial = dial.withUserSelect(1)

        dial = dial.withOrder(listOf(germany, null, null))

        assertEquals(null, dial.dialId)
        assertEquals(DialAction.OpenList, dial.centerTap(germany, connected = false, busy = false))
    }

    @Test
    fun busyCenterDoesNothing() {
        var dial = selection(germany, netherlands).withSync(selectedId = germany)
        dial = dial.withUserSelect(1)
        assertEquals(DialAction.None, dial.centerTap(germany, connected = false, busy = true))
    }

    @Test
    fun emptyWheelOpensSubscriptions() {
        val dial = selection()
        assertEquals(
            DialAction.OpenSubscriptions,
            dial.centerTap(null, connected = false, busy = false, anyNodes = false),
        )
        assertFalse(dial.hasNodes)
        assertTrue(dial.ordered.all { it == null })
    }

    @Test
    fun wheelNotReadyButNodesExist_opensList() {
        // Без fallback wheel действительно не знает, какой сервер подключать.
        val dial = selection()
        assertEquals(
            DialAction.OpenList,
            dial.centerTap(null, connected = false, busy = false, anyNodes = true),
        )
    }

    @Test
    fun wheelNotReadyConnectsSelectedServerInsteadOfOpeningList() {
        val dial = selection()
        assertEquals(
            DialAction.Connect(germany),
            dial.centerTap(
                selectedId = germany,
                connected = false,
                busy = false,
                anyNodes = true,
                fallbackId = germany,
            ),
        )
    }

    @Test
    fun wheelNotReadySelectsAndConnectsFirstAvailableServer() {
        val dial = selection()
        assertEquals(
            DialAction.SelectAndConnect(netherlands),
            dial.centerTap(
                selectedId = null,
                connected = false,
                busy = false,
                anyNodes = true,
                fallbackId = netherlands,
            ),
        )
    }

    @Test
    fun selectedServerNotInFavorites_connectsInsteadOfOpeningList() {
        // Пользователь выбрал сервер из полного списка, которого нет среди долек избранного.
        val dial = selection(germany, netherlands).withSync(selectedId = poland)
        assertEquals(-1, dial.index)
        assertEquals(
            DialAction.Connect(poland),
            dial.centerTap(
                selectedId = poland,
                connected = false,
                busy = false,
                anyNodes = true,
                fallbackId = poland,
            ),
        )
    }
}
