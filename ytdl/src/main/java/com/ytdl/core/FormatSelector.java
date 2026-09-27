package com.ytdl.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Выбор форматов по спецификации вида "best", "bestaudio", "720",
 * "137+140", "bv[height&lt;=1080]+ba". Разбор сделан на циклах,
 * чтобы не тянуть java.util.stream (на Android он с API 24).
 */
public final class FormatSelector {

    private static final Set<String> CONTAINERS = new HashSet<String>(Arrays.asList(
            "mp4", "webm", "m4a", "mp3", "mkv", "mov", "3gp"));

    private static final Pattern HEIGHT_LE = Pattern.compile("height<=(\\d+)");
    private static final Pattern BRACKET = Pattern.compile("\\[([^]]*)\\]");
    private static final Pattern DIGITS = Pattern.compile("^\\d{3,4}$");
    private static final Pattern ITAG = Pattern.compile("^\\d+$");

    /** Что скачиваем: видеопоток (возможно null), аудиопоток (возможно null) и контейнер. */
    public static final class Selection {
        public final Format video;
        public final Format audio;
        public final String container;

        Selection(Format video, Format audio, String container) {
            this.video = video;
            this.audio = audio;
            this.container = container;
        }

        /** true, если это один прогрессивный поток (видео+аудио вместе). */
        public boolean isProgressiveOnly() {
            return video != null && audio == null;
        }

        public String describe() {
            if (video == null) {
                return "только аудио (itag " + audio.itag + ")";
            }
            if (isProgressiveOnly()) {
                return "прогрессивный " + video.shortQuality() + " (itag " + video.itag + ")";
            }
            return video.shortQuality() + " + аудио " + audio.shortQuality()
                    + " -> " + container;
        }
    }

    private FormatSelector() {
    }

    public static Selection select(VideoInfo info, String formatSpec) {
        String spec = formatSpec == null || formatSpec.trim().isEmpty()
                ? "best" : formatSpec.trim().toLowerCase(Locale.US);
        List<Format> all = info.formats();

        // "18" и "1080" оба выглядят числами: сначала пробуем как itag,
        // иначе (такого itag нет) трактуем как высоту — как в yt-dlp
        if (ITAG.matcher(spec).matches()) {
            int number = Integer.parseInt(spec);
            Format exact = findByItag(all, number);
            if (exact == null) {
                Format byHeight = resolvePart(all, spec, true);
                if (byHeight == null) {
                    throw new YtDlpException("Не найден формат " + number
                            + ": нет itag " + number + " и нет потока " + number + "p");
                }
                exact = byHeight;
            }
            if (exact.hasVideo && exact.hasAudio) {
                return new Selection(exact, null, exact.container);
            }
            if (exact.hasVideo) {
                Format a = bestAudio(all);
                return new Selection(exact, a, mergeContainer(exact.container, a.container));
            }
            return new Selection(null, exact, exact.container);
        }

        int plus = spec.indexOf('+');
        if (plus > 0 && plus < spec.length() - 1) {
            String left = spec.substring(0, plus);
            String right = spec.substring(plus + 1);
            // "137+140": явные itag по обе стороны
            Format v = resolveSide(all, left, true);
            Format a = resolveSide(all, right, false);
            if (v == null) {
                // видео не нашлось — отдаём только аудио, если оно есть
                if (a != null) {
                    return new Selection(null, a, a.container);
                }
                Format prog = bestProgressive(all);
                if (prog != null) {
                    return new Selection(prog, null, prog.container);
                }
                throw new YtDlpException("Не найдено ни видео, ни аудио для: " + formatSpec);
            }
            if (a == null) {
                Format prog = bestProgressive(all);
                if (prog != null) {
                    return new Selection(prog, null, prog.container);
                }
                throw new YtDlpException("Аудиопоток не найден для: " + formatSpec);
            }
            return new Selection(v, a, mergeContainer(v.container, a.container));
        }

        Format single = resolvePart(all, spec, true);
        if (single == null) {
            single = resolvePart(all, spec, false);
        }
        if (single == null) {
            throw new YtDlpException("Не удалось выбрать формат: " + formatSpec);
        }
        if (!single.hasVideo) {
            return new Selection(null, single, single.container);
        }
        if (single.hasAudio) {
            return new Selection(single, null, single.container);
        }
        Format audio = bestAudio(all);
        return new Selection(single, audio, mergeContainer(single.container, audio.container));
    }

    /** Одна сторона связки "+": itag, если число и itag существует, иначе спецификация. */
    private static Format resolveSide(List<Format> all, String part, boolean wantVideo) {
        if (ITAG.matcher(part).matches()) {
            Format byItag = findByItag(all, Integer.parseInt(part));
            if (byItag != null) {
                return byItag;
            }
        }
        return resolvePart(all, part, wantVideo);
    }

