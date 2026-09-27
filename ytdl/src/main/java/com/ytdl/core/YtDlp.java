package com.ytdl.core;

import com.ytdl.json.JsonWriter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Точка входа библиотеки: получить метаданные, выбрать формат, скачать,
 * склеить и сохранить.
 *
 * Пример:
 * <pre>
 * YtDlp ytdlp = new YtDlp(new MediaMuxerMuxer());
 * VideoInfo info = ytdlp.getInfo("https://youtu.be/...");
 * File file = ytdlp.download(new DownloadRequest(url).outputDir(dir), listener);
 * </pre>
 *
 * Не блокируйте поток интерфейса: вызывайте из Executor/WorkManager.
 */
public final class YtDlp {

    public static final String VERSION = "0.3.0";
    private static final int MAX_URL_ATTEMPTS = 5;
    private static final String DEFAULT_USER_AGENT =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip";

    private final InnerTubeClient client;
    private final Muxer muxer;
    private volatile Downloader activeDownloader;
    private volatile String lastVideoId;

    public YtDlp() {
        this(new MediaMuxerMuxer());
    }

    public YtDlp(Muxer muxer) {
        this.client = new InnerTubeClient();
        this.muxer = muxer;
    }

    /** Метаданные и список форматов без скачивания. */
    public VideoInfo getInfo(String url) throws IOException {
        String videoId = VideoIdExtractor.extract(url);
        Map<String, Object> response = client.player(videoId);
        lastVideoId = videoId;
        return VideoInfo.fromPlayerResponse(videoId, response);
    }

    /**
     * Проверенная ссылка на аудиопоток для стриминга.
     *
     * Ссылка YouTube привязана к IP клиента (параметр "ip="), поэтому при
     * смене адреса (VPN, прокси, мобильная сеть) старая ссылка отдаёт 403.
     * Метод проверяет ссылку тем же способом, каким её будет читать плеер,
     * и при неудаче берёт новую.
     *
     * @return рабочая ссылка или null
     */
    /**
     * С какого смещения проверять, что ссылка держит поток целиком.
     *
     * 256 КБ — это несколько секунд звука. Именно столько успевает проиграть
     * плеер, прежде чем у него кончится первый кусок и он пойдёт за следующим:
     * столько и держала ссылка до появления 403.
     */
    private static final long PROBE_OFFSET_BYTES = 256L * 1024L;

