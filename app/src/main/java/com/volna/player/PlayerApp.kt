package com.volna.player

import android.app.Application
import com.volna.player.player.PlaybackService

/**
 * Точка входа приложения: держит единственный репозиторий треков
 * и запускает сервис воспроизведения.
 */
class PlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Сервис намеренно не стартует здесь: foreground-сервис без playback
        // система убьёт. Он поднимается при первом нажатии «играть».
    }

    companion object {
        lateinit var instance: PlayerApp
            private set
    }
}