    /** Разбирает одно «звено» спецификации: best, worst, 1080, [mp4], bv*, hls и т.п. */
    private static Format resolvePart(List<Format> all, String part, boolean wantVideo) {
        String p = part.trim().toLowerCase(Locale.US);
        // явное слово audio/video важнее переданного по умолчанию намерения,
        // иначе "bestaudio" отдал бы видеопоток
        if (p.contains("audio") || p.startsWith("ba")) {
            wantVideo = false;
        } else if (p.contains("video") || p.startsWith("bv")) {
            wantVideo = true;
        }

        if (p.contains("hls") || p.contains("dash") || p.contains("mhtml")) {
            return null; // HLS/DASH-манифесты в базовой версии не поддерживаются
        }

        boolean pickWorst = false;
        if (p.startsWith("worst")) {
            pickWorst = true;
            p = p.substring(5);
        } else if (p.startsWith("best")) {
            p = p.substring(4);
        }
        if (p.startsWith("v") && !p.startsWith("vcodec")) {
            p = p.substring(1);            // bestvideo -> video
        } else if (p.startsWith("a") && !p.startsWith("acodec")) {
            p = p.substring(1);            // bestaudio -> audio
        }
        if (p.startsWith("worst")) {
            pickWorst = true;
            p = p.substring(5);
        }

        Integer exactHeight = null;
        Integer maxHeight = null;
        String container = null;
        String codec = null;

        if (DIGITS.matcher(p).matches()) {
            exactHeight = Integer.valueOf(Integer.parseInt(p));
            p = "";
        }
        Matcher le = HEIGHT_LE.matcher(p);
        if (le.find()) {
            maxHeight = Integer.valueOf(Integer.parseInt(le.group(1)));
        }
        p = le.reset().replaceAll("");

        Matcher brackets = BRACKET.matcher(p);
        while (brackets.find()) {
            String token = brackets.group(1).trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("ext=")) {
                String value = token.substring(4).trim();
                if (!value.isEmpty()) {
                    container = value;
                }
            } else if (CONTAINERS.contains(token)) {
                container = token;
            } else if (token.startsWith("vcodec") || token.startsWith("acodec")) {
                int eq = token.indexOf('=');
                int tilde = token.indexOf('~');
                int cut = eq >= 0 ? eq : tilde;
                if (cut > 0 && cut + 1 < token.length()) {
                    codec = token.substring(cut + 1);
                }
            }
        }
        p = BRACKET.matcher(p).replaceAll("").trim();
        if (p.startsWith("ext=")) {
            String value = p.substring(4).trim();
            if (!value.isEmpty()) {
                container = value;
            }
            p = "";
        }
        if (p.startsWith(".")) {
            p = p.substring(1);
        }
        if (CONTAINERS.contains(p)) {
            container = p;
        } else if (!p.isEmpty() && !p.matches("^[a-z0-9]+$")) {
            return null;
        }

        List<Format> pool = new ArrayList<Format>();
        for (int i = 0; i < all.size(); i++) {
            Format f = all.get(i);
            if (wantVideo ? !f.hasVideo : !f.hasAudio) {
                continue;
            }
            if (exactHeight != null && f.height != exactHeight.intValue()) {
                continue;
            }
            if (maxHeight != null && f.height > maxHeight.intValue()) {
                continue;
            }
            if (container != null && !matchesContainer(f, container)) {
                continue;
            }
            if (codec != null && (f.codecs == null
                    || !f.codecs.toLowerCase(Locale.US).contains(codec))) {
                continue;
            }
            pool.add(f);
        }
        if (pool.isEmpty()) {
            return null;
        }
        Format best = pool.get(0);
        for (int i = 1; i < pool.size(); i++) {
            Format f = pool.get(i);
            int cmp = wantVideo ? compareVideo(f, best) : compareAudio(f, best);
            if (cmp > 0) {
                best = f;
            }
        }
        return pickWorst ? pool.get(0) : best;
    }

    private static int compareVideo(Format a, Format b) {
        if (a.height != b.height) {
            return a.height < b.height ? -1 : 1;
        }
        if (a.fps != b.fps) {
            return a.fps < b.fps ? -1 : 1;
        }
        if (a.bitrate != b.bitrate) {
            return a.bitrate < b.bitrate ? -1 : 1;
        }
        return 0;
    }

    private static int compareAudio(Format a, Format b) {
        if (a.bitrate != b.bitrate) {
            return a.bitrate < b.bitrate ? -1 : 1;
        }
        if (a.audioChannels != b.audioChannels) {
            return a.audioChannels < b.audioChannels ? -1 : 1;
        }
        return 0;
    }

    private static boolean matchesContainer(Format f, String container) {
        if ("m4a".equals(container)) {
            return f.container.startsWith("mp4");
        }
        if ("mkv".equals(container)) {
            return "webm".equals(f.container) || "mp4".equals(f.container);
        }
        return container.equals(f.container);
    }

    public static Format findByItag(List<Format> all, int itag) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).itag == itag) {
                return all.get(i);
            }
        }
        return null;
    }

    /** Лучший совмещённый поток (видео+аудио в одном файле). */
    public static Format bestProgressive(List<Format> all) {
        Format best = null;
        for (int i = 0; i < all.size(); i++) {
            Format f = all.get(i);
            if (!f.hasVideo || !f.hasAudio) {
                continue;
            }
            if (best == null || compareVideo(f, best) > 0) {
                best = f;
            }
        }
        return best;
    }

    /** Лучший аудиопоток по битрейту. */
    public static Format bestAudio(List<Format> all) {
        Format best = null;
        for (int i = 0; i < all.size(); i++) {
            Format f = all.get(i);
            if (f.hasVideo || !f.hasAudio) {
                continue;
            }
            if (best == null || compareAudio(f, best) > 0) {
                best = f;
            }
        }
        if (best == null) {
            throw new YtDlpException("Аудиопоток не найден");
        }
        return best;
    }

    /** Контейнер для склейки: одинаковые -> как есть, разные -> mkv. */
    public static String mergeContainer(String videoContainer, String audioContainer) {
        String v = videoContainer == null ? "mp4" : videoContainer;
        String a = audioContainer == null ? "mp4" : audioContainer;
        if (v.equals(a)) {
            return v;
        }
        return "mkv";
    }
}
