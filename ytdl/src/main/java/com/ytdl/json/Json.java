package com.ytdl.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный JSON-парсер без внешних зависимостей.
 * Поддерживает объекты, массивы, строки, числа, true/false/null.
 * Мелкие целые числа читаются как Integer, крупные — как Long, остальные — как Double.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (p.pos != text.length()) {
            throw new JsonException("Trailing data at offset " + p.pos);
        }
        return value;
    }

    /** Разбирает текст, который обязан быть JSON-объектом. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected JSON object at root, got " + typeName(v));
        }
        return (Map<String, Object>) v;
    }

    private Object readValue() {
        if (pos >= src.length()) {
            throw new JsonException("Unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw new JsonException("Expected object key at offset " + pos);
            }
            String key = readString();
            skipWs();
            if (peek() != ':') {
                throw new JsonException("Expected ':' at offset " + pos);
            }
            pos++;
            skipWs();
            map.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new JsonException("Expected ',' or '}' at offset " + pos);
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new JsonException("Expected ',' or ']' at offset " + pos);
        }
    }

    private String readString() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new JsonException("Unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new JsonException("Unterminated escape");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw new JsonException("Bad unicode escape");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new JsonException("Bad escape '\\" + e + "'");
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-' || peek() == '+') {
            pos++;
        }
        boolean floating = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                floating = floating || c == '.' || c == 'e' || c == 'E';
                pos++;
            } else {
                break;
            }
        }
        String num = src.substring(start, pos);
        if (num.isEmpty()) {
            throw new JsonException("Unexpected character '" + src.charAt(start) + "' at offset " + start);
        }
        try {
            if (!floating) {
                long l = Long.parseLong(num);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            }
            return Double.parseDouble(num);
        } catch (NumberFormatException e) {
            throw new JsonException("Bad number '" + num + "'");
        }
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new JsonException("Expected '" + literal + "' at offset " + pos);
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new JsonException("Unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    // ---------- навигация по распарсенному дереву ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> parent, String key) {
        if (parent == null) {
            return null;
        }
        Object v = parent.get(key);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> parent, String key) {
        if (parent == null) {
            return null;
        }
        Object v = parent.get(key);
        return (v instanceof List) ? (List<Object>) v : null;
    }

    public static String str(Map<String, Object> parent, String key) {
        if (parent == null) {
            return null;
        }
        Object v = parent.get(key);
        return (v instanceof String) ? (String) v : null;
    }

    public static Integer intOf(Map<String, Object> parent, String key) {
        if (parent == null) {
            return null;
        }
        Object v = parent.get(key);
        if (v instanceof Integer) {
            return (Integer) v;
        }
        if (v instanceof Long) {
            long l = (Long) v;
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (int) l : null;
        }
        if (v instanceof Double) {
            return (int) (double) (Double) v;
        }
        return null;
    }

    public static Long longOf(Map<String, Object> parent, String key) {
        if (parent == null) {
            return null;
        }
        Object v = parent.get(key);
        return (v instanceof Number) ? ((Number) v).longValue() : null;
    }

    /** Возвращает первый непустой результат по списку ключей. */
    public static String firstStr(Map<String, Object> parent, String... keys) {
        for (String k : keys) {
            String v = str(parent, k);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /** Глубокий проход по пути ключей, напр. dig(root, "videoDetails", "author"). */
    public static Map<String, Object> dig(Map<String, Object> root, String... path) {
        Map<String, Object> cur = root;
        for (String key : path) {
            cur = obj(cur, key);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }
}
