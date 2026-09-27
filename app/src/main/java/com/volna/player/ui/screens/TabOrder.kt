package com.volna.player.ui.screens

import android.content.Context

/**
 * Порядок вкладок в боковой навигации.
 *
 * Хранится пользователем: по умолчанию «Поиск, Альбомы, Медиатека, Похожие» —
 * то, чем пользуются чаще всего, идёт сверху. Перестановка долгий нажатием
 * меняет только этот список, код экранов от неё не зависит.
 */
object TabOrder {

    private const val PREFS = "volna_tabs"
    private const val KEY_ORDER = "order"

    /** Вкладки в порядке по умолчанию. */
    val DEFAULT: List<AppTab> = listOf(
        AppTab.Search,
        AppTab.Albums,
        AppTab.Library,
        AppTab.Recommendations,
    )

    fun load(context: Context): List<AppTab> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null) ?: return DEFAULT
        val byName = DEFAULT.associateBy { it.name }
        val parsed = raw.split(",").mapNotNull { byName[it] }
        // Вкладку, пропавшую из списка (например, после удаления), возвращаем
        // в конец: иначе навигация останется без неё навсегда.
        val missing = DEFAULT.filterNot { it in parsed }
        return parsed + missing
    }

    fun save(context: Context, order: List<AppTab>) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDER, order.joinToString(",") { it.name }).apply()
    }

    /**
     * Чистое перемещение вкладки — ради тестов и для вызова из жеста.
     *
     * Индексы зажимаются, а не проверяются на выход: при перетаскивании за
     * край списка указатель может дать индекс вне диапазона, и без зажима
     * список рассыпался бы.
     */
    fun move(order: List<AppTab>, from: Int, to: Int): List<AppTab> {
        if (order.isEmpty()) return order
        val source = from.coerceIn(0, order.lastIndex)
        val target = to.coerceIn(0, order.lastIndex)
        if (source == target) return order
        val result = order.toMutableList()
        result.add(target, result.removeAt(source))
        return result
    }
}

/**
 * Видна ли боковая панель.
 *
 * Панель занимает 72dp по всей высоте и на узком экране отнимает заметную
 * часть ширины, поэтому её можно убрать и вернуть кнопкой у самого края.
 * Состояние запоминается: панель не должна выскакивать при каждом запуске.
 */
object RailVisibility {

    private const val PREFS = "volna_tabs"
    private const val KEY_VISIBLE = "rail_visible"

    fun load(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VISIBLE, true)

    fun save(context: Context, visible: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VISIBLE, visible).apply()
    }
}
