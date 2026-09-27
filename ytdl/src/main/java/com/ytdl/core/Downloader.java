package com.ytdl.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Потоковая загрузка файла с докачкой через Range (.part-файл),
 * прогрессом, ограничением скорости и повторами при обрыве.
 */
public final class Downloader {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_ATTEMPTS = 5;
    private static final long PROGRESS_INTERVAL_NS = 250_000_000L;

    /**
     * Перезапрашивает прямой URL потока. Нужен потому, что ссылки googlevideo
     * привязаны к IP: при смене адреса (мобильная сеть, прокси, VPN)
     * старый URL отдаёт 403, и его нужно заменить свежим.
     */
    public interface UrlRefresher {
        /** Новый URL для того же формата; null — обновиться не удалось. */
        String refresh(String staleUrl) throws IOException;
    }

    private final long rateLimit; // байт/сек, 0 — без ограничения
    private volatile boolean cancelled;
    private volatile UrlRefresher urlRefresher;

    public Downloader() {
        this(0);
    }

    public Downloader(long rateLimit) {
        this.rateLimit = rateLimit;
    }

    /** Включает обновление протухших ссылок. */
    public void setUrlRefresher(UrlRefresher refresher) {
        this.urlRefresher = refresher;
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Скачивает url в target. Данные пишутся во временный файл "<name>.part",
     * при обрыве следующая попытка продолжает с места обрыва (Range).
     */
    public File download(String url, File target, String userAgent, ProgressListener listener)
            throws IOException, InterruptedException {
        FileUtils.mkdirs(target.getParentFile());
        File part = new File(target.getParentFile(), target.getName() + ".part");
        IOException lastError = null;
        String currentUrl = url;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            checkCancelled(listener);
            long existing = part.isFile() ? part.length() : 0L;
            try {
                fetch(currentUrl, part, existing, userAgent, listener);
                FileUtils.move(part, target);
                if (listener != null) {
                    listener.onFinish(target);
                }
                return target;
            } catch (StaleUrlException e) {
                // ссылка протухла: берём новую и продолжаем с докачкой
                lastError = e;
                String refreshed = refresh(e.getStaleUrl());
                if (refreshed == null) {
                    if (attempt == MAX_ATTEMPTS) {
                        break;
                    }
                    Thread.sleep(800L * attempt);
                    continue;
                }
                currentUrl = refreshed;
                // докачку не сбрасываем: partial-файл остаётся валидным
            } catch (IOException e) {
                lastError = e;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                Thread.sleep(800L * attempt);
            }
        }
        throw new YtDlpException("Скачивание не удалось за " + MAX_ATTEMPTS + " попыток: "
                + (lastError == null ? "?" : lastError.getMessage()), lastError);
    }

    private void fetch(String url, File part, long offset, String userAgent,
                       ProgressListener listener) throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        // Range обязателен даже с нуля: без него YouTube отдаёт поток
        // с троттлингом ~30 KiB/s вместо нормальных сотен KiB/s
        headers.put("Range", "bytes=" + offset + "-");

        Http.Connection conn = Http.open(url, "GET", headers);
        try {
            int status = conn.status;
            if (status == 403 || status == 410) {
                // URL привязан к IP или истёк: нужен свежий
                throw new StaleUrlException(url);
            }
            if (status != 200 && status != 206) {
                throw new YtDlpException("HTTP " + status + " при скачивании потока");
            }

            long startAt;
            long total;
            String contentRange = conn.header("Content-Range");
            if (status == 206 && contentRange != null) {
                startAt = offset;
                long size = parseTotalFromRange(contentRange);
                total = size > 0 ? size : 0L;
            } else {
                // сервер проигнорировал Range — начинаем с нуля
                startAt = 0L;
                total = conn.headerLong("Content-Length", 0L);
            }

            if (startAt == 0L && listener != null) {
                listener.onStart(total, part.getName());
            }

            long downloaded = startAt;
            long windowStartNs = System.nanoTime();
            long windowBytes = 0L;
            long lastReportNs = 0L;
            byte[] buffer = new byte[BUFFER_SIZE];

            RandomAccessFile out = new RandomAccessFile(part, "rw");
            try {
                out.setLength(startAt);
                out.seek(startAt);
                InputStream in = conn.body();
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    windowBytes += read;

                    if (cancelled) {
                        throw new YtDlpException("Загрузка отменена");
                    }
                    if (rateLimit > 0L) {
                        throttle(windowBytes, windowStartNs);
                    }
                    long nowNs = System.nanoTime();
                    if (listener != null && nowNs - lastReportNs >= PROGRESS_INTERVAL_NS) {
                        double seconds = (nowNs - windowStartNs) / 1_000_000_000.0;
                        double speed = seconds > 0 ? windowBytes / seconds : 0;
                        listener.onProgress(downloaded, total > 0 ? total : downloaded, speed);
                        lastReportNs = nowNs;
                        windowStartNs = nowNs;
                        windowBytes = 0L;
                    }
                }
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (listener != null) {
                listener.onProgress(downloaded, total > 0 ? total : downloaded, 0);
            }
        } finally {
            conn.close();
        }
    }

    /** Замедляет чтение до rateLimit байт/сек. */
    private void throttle(long windowBytes, long windowStartNs) throws InterruptedException {
        if (windowBytes * 10L < rateLimit) {
            return; // окно слишком мало для точного измерения
        }
        long elapsedMs = (System.nanoTime() - windowStartNs) / 1_000_000L;
        long expectedMs = windowBytes * 1000L / rateLimit;
        if (expectedMs > elapsedMs) {
            Thread.sleep(expectedMs - elapsedMs);
        }
    }

    private void checkCancelled(ProgressListener listener) throws YtDlpException {
        if (cancelled) {
            if (listener != null) {
                listener.onError(new YtDlpException("Загрузка отменена"));
            }
            throw new YtDlpException("Загрузка отменена");
        }
    }

    /** Просит у refresher новый URL; null, если обновить нечем. */
    private String refresh(String staleUrl) {
        UrlRefresher refresher = urlRefresher;
        if (refresher == null) {
            return null;
        }
        try {
            return refresher.refresh(staleUrl);
        } catch (IOException e) {
            return null;
        }
    }

    /** Внутренний сигнал: ссылка устарела, нужен новый URL. */
    public static final class StaleUrlException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String staleUrl;

        StaleUrlException(String staleUrl) {
            super("Ссылка на поток устарела (HTTP 403/410)");
            this.staleUrl = staleUrl;
        }

        public String getStaleUrl() {
            return staleUrl;
        }
    }

    /** Из заголовка "bytes 0-99/1234" достаёт полный размер. */
    static long parseTotalFromRange(String contentRange) {
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1L;
        }
        String tail = contentRange.substring(slash + 1).trim();
        if (tail.isEmpty() || "*".equals(tail)) {
            return -1L;
        }
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
