package com.ytdl.core;

import java.io.File;

/** Параметры загрузки. Заполняется вручную, без CLI-разбора. */
public final class DownloadRequest {

    public String url;                       // обязательное поле
    public String format = "bestvideo+bestaudio";
    public File outputDir = new File(".");   // куда сохранять
    public String outputTemplate;            // "%(title)s.%(ext)s" или null
    public String audioFormat;               // "mp3"/"m4a"/... -> только звук с конвертацией
    public boolean audioOnly;                // скачать только аудио
    public boolean overwrite;                // перезаписывать существующий файл
    public boolean keepSeparateStreams;       // не склеивать видео и аудио
    public boolean writeThumbnail = true;    // сохранить обложку рядом
    public long rateLimit;                   // байт/сек, 0 — без ограничения
    public String userAgent;

    public DownloadRequest() {
    }

    public DownloadRequest(String url) {
        this.url = url;
    }

    /** Каталог для сохранения. */
    public DownloadRequest outputDir(File dir) {
        this.outputDir = dir;
        return this;
    }

    public DownloadRequest format(String spec) {
        this.format = spec;
        return this;
    }

    public DownloadRequest audioOnly(String audioFormat) {
        this.audioOnly = true;
        this.audioFormat = audioFormat;
        return this;
    }

    public DownloadRequest outputTemplate(String template) {
        this.outputTemplate = template;
        return this;
    }

    public DownloadRequest rateLimit(long bytesPerSecond) {
        this.rateLimit = bytesPerSecond;
        return this;
    }

    public DownloadRequest overwrite(boolean value) {
        this.overwrite = value;
        return this;
    }

    public DownloadRequest keepSeparateStreams(boolean value) {
        this.keepSeparateStreams = value;
        return this;
    }

    public DownloadRequest writeThumbnail(boolean value) {
        this.writeThumbnail = value;
        return this;
    }

    public DownloadRequest userAgent(String ua) {
        this.userAgent = ua;
        return this;
    }

    /** Разбор лимита вида "2M", "500K"; 0 или пусто — без ограничения. */
    public static DownloadRequest parseRate(String value, DownloadRequest target) {
        if (value == null || value.trim().isEmpty() || "0".equals(value.trim())) {
            return target.rateLimit(0L);
        }
        String v = value.trim().toUpperCase(java.util.Locale.US);
        long multiplier = 1L;
        char last = v.charAt(v.length() - 1);
        if (last == 'K') {
            multiplier = 1024L;
        } else if (last == 'M') {
            multiplier = 1024L * 1024L;
        } else if (last == 'G') {
            multiplier = 1024L * 1024L * 1024L;
        }
        if (multiplier > 1L) {
            v = v.substring(0, v.length() - 1);
        }
        try {
            return target.rateLimit((long) (Double.parseDouble(v) * multiplier));
        } catch (NumberFormatException e) {
            throw new YtDlpException("Не удалось разобрать лимит скорости: " + value);
        }
    }
}
