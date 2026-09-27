package com.ytdl.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Достаёт 11-символьный video id из разных ссылок на YouTube:
 * watch?v=, youtu.be/, /shorts/, /embed/, /live/, /v/, m.youtube.com, music.youtube.com.
 */
public final class VideoIdExtractor {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    public static String extract(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Ссылка не задана");
        }
        String s = input.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Ссылка пустая");
        }
        // Голый id
        if (ID.matcher(s).matches()) {
            return s;
        }
        String url = normalize(s);
        String host = host(url);
        String fromQuery = queryParam(url, "v");
        if (isValid(fromQuery)) {
            return fromQuery;
        }
        // youtu.be/<id>, /shorts/<id>, /embed/<id>, /live/<id>, /v/<id>
        String path = path(url);
        for (String seg : path.split("/")) {
            if (isValid(seg)) {
                return seg;
            }
        }
        // music.youtube.com/watch?v=, ссылки с OtherTube-обёрток вида ?url=<ссылка>
        if (fromQuery == null) {
            for (String key : new String[] {"url", "u", "link", "target"}) {
                String nested = queryParam(url, key);
                if (nested != null && nested.contains("youtu")) {
                    return extract(nested);
                }
            }
        }
        throw new IllegalArgumentException("Не удалось извлечь video id из: " + input);
    }

    public static boolean isValid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    private static String normalize(String s) {
        if (!s.contains("://")) {
            s = "https://" + s;
        }
        try {
            URI uri = URI.create(s);
            String scheme = uri.getScheme();
            if (scheme == null) {
                throw new IllegalArgumentException("Некорректная ссылка: " + s);
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректная ссылка: " + s, e);
        }
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String path(String url) {
        try {
            String p = URI.create(url).getPath();
            return p == null ? "" : p;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String queryParam(String url, String name) {
        try {
            String q = URI.create(url).getRawQuery();
            if (q == null) {
                return null;
            }
            for (String pair : q.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (key.equals(name)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }
}
