package com.ytdl.core;

import com.ytdl.json.Json;
import com.ytdl.json.JsonException;
import com.ytdl.json.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиент InnerTube API (https://www.youtube.com/youtubei/v1/player).
 *
 * Запрашивает метаданные и список форматов. Перебирает несколько клиентов
 * (ANDROID, ANDROID_VR, IOS) — они отдают прямые URL без шифрования подписи,
 * поэтому для базового сценария дешифровка не требуется.
 */
public final class InnerTubeClient {

    private static final String ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

    /** Описание одного InnerTube-клиента. */
    public static final class ClientProfile {
        public final String name;
        /**
         * Числовой идентификатор клиента во InnerTube API.
         *
         * Именно число, а не имя: в X-Youtube-Client-Name и в clientName тела
         * запроса YouTube ждёт именно его. Строка вместо числа означает, что мы
         * называемся клиентом не так, как нас знает InnerTube, и в ответ
         * приходят форматы, которые сам же YouTube затем не признаёт на CDN —
         * ссылка проходит нашу проверку и умирает на стриме с 403.
         */
        public final int id;
        public final String version;
        public final String userAgent;
        public final Map<String, Object> extra;

        public ClientProfile(int id, String name, String version, String userAgent,
                             Map<String, Object> extra) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.userAgent = userAgent;
            this.extra = extra;
        }
    }

    public static List<ClientProfile> defaultProfiles() {
        List<ClientProfile> list = new ArrayList<ClientProfile>();

        Map<String, Object> androidExtra = new LinkedHashMap<String, Object>();
        androidExtra.put("androidSdkVersion", Integer.valueOf(34));
        list.add(new ClientProfile(3, "ANDROID", "20.10.38",
                "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip", androidExtra));

        Map<String, Object> vrExtra = new LinkedHashMap<String, Object>();
        vrExtra.put("androidSdkVersion", Integer.valueOf(32));
        list.add(new ClientProfile(28, "ANDROID_VR", "1.60.19",
                "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12) gzip",
                vrExtra));

        Map<String, Object> iosExtra = new LinkedHashMap<String, Object>();
        iosExtra.put("deviceMake", "Apple");
        iosExtra.put("deviceModel", "iPhone16,2");
        iosExtra.put("osName", "iPhone");
        iosExtra.put("osVersion", "18.3.1.22D72");
        list.add(new ClientProfile(5, "IOS", "20.10.4",
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)",
                iosExtra));

        return Collections.unmodifiableList(list);
    }

    private final List<ClientProfile> profiles;

    public InnerTubeClient() {
        this(defaultProfiles());
    }

    public InnerTubeClient(List<ClientProfile> profiles) {
        this.profiles = profiles;
    }

    /**
     * Возвращает player-response для видео. Клиенты перебираются по очереди,
     * пока один не отдаст играбельный ответ с форматами.
     */
    public Map<String, Object> player(String videoId) throws IOException {
        String lastReason = "неизвестная причина";
        for (int i = 0; i < profiles.size(); i++) {
            ClientProfile profile = profiles.get(i);
            Map<String, Object> response;
            try {
                response = fetch(videoId, profile);
            } catch (IOException e) {
                lastReason = e.getMessage();
                continue;
            }
            if (isPlayable(response)) {
                // Запоминаем, кто именно ответил. Подпись ссылки на поток
                // привязана к этому клиенту, и открывать её нужно его же
                // User-Agent'ом. Ссылка от ANDROID_VR, открытая как ANDROID,
                // даёт 403 — и потерять клиента здесь означает гадать,
                // откуда взялся отказ.
                lastProfile = profile;
                return response;
            }
            lastReason = profile.name + ": " + reasonOf(response);
        }
        throw new YtDlpException("Не удалось получить информацию о видео: " + lastReason);
    }

    private ClientProfile lastProfile;

    /** Клиент, чей ответ оказался последним принятым. */
    public ClientProfile lastProfile() {
        return lastProfile;
    }

    private Map<String, Object> fetch(String videoId, ClientProfile profile) throws IOException {
        Map<String, Object> client = new LinkedHashMap<String, Object>();
        client.put("clientName", Integer.valueOf(profile.id));
        client.put("clientVersion", profile.version);
        client.put("hl", "en");
        client.put("gl", "US");
        client.putAll(profile.extra);

        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("client", client);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("videoId", videoId);
        body.put("context", context);
        body.put("contentCheckOk", Boolean.TRUE);
        body.put("racyCheckOk", Boolean.TRUE);

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", profile.userAgent);
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Origin", "https://www.youtube.com");
        headers.put("X-Youtube-Client-Name", String.valueOf(profile.id));
        headers.put("X-Youtube-Client-Version", profile.version);

        String text = Http.post(ENDPOINT, JsonWriter.write(body), headers);
        try {
            return Json.parseObject(text);
        } catch (JsonException e) {
            throw new YtDlpException("Некорректный JSON от InnerTube: " + e.getMessage());
        }
    }

    static boolean isPlayable(Map<String, Object> response) {
        String status = Json.str(Json.obj(response, "playabilityStatus"), "status");
        if (!"OK".equals(status)) {
            return false;
        }
        Map<String, Object> streaming = Json.obj(response, "streamingData");
        List<Object> progressive = Json.arr(streaming, "formats");
        List<Object> adaptive = Json.arr(streaming, "adaptiveFormats");
        return (progressive != null && !progressive.isEmpty())
                || (adaptive != null && !adaptive.isEmpty());
    }

    static String reasonOf(Map<String, Object> response) {
        Map<String, Object> status = Json.obj(response, "playabilityStatus");
        String reason = Json.firstStr(status, "reason", "subreason");
        return reason != null ? reason : "неизвестная причина";
    }
}
