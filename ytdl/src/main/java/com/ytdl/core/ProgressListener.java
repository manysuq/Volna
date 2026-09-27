package com.ytdl.core;

import java.io.File;

/** Колбэки прогресса. Реализуйте в UI и обновляйте прогресс-бар. */
public interface ProgressListener {

    /** Началась загрузка потока; totalBytes может быть 0, если размер неизвестен. */
    void onStart(long totalBytes, String fileName);

    /** Периодический прогресс; bytesPerSecond равен 0 в финальном вызове. */
    void onProgress(long downloadedBytes, long totalBytes, double bytesPerSecond);

    /** Поток скачан полностью. */
    void onFinish(File file);

    /** Загрузка сорвалась с ошибкой. */
    void onError(Exception error);
}
