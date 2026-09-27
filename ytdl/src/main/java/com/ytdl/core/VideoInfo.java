package com.ytdl.core;

import com.ytdl.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Метаданные видео и список доступных форматов. */
public final class VideoInfo {

    private final String id;
    private final String title;
    private final String uploader;
    private final long durationSeconds;
    private final String thumbnailUrl;
    private final List<Format> formats;
    private final long expiresInSeconds;

    private VideoInfo(String id, String title, String uploader, long durationSeconds,
                      String thumbnailUrl, List<Format> formats, long expiresInSeconds) {
        this.id = id;
        this.title = title;
        this.uploader = uploader;
        this.durationSeconds = durationSeconds;
        this.thumbnailUrl = thumbnailUrl;
        this.formats = formats;
        this.expiresInSeconds = expiresInSeconds;
    }

    public static VideoInfo fromPlayerResponse(String id, Map<String, Object> response) {
        Map<String, Object> details = Json.obj(response, "videoDetails");
        String title = Json.firstStr(details, "title");
        if (title == null) {
            title = id;
        }
        String uploader = Json.firstStr(details, "author", "ownerChannelName");
        if (uploader == null) {
            uploader = "unknown";
        }
        Long duration = Json.longOf(details, "lengthSeconds");

        Map<String, Object> streaming = Json.obj(response, "streamingData");
        List<Format> formats = new ArrayList<Format>();
        collect(Json.arr(streaming, "formats"), formats);
        collect(Json.arr(streaming, "adaptiveFormats"), formats);
        if (formats.isEmpty()) {
            throw new YtDlpException("У видео нет доступных для загрузки форматов");
        }
        Long expires = Json.longOf(streaming, "expiresInSeconds");
        return new VideoInfo(id, title, uploader,
                duration == null ? 0L : duration.longValue(),
                bestThumbnail(details),
                Collections.unmodifiableList(formats),
                expires == null ? 0L : expires.longValue());
    }

    private static void collect(List<Object> raw, List<Format> target) {
        if (raw == null) {
            return;
        }
        for (int i = 0; i < raw.size(); i++) {
            Object o = raw.get(i);
            if (!(o instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Format f = Format.fromJson((Map<String, Object>) o);
            if (f != null) {
                target.add(f);
            }
        }
    }

    /** Самая большая доступная обложка (до 1280px по ширине). */
    private static String bestThumbnail(Map<String, Object> details) {
        // "thumbnail" — объект, "thumbnails" внутри — МАССИВ, а не объект
        List<Object> list = Json.arr(Json.obj(details, "thumbnail"), "thumbnails");
        if (list == null) {
            return null;
        }
        Map<String, Object> best = null;
        int bestWidth = -1;
        int bestHeight = -1;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (!(o instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            Integer w = Json.intOf(m, "width");
            Integer h = Json.intOf(m, "height");
            int width = w == null ? 0 : w.intValue();
            int height = h == null ? 0 : h.intValue();
            boolean better = width > bestWidth || (width == bestWidth && height > bestHeight);
            if (better && width <= 1280) {
                best = m;
                bestWidth = width;
                bestHeight = height;
            }
        }
        String url = Json.str(best, "url");
        if (url != null && url.startsWith("//")) {
            url = "https:" + url;
        }
        return url;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String uploader() {
        return uploader;
    }

    public long durationSeconds() {
        return durationSeconds;
    }

    public String thumbnailUrl() {
        return thumbnailUrl;
    }

    public List<Format> formats() {
        return formats;
    }

    public long expiresInSeconds() {
        return expiresInSeconds;
    }

    public String webPageUrl() {
        return "https://www.youtube.com/watch?v=" + id;
    }

    public String durationText() {
        if (durationSeconds <= 0) {
            return "?";
        }
        long s = durationSeconds;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        }
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Форматы, отсортированные для показа: видео выше, битрейт выше. */
    public List<Format> formatsForDisplay() {
        List<Format> copy = new ArrayList<Format>(formats);
        Collections.sort(copy, new Comparator<Format>() {
            @Override
            public int compare(Format a, Format b) {
                if (a.hasVideo != b.hasVideo) {
                    return a.hasVideo ? -1 : 1;
                }
                int cmp = compareInt(b.height, a.height);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = compareLong(b.bitrate, a.bitrate);
                if (cmp != 0) {
                    return cmp;
                }
                return compareInt(a.itag, b.itag);
            }

            private int compareInt(int x, int y) {
                return x < y ? -1 : (x > y ? 1 : 0);
            }

            private int compareLong(long x, long y) {
                return x < y ? -1 : (x > y ? 1 : 0);
            }
        });
        return copy;
    }
}
