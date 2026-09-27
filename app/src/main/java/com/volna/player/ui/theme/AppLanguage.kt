package com.volna.player.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Язык интерфейса.
 *
 * Приложение двуязычное по сути: строк меньше сотни, поэтому переводим все
 * ключи, а не оставляем английский запасным вариантом. Выбор хранится
 * в настройках и применяется через [AppCompatDelegate], который пересоздаёт
 * активности сам — вручную перезапускать их не нужно.
 */
enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    Russian("ru"),
    Ukrainian("uk"),
    Belarusian("be"),
    French("fr"),
    Spanish("es"),
    Chinese("zh"),
    Arabic("ar");

    companion object {
        private const val PREFS = "ytdl_prefs"
        private const val KEY = "app_language"

        /** Язык, сохранённый пользователем; [System] — не задан. */
        fun load(context: Context): AppLanguage {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return System
            return entries.firstOrNull { it.tag == raw } ?: System
        }

        fun save(context: Context, language: AppLanguage) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, language.tag).apply()
            apply(language)
        }

        /** Применяет язык ко всему приложению. */
        fun apply(language: AppLanguage) {
            val locales = if (language.tag.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }

        /**
         * Прогревает язык до первого показа интерфейса.
         *
         * Без этого первый запуск успевает отрисовать компоненты на старом
         * языке, и переключение выглядит как «нажал, а текст не изменился».
         */
        fun init(context: Context) {
            apply(load(context))
        }
    }
}