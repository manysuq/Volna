package com.volna.player.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Перестановка вкладок — чистая функция, поэтому проверяется без Android.
 *
 * Важны края: выход за границы (палец увели за край рельса) и пустой список.
 * Без зажима на первом случае список рассыпался бы.
 */
class TabOrderTest {

    private val base = listOf(
        AppTab.Search,
        AppTab.Albums,
        AppTab.Library,
        AppTab.Recommendations,
    )

    @Test
    fun `порядок по умолчанию начинается с поиска`() {
        assertEquals(AppTab.Search, TabOrder.DEFAULT.first())
    }

    @Test
    fun `перемещение вниз меняет местами`() {
        assertEquals(
            listOf(AppTab.Albums, AppTab.Search, AppTab.Library, AppTab.Recommendations),
            TabOrder.move(base, 0, 1),
        )
    }

    @Test
    fun `перемещение вверх ставит элемент на своё место`() {
        // Albums стоял вторым и переносится на первое место, остальные
        // сдвигаются вниз. Это перенос, а не обмен.
        assertEquals(
            listOf(AppTab.Albums, AppTab.Search, AppTab.Library, AppTab.Recommendations),
            TabOrder.move(base, 1, 0),
        )
    }

    @Test
    fun `выход за нижнюю границу зажимается`() {
        // Палец увели за край: Search попадает на последнее место, а не
        // исчезает и не ломает список.
        assertEquals(
            listOf(AppTab.Albums, AppTab.Library, AppTab.Recommendations, AppTab.Search),
            TabOrder.move(base, 0, 99),
        )
    }

    @Test
    fun `выход за верхнюю границу зажимается`() {
        assertEquals(
            listOf(AppTab.Recommendations, AppTab.Search, AppTab.Albums, AppTab.Library),
            TabOrder.move(base, 3, -5),
        )
    }

    @Test
    fun `перемещение в себя же ничего не меняет`() {
        assertEquals(base, TabOrder.move(base, 2, 2))
    }

    @Test
    fun `состав вкладок после перестановок не меняется`() {
        var order = base
        repeat(10) { order = TabOrder.move(order, 0, 2) }
        assertEquals(base.size, order.size)
        assertEquals(base.toSet(), order.toSet())
    }

    @Test
    fun `пустой список не падает`() {
        assertEquals(emptyList<AppTab>(), TabOrder.move(emptyList(), 0, 1))
    }
}
