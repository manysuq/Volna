package com.ytdl.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Тонкая обёртка над HttpURLConnection — единственный HTTP-клиент,
 * доступный на Android без дополнительных зависимостей.
 */
public final class Http {

    public static final int CONNECT_TIMEOUT_MS = 15_000;
    public static final int READ_TIMEOUT_MS = 30_000;

    private static final int MAX_REDIRECTS = 5;

    private Http() {
    }

    /** Открывает соединение с ручной обработкой редиректов. */
    public static Connection open(String url, String method, Map<String, String> headers)
            throws IOException {
        return open(url, method, headers, null);
    }

    /**
     * Открывает соединение, при необходимости отправляя тело запроса.
     * Тело задаётся ДО первого обращения к ответу: иначе соединение
     * уже считается установленным и setDoOutput бросает "Already connected".
     */
    public static Connection open(String url, String method, Map<String, String> headers,
                                  byte[] body) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL target = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) target.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
            }

            if (body != null) {
                // пишем тело до чтения кода ответа
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(body);
                    out.flush();
                } finally {
                    out.close();
                }
            }

            int status = conn.getResponseCode();
            if (isRedirect(status)) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) {
                    throw new YtDlpException("HTTP " + status + " без заголовка Location");
                }
                current = new URL(target, location).toString();
                continue;
            }
            return new Connection(conn, status);
        }
        throw new YtDlpException("Слишком много перенаправлений: " + url);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** POST с телом и чтение ответа как строки. */
    public static String post(String url, String body, Map<String, String> headers)
            throws IOException {
        Connection conn = open(url, "POST", headers, body.getBytes("UTF-8"));
        try {
            String text = conn.readBodyAsString();
            if (conn.status != 200) {
                throw new YtDlpException("HTTP " + conn.status + " от " + url
                        + (text.isEmpty() ? "" : ": " + abbreviate(text, 300)));
            }
            return text;
        } finally {
            conn.close();
        }
    }

    /**
     * Проверяет, отдаёт ли ссылка данные: открывает её Range-запросом,
     * читает первый килобайт и закрывает соединение.
     *
     * Нужна для стриминга: ссылка YouTube привязана к IP, и без проверки
     * плеер получит 403 уже во время воспроизведения.
     *
     * @return код ответа (200 или 206 — ссылка живая), -1 при ошибке сети
     */
    public static int probeRange(String url, String userAgent) {
        return probeRange(url, userAgent, 0L);
    }

    /**
     * Проверяет, отдаёт ли URL данные начиная с [offset].
     *
     * Смещение нужно, потому что ссылка, годная с нуля, может быть негодной
     * дальше: подпись googlevideo привязана к диапазону и клиенту. Плеер
     * первым делом тянет начало трека — и это проходит, — а через несколько
     * секунд доливает следующий кусок уже со смещения и получает 403. Старая
     * проверка читала ровно 1 КБ с нуля, поэтому такую ссылку пропускала:
     * годная на килобайт, но негодная на трек целиком.
     */
    /**
     * Размер куска проверки.
     *
     * Маленький (1 КБ), и это осознанно: проверка обязана быть лёгкой.
     * В 1.0.5 проба ходила куском в 1 МБ — как плеер — и на телефоне за VPN
     * не проходила вообще ни разу за 5 попыток, то есть не играло ничего.
     * Маленькая проба проходит и отделяет мёртвую ссылку от живой; а то, что
     * ссылка держит весь трек, решает уже плеер своими ограниченными кусками.
     */
    public static final long PROBE_CHUNK_BYTES = 1024L;

    public static int probeRange(String url, String userAgent, long offset) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "*/*");
        headers.put("Range", "bytes=" + offset + "-" + (offset + PROBE_CHUNK_BYTES - 1L));
        Connection conn = null;
        try {
            conn = open(url, "GET", headers);
            InputStream in = conn.body();
            if (in != null) {
                // Читаем не весь мегабайт, а его начало: серверу важно, что
                // мы просим ограниченный кусок (иначе 403), а не сколько
                // реально выкачаем.
                in.read(new byte[1024]);
            }
            return conn.status;
        } catch (IOException e) {
            return -1;
        } finally {
            if (conn != null) {
                conn.close();
            }
        }
    }

    /** Скачивает ресурс целиком в память (используется для обложек). */
    public static byte[] getBytes(String url, Map<String, String> headers) throws IOException {
        Connection conn = open(url, "GET", headers);
        try {
            if (conn.status != 200) {
                throw new YtDlpException("HTTP " + conn.status + " при скачивании " + url);
            }
            return Http.readAll(conn.body());
        } finally {
            conn.close();
        }
    }

    public static String readString(InputStream in) throws IOException {
        return new String(readAll(in), "UTF-8");
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        byte[] buf = new byte[16 * 1024];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static String abbreviate(String s, int max) {
        String flat = s.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    /** Соединение с телом ответа. */
    public static final class Connection implements AutoCloseable {
        public final HttpURLConnection raw;
        public final int status;

        Connection(HttpURLConnection raw, int status) {
            this.raw = raw;
            this.status = status;
        }

        public String header(String name) {
            return raw.getHeaderField(name);
        }

        public long headerLong(String name, long fallback) {
            String v = raw.getHeaderField(name);
            if (v == null) {
                return fallback;
            }
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /** Поток тела: основной либо ошибочный, в зависимости от кода ответа. */
        public InputStream body() throws IOException {
            return status >= 400 ? raw.getErrorStream() : raw.getInputStream();
        }

        String readBodyAsString() throws IOException {
            InputStream in = body();
            return in == null ? "" : readString(in);
        }

        @Override
        public void close() {
            raw.disconnect();
        }
    }
}
