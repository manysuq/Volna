package com.ytdl.core;

/** Ошибка загрузки/разбора, понятная пользователю. */
public class YtDlpException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public YtDlpException(String message) {
        super(message);
    }

    public YtDlpException(String message, Throwable cause) {
        super(message, cause);
    }
}
