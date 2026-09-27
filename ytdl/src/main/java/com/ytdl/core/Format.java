package com.ytdl.core;

import com.ytdl.json.Json;

import java.util.Locale;
import java.util.Map;

/** Один формат потока из streamingData. */
public final class Format {

    public final int itag;
    public final String url;
    public final String mimeType;
    public final String codecs;
    public final long bitrate;
    public final long contentLength;
    public final int width;
    public final int height;
    public final int fps;
    public final String qualityLabel;
    public final String audioQuality;
    public final boolean hasVideo;
    public final boolean hasAudio;
    public final String audioSampleRate;
    public final int audioChannels;
    public final String container;

    private Format(Map<String, Object> f) {
        Integer itag = Json.intOf(f, "itag");
        this.itag = itag == null ? -1 : itag.intValue();
        this.url = Json.str(f, "url");
        this.mimeType = Json.str(f, "mimeType");
        this.codecs = Json.str(f, "codecs");
        this.qualityLabel = Json.firstStr(f, "qualityLabel", "quality");
        this.audioQuality = Json.str(f, "audioQuality");
        this.bitrate = orZero(Json.longOf(f, "bitrate"));
        this.contentLength = orZero(Json.longOf(f, "contentLength"));
        this.width = orZero(Json.intOf(f, "width"));
        this.height = orZero(Json.intOf(f, "height"));
        this.fps = orZero(Json.intOf(f, "fps"));
        this.audioSampleRate = Json.str(f, "audioSampleRate");
        this.audioChannels = orZero(Json.intOf(f, "audioChannels"));
        String mime = this.mimeType == null ? "" : this.mimeType;
        this.hasVideo = mime.startsWith("video/");
        this.hasAudio = mime.startsWith("audio/");
        int slash = mime.indexOf('/');
        String cont = slash >= 0 ? mime.substring(slash + 1) : "unknown";
        int semi = cont.indexOf(';');
        this.container = (semi >= 0 ? cont.substring(0, semi) : cont).trim();
    }

    /**
     * Разбирает формат. Возвращает null, если поток недоступен для прямой
     * загрузки: например, требует дешифровки подписи (signatureCipher).
     */
    public static Format fromJson(Map<String, Object> f) {
        if (f == null) {
            return null;
        }
        String url = Json.str(f, "url");
        if (url == null || url.isEmpty()) {
            return null;
        }
        return new Format(f);
    }

    public boolean needsDeciphering() {
        return url == null || url.isEmpty();
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v.longValue();
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v.intValue();
    }

    public String kind() {
        return hasVideo && hasAudio ? "video+audio" : hasVideo ? "video" : "audio";
    }

    public String shortQuality() {
        if (hasVideo) {
            return qualityLabel != null ? qualityLabel : (height > 0 ? height + "p" : "video");
        }
        if (audioQuality != null) {
            return audioQuality.replace("AUDIO_QUALITY_", "").toLowerCase(Locale.US);
        }
        return "audio";
    }

    /** Строка для --list-formats. */
    public String describe() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(String.format(Locale.US, "%-6s", Integer.toString(itag)));
        sb.append(String.format(Locale.US, "%-12s", kind()));
        sb.append(String.format(Locale.US, "%-8s", shortQuality()));
        sb.append(String.format(Locale.US, "%-8s", container));
        sb.append(String.format(Locale.US, "%-30s", codecs == null ? "?" : codecs));
        StringBuilder extra = new StringBuilder();
        if (hasVideo) {
            if (width > 0) {
                extra.append(width).append("x").append(height);
            }
            if (fps > 0) {
                extra.append(extra.length() > 0 ? " " : "").append(fps).append("fps");
            }
        } else {
            if (audioSampleRate != null) {
                extra.append(audioSampleRate);
            }
            if (audioChannels > 0) {
                extra.append(extra.length() > 0 ? " " : "").append(audioChannels).append("ch");
            }
        }
        extra.append(String.format(Locale.US, " %dkbps", bitrate / 1000));
        if (contentLength > 0) {
            extra.append(" ").append(FileUtils.humanSize(contentLength));
        }
        sb.append(extra);
        return sb.toString();
    }
}