    public String getVerifiedStreamUrl(DownloadRequest request) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_URL_ATTEMPTS; attempt++) {
            List<String> urls = getStreamUrls(request);
            if (urls.isEmpty()) {
                throw new YtDlpException("Аудиопоток не найден");
            }
            String url = urls.get(0);
            String ua = userAgentOf(request);
            int status = Http.probeRange(url, ua);
            if (status != 200 && status != 206) {
                lastError = new IOException("Ссылка не работает с нуля, HTTP " + status);
                continue;
            }
            // Годная с нуля ещё не значит годная целиком: плеер через пару
            // секунд пойдёт за следующим куском уже со смещения. Проверяем и
            // его, иначе такая ссылка доходит до пользователя и обрывается.
            int far = Http.probeRange(url, ua, PROBE_OFFSET_BYTES);
            if (far != 200 && far != 206) {
                lastError = new IOException(
                        "Ссылка обрывается со смещения " + PROBE_OFFSET_BYTES
                                + " байт, HTTP " + far);
                continue;
            }
            return url;
        }
        throw new YtDlpException(
                "Не удалось получить рабочую ссылку на поток за " + MAX_URL_ATTEMPTS + " попыток",
                lastError);
    }

    private String userAgentOf(DownloadRequest request) {
        return request.userAgent != null ? request.userAgent : DEFAULT_USER_AGENT;
    }

    /** Прямые URL выбранных потоков, если нужно отдать их системному загрузчику. */
    public List<String> getStreamUrls(DownloadRequest request) throws IOException {
        VideoInfo info = getInfo(request.url);
        FormatSelector.Selection selection = selectFor(request, info);
        List<String> urls = new ArrayList<String>();
        if (selection.video != null) {
            urls.add(selection.video.url);
        }
        if (selection.audio != null) {
            urls.add(selection.audio.url);
        }
        return urls;
    }

    /** Скачивает видео и возвращает итоговый файл. */
    public File download(DownloadRequest request) throws IOException {
        return download(request, null);
    }

    public File download(DownloadRequest request, ProgressListener listener)
            throws IOException {
        if (request.url == null || request.url.trim().isEmpty()) {
            throw new YtDlpException("Не задан URL");
        }
        VideoInfo info = getInfo(request.url);
        final FormatSelector.Selection selection = selectFor(request, info);

        if (!selection.isProgressiveOnly() && request.keepSeparateStreams
                && !muxer.isAvailable()) {
            throw new YtDlpException("Нужен m��ксер для склейки потоков");
        }

        File target = resolveTarget(request, info, selection);
        FileUtils.mkdirs(target.getParentFile());
        String userAgent = request.userAgent != null ? request.userAgent : DEFAULT_USER_AGENT;
        Downloader downloader = new Downloader(request.rateLimit);
        activeDownloader = downloader;
        // ссылки googlevideo привязаны к IP: при 403 перезапрашиваем player
        downloader.setUrlRefresher(new Downloader.UrlRefresher() {
            @Override
            public String refresh(String staleUrl) {
                return refreshUrl(staleUrl, selection);
            }
        });

        File videoPart = null;
        File audioPart = null;
        try {
            if (selection.video != null && selection.audio != null) {
                // без ".part" на конце: Downloader сам добавит суффикс при докачке
                videoPart = new File(target.getParentFile(),
                        FileUtils.stem(target.getName()) + ".video." + selection.video.container);
                audioPart = new File(target.getParentFile(),
                        FileUtils.stem(target.getName()) + ".audio." + selection.audio.container);

                fetch(downloader, selection.video.url, videoPart, userAgent, listener);
                fetch(downloader, selection.audio.url, audioPart, userAgent, listener);

                if (request.keepSeparateStreams) {
                    File separateVideo = new File(target.getParentFile(),
                            FileUtils.stem(target.getName()) + ".video." + selection.video.container);
                    File separateAudio = new File(target.getParentFile(),
                            FileUtils.stem(target.getName()) + ".audio." + selection.audio.container);
                    FileUtils.move(videoPart, separateVideo);
                    FileUtils.move(audioPart, separateAudio);
                    videoPart = null;
                    audioPart = null;
                    saveThumbnail(request, info, separateVideo);
                    return separateVideo;
                }

                if (!muxer.isAvailable()) {
                    throw new YtDlpException("Мьюксер недоступен: нельзя склеить видео и аудио");
                }
                muxer.merge(videoPart, audioPart, target, selection.container);
            } else {
                Format only = selection.video != null ? selection.video : selection.audio;
                if (request.audioFormat != null) {
                    // качаем во временное имя: итоговое расширение ещё не наше
                    File raw = new File(target.getParentFile(),
                            FileUtils.stem(target.getName()) + ".source." + only.container);
                    fetch(downloader, only.url, raw, userAgent, listener);
                    File result = new File(target.getParentFile(),
                            FileUtils.stem(target.getName()) + "." + request.audioFormat);
                    muxer.extractAudio(raw, result, request.audioFormat);
                    FileUtils.deleteQuietly(raw);
                    writeTags(result, info);
                    saveThumbnail(request, info, result);
                    return result;
                }
                fetch(downloader, only.url, target, userAgent, listener);
            }

            File result = target;
            if (request.audioFormat != null) {
                result = new File(target.getParentFile(),
                        FileUtils.stem(target.getName()) + "." + request.audioFormat);
                muxer.extractAudio(target, result, request.audioFormat);
                FileUtils.deleteQuietly(target);
                target = null;
            }

            writeTags(result, info);
            saveThumbnail(request, info, result);
            return result;
        } finally {
            activeDownloader = null;
            if (videoPart != null) {
                FileUtils.deleteQuietly(videoPart);
            }
            if (audioPart != null) {
                FileUtils.deleteQuietly(audioPart);
            }
        }
    }

    /** Прерывает текущую загрузку (поток, вызвавший download, увидит YtDlpException). */
    public void cancel() {
        Downloader downloader = activeDownloader;
        if (downloader != null) {
            downloader.cancel();
        }
    }

    private FormatSelector.Selection selectFor(DownloadRequest request, VideoInfo info) {
        String spec = request.audioOnly
                ? "bestaudio" : (request.format == null ? "bestvideo+bestaudio" : request.format);
        return FormatSelector.select(info, spec);
    }

    private File resolveTarget(DownloadRequest request, VideoInfo info,
                               FormatSelector.Selection selection) {
        String extension;
        if (request.audioFormat != null) {
            extension = request.audioFormat;
        } else if (selection.container != null && !selection.container.isEmpty()) {
            extension = selection.container;
        } else {
            extension = "mp4";
        }
        String template = request.outputTemplate != null
                ? request.outputTemplate : "%(title)s.%(ext)s";
        String base = template
                .replace("%(title)s", FileUtils.sanitizeName(info.title()))
                .replace("%(id)s", info.id())
                .replace("%(uploader)s", FileUtils.sanitizeName(info.uploader()))
                .replace("%(ext)s", extension);
        String name = FileUtils.sanitizeName(base);
        if (FileUtils.extension(name).isEmpty()) {
            name = name + "." + extension;   // шаблон без %(ext)s
        }
        File dir = request.outputDir != null ? request.outputDir : new File(".");
        if (request.overwrite) {
            return new File(dir, name);
        }
        // уникальное имя: "Название (1).mp4"
        return FileUtils.uniqueFile(dir, FileUtils.stem(name), FileUtils.extension(name));
    }

    /** Обёртка: прерывание из UI превращаем в YtDlpException. */
    private static void fetch(Downloader downloader, String url, File target,
                              String userAgent, ProgressListener listener) {
        try {
            downloader.download(url, target, userAgent, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YtDlpException("Загрузка прервана", e);
        } catch (IOException e) {
            throw new YtDlpException("Скачивание не удалось: " + e.getMessage(), e);
        }
    }

    private void writeTags(File media, VideoInfo info) {
        if (media == null) {
            return;
        }
        Map<String, String> tags = new LinkedHashMap<String, String>();
        tags.put("title", info.title());
        tags.put("artist", info.uploader());
        tags.put("album_artist", info.uploader());
        tags.put("comment", info.webPageUrl());
        tags.put("genre", "YouTube");
        try {
            muxer.writeTags(media, tags);
        } catch (RuntimeException ignored) {
            // теги необязательны
        }
    }

    private void saveThumbnail(DownloadRequest request, VideoInfo info, File target) {
        if (!request.writeThumbnail || info.thumbnailUrl() == null || target == null) {
            return;
        }
        // расширение берём из URL: ANDROID-клиент отдаёт webp, а не jpg
        String coverExtension = imageExtension(info.thumbnailUrl());
        File cover = new File(target.getParentFile(),
                FileUtils.stem(target.getName()) + "." + coverExtension);
        if (cover.isFile()) {
            return;
        }
        try {
            byte[] bytes = Http.getBytes(info.thumbnailUrl(), null);
            java.io.FileOutputStream out = new java.io.FileOutputStream(cover);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // обложка необязательна
        }
    }

    /**
     * Перезапрашивает player-ответ и ищет тот же формат (itag) с новым URL.
     * Нужно, когда ссылка протухла: у googlevideo в URL зашит IP клиента,
     * поэтому при смене адреса (сотовая сеть, VPN, прокси) приходит 403.
     */
    private String refreshUrl(String staleUrl, FormatSelector.Selection selection) {
        try {
            VideoInfo fresh = getInfo(staleVideoId(staleUrl, selection));
            if (selection.video != null) {
                Format f = FormatSelector.findByItag(fresh.formats(), selection.video.itag);
                if (f != null) {
                    return f.url;
                }
            }
            if (selection.audio != null) {
                Format f = FormatSelector.findByItag(fresh.formats(), selection.audio.itag);
                if (f != null) {
                    return f.url;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** Video id для перезапроса: из старого URL или из текущей сессии. */
    private String staleVideoId(String staleUrl, FormatSelector.Selection selection) {
        if (lastVideoId != null) {
            return lastVideoId;
        }
        throw new YtDlpException("Невозможно обновить ссылку: неизвестен video id");
    }

    /** Расширение картинки из URL; по умолчанию jpg. */
    private static String imageExtension(String url) {
        String ext = FileUtils.extension(url == null ? "" : url);
        if (ext.isEmpty() || ext.length() > 4) {
            return "jpg";
        }
        return ext;
    }

    /** Метаданные в JSON (аналог --dump-json). */
    public String toJson(VideoInfo info) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("id", info.id());
        root.put("title", info.title());
        root.put("uploader", info.uploader());
        root.put("duration", info.durationSeconds());
        root.put("webpage_url", info.webPageUrl());
        root.put("thumbnail", info.thumbnailUrl());

        List<Object> formats = new ArrayList<Object>();
        List<Format> sorted = info.formatsForDisplay();
        for (int i = 0; i < sorted.size(); i++) {
            Format f = sorted.get(i);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("format_id", Integer.toString(f.itag));
            item.put("ext", f.container);
            item.put("height", f.height);
            item.put("fps", f.fps);
            item.put("vcodec", f.hasVideo ? f.codecs : "none");
            item.put("acodec", f.hasAudio ? f.codecs : "none");
            item.put("abr", f.bitrate);
            item.put("filesize", f.contentLength);
            item.put("url", f.url);
            formats.add(item);
        }
        root.put("formats", formats);
        return JsonWriter.write(root);
    }

    /** Список форматов таблицей (аналог -F). */
    public String formatList(VideoInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US, "%-6s %-12s %-8s %-8s %-30s %s%n",
                "itag", "kind", "quality", "ext", "codecs", "info"));
        List<Format> sorted = info.formatsForDisplay();
        for (int i = 0; i < sorted.size(); i++) {
            sb.append(sorted.get(i).describe()).append('\n');
        }
        return sb.toString();
    }
}
