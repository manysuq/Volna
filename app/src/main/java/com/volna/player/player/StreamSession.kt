package com.volna.player.player

/**
 * Состояние ссылки на текущий поток.
 *
 * Объект общий для источника данных и ViewModel: источник читает по нему,
 * сколько уже взято, а ViewModel добывает свежую ссылку, когда источник её
 * попросил.
 */
internal class StreamSession(
    /** Свежая ссылка. Вызывается из потока загрузки, поэтому обязана быть блокирующей. */
    private val refreshBlocking: (() -> String?)?,
) {

    /** Текущая ссылка. */
    @Volatile
    var url: String? = null
        private set

    /** Сколько байт уже взято с текущей ссылки. */
    private var consumed: Long = 0

    /** Ссылка уже отдала отказ — обновляем при следующем открытии. */
    private var forced: Boolean = false

    /**
     * Сколько свежих ссылок подряд ещё не проверены успешным открытием.
     *
     * Нужно, чтобы не кружи: источник при каждом открытии брал новую ссылку,
     * и если та тоже не открывалась — брал ещё. В отчёте это выглядело как
     * шесть обновлений за 16 секунд: поток выедал лимит запросов к YouTube,
     * новые ссылки отдавали 403 всё быстрее, и в конце концов ошибка доходила
     * до плеера, а трек начинался заново.
     *
     * Поэтому свежая ссылка допускается только одна за раз, и снимается
     * запрет лишь удачным открытием. Не открылась — отдаём ошибку наружу, и
     * ею занимается переподключение с растущей паузой, а не новые запросы.
     */
    private var unverifiedFresh: Int = 0

    /** Принимает уже полученную ссылку, чтобы первый open() её использовал. */
    fun adopt(url: String) {
        this.url = url
        consumed = 0
        forced = false
        unverifiedFresh = 0
    }

    /** Можно ли сейчас брать ещё одну ссылку. */
    fun canRefresh(): Boolean = unverifiedFresh < MAX_UNVERIFIED_FRESH

    /** Обновляем ссылку, если отработан её объём или предыдущая не открылась. */
    fun refreshIfNeeded() {
        if (!canRefresh()) return
        if (!StreamRotation.shouldRotate(consumed, forced)) return
        refreshNow()
    }

    /**
     * Обновляет ссылку принудительно.
     *
     * Счётчик увеличивается сразу при выдаче ссылки: она ещё не проверена, и
     * пока не откроется, следующую брать нельзя.
     */
    fun refreshNow() {
        if (!canRefresh()) return
        val fresh = refreshBlocking?.invoke()
        if (!fresh.isNullOrBlank()) {
            url = fresh
            consumed = 0
            forced = false
            unverifiedFresh++
        }
    }

    /** Поток открылся: начинаем отсчёт заново. */
    fun onOpened(url: String) {
        if (this.url != url) {
            this.url = url
            consumed = 0
        }
        unverifiedFresh = 0
    }

    /** Поток с этого адреса не открылся. */
    fun onFailed(url: String) {
        if (this.url == url) forced = true
    }

    /** Пришло столько-то байт. */
    fun onRead(bytes: Long) {
        consumed += bytes
    }

    private companion object {
        /**
         * Сколько свежих ссылок можно взять подряд, не дождавшись успеха.
         *
         * Одна: источник и так пробует текущую и одну свежую, а если и та не
         * открылась, дело не в ссылке — пора отдать ошибку и подождать.
         */
        const val MAX_UNVERIFIED_FRESH = 1
    }
}
