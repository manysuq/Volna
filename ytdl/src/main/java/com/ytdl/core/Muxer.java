package com.ytdl.core;

import java.io.File;
import java.util.Map;

/**
 * Склейка видео с аудио и конвертация звука.
 *
 * На Android реализация — MediaMuxerMuxer (android.media, без внешних бинарников).
 * На JVM — FfmpegMuxer (нужен установленный ffmpeg).
 */
public interface Muxer {

    /** Доступен ли мьюксер в текущем окружении. */
    boolean isAvailable();

    /** Склеивает видео и аудио без перекодирования. */
    void merge(File video, File audio, File output, String container) throws YtDlpException;

    /** Извлекает аудиодорожку в отдельный файл. */
    void extractAudio(File source, File output, String audioFormat) throws YtDlpException;

    /** Записывает теги; реализация может ничего не делать. */
    void writeTags(File media, Map<String, String> tags);
}
