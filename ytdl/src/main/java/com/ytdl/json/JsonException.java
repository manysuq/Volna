package com.ytdl.json;

/** Ошибка разбора JSON. */
public class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
