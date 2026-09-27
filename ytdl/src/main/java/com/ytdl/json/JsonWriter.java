package com.ytdl.json;

import java.util.Map;

/** Минимальный сериализатор объектов/массивов/строк/чисел в JSON-текст. */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static void write(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<Object>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
