package com.ytdl.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Имена файлов и размеры — на java.io.File, потому что java.nio.file с API 26. */
public final class FileUtils {

    private static final String ILLEGAL = "/\\:*?\"<>|\t\n\r ";

    private FileUtils() {
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, i == 0 ? "%.0f %s" : "%.2f %s", v, units[i]);
    }

    /** Приводит строку к безопасному имени файла. */
    public static String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "video";
        }
        String trimmed = name.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 32 || ILLEGAL.indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = trimEdges(sb.toString());
        if (out.isEmpty()) {
            out = "video";
        }
        return out.length() > 120 ? out.substring(0, 120) : out;
    }

    private static String trimEdges(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '_' || s.charAt(start) == '.')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '_' || s.charAt(end - 1) == '.')) {
            end--;
        }
        return s.substring(start, end);
    }

    /** Имя файла без расширения. */
    public static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Расширение без точки, в нижнем регистре. */
    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    /** Файл, не перезаписывающий существующий: "name (1).mp4". */
    public static File uniqueFile(File dir, String baseName, String extension) {
        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        File candidate = new File(dir, baseName + "." + ext);
        int i = 1;
        while (candidate.exists()) {
            candidate = new File(dir, baseName + " (" + i + ")." + ext);
            i++;
        }
        return candidate;
    }

    public static void mkdirs(File dir) {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new YtDlpException("Не удалось создать каталог: " + dir.getAbsolutePath());
        }
    }

    public static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    /** Перемещение с перезаписью; копирование, если rename невозможен (разные ФС). */
    public static void move(File from, File to) {
        mkdirs(to.getParentFile());
        if (from.renameTo(to)) {
            return;
        }
        copy(from, to);
        deleteQuietly(from);
    }

    public static void copy(File from, File to) {
        try {
            InputStream in = new FileInputStream(from);
            try {
                OutputStream out = new FileOutputStream(to);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new YtDlpException("Не удалось скопировать " + from + " -> " + to, e);
        }
    }
}
