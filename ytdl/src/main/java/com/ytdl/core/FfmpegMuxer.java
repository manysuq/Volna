package com.ytdl.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Склейка и конвертация через внешний ffmpeg. Годится для JVM и для
 * Android-устройств с ffmpeg в $PATH (termux), в отличие от MediaMuxer
 * умеет MP3 и любые контейнеры.
 */
public final class FfmpegMuxer implements Muxer {

    private final String ffmpeg;

    public FfmpegMuxer() {
        this("ffmpeg");
    }

    public FfmpegMuxer(String ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    @Override
    public boolean isAvailable() {
        return runQuietly(ffmpeg + " -version");
    }

    @Override
    public void merge(File video, File audio, File output, String container) throws YtDlpException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-y");
        if (video != null) {
            cmd.add("-i");
            cmd.add(video.getAbsolutePath());
        }
        if (audio != null) {
            cmd.add("-i");
            cmd.add(audio.getAbsolutePath());
        }
        cmd.add("-map");
        cmd.add(video != null ? "0:v:0" : "0:a:0");
        if (video != null && audio != null) {
            cmd.add("-map");
            cmd.add("1:a:0");
        }
        cmd.add("-c");
        cmd.add("copy");
        cmd.add(output.getAbsolutePath());
        run(cmd);
    }

    @Override
    public void extractAudio(File source, File output, String audioFormat) throws YtDlpException {
        String codec;
        String format;
        if ("mp3".equals(audioFormat)) {
            codec = "libmp3lame";
            format = "mp3";
        } else if ("opus".equals(audioFormat)) {
            codec = "libopus";
            format = "opus";
        } else if ("flac".equals(audioFormat)) {
            codec = "flac";
            format = "flac";
        } else if ("ogg".equals(audioFormat)) {
            codec = "libvorbis";
            format = "ogg";
        } else {
            codec = "aac";
            format = "ipod";
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(source.getAbsolutePath());
        cmd.add("-vn");
        cmd.add("-c:a");
        cmd.add(codec);
        if ("aac".equals(codec)) {
            cmd.add("-b:a");
            cmd.add("192k");
        } else if ("libopus".equals(codec)) {
            cmd.add("-b:a");
            cmd.add("160k");
        }
        cmd.add("-f");
        cmd.add(format);
        cmd.add(output.getAbsolutePath());
        run(cmd);
    }

    /**
     * Записывает теги переremux-ом (копия потоков, без перекодирования).
     * Контейнер результата обязан совпадать с исходным, иначе файл
     * окажется mp4 внутри файла с расширением .webm.
     */
    @Override
    public void writeTags(File media, Map<String, String> tags) {
        String muxer = muxerForExtension(FileUtils.extension(media.getName()));
        if (muxer == null) {
            return; // неизвестный контейнер: не рискуем файлом
        }
        File tmp = new File(media.getAbsolutePath() + ".tagged.tmp");
        List<String> cmd = new ArrayList<String>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(media.getAbsolutePath());
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-map_metadata");
        cmd.add("0");
        for (Map.Entry<String, String> e : tags.entrySet()) {
            cmd.add("-metadata");
            cmd.add(e.getKey() + "=" + sanitizeTagValue(e.getValue()));
        }
        cmd.add("-f");
        cmd.add(muxer);
        cmd.add(tmp.getAbsolutePath());
        try {
            run(cmd);
            if (tmp.isFile() && tmp.length() > 0) {
                FileUtils.move(tmp, media);
            } else {
                FileUtils.deleteQuietly(tmp);
            }
        } catch (YtDlpException e) {
            FileUtils.deleteQuietly(tmp); // исходный файл остаётся нетронутым
        }
    }

    /** muxer ffmpeg по расширению файла; null, если контейнер не поддерживается. */
    private static String muxerForExtension(String extension) {
        if ("mp4".equals(extension) || "m4a".equals(extension) || "mov".equals(extension)) {
            return "ipod";
        }
        if ("webm".equals(extension) || "mkv".equals(extension)) {
            return "matroska";
        }
        if ("mp3".equals(extension)) {
            return "mp3";
        }
        if ("opus".equals(extension)) {
            return "opus";
        }
        if ("flac".equals(extension)) {
            return "flac";
        }
        if ("ogg".equals(extension)) {
            return "ogg";
        }
        return null;
    }

    private static String sanitizeTagValue(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c == '=' || c == '\\' || c < 32 ? '_' : c);
        }
        return sb.toString();
    }

    private static boolean runQuietly(String command) {
        try {
            Process p = Runtime.getRuntime().exec(command);
            drain(p);
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void run(List<String> cmd) throws YtDlpException {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new YtDlpException("ffmpeg завершился с кодом " + code + ":\n" + output);
            }
        } catch (IOException e) {
            throw new YtDlpException("Не удалось запустить ffmpeg (" + e.getMessage()
                    + "). На Android используйте MediaMuxerMuxer или установите ffmpeg.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YtDlpException("ffmpeg прерван", e);
        }
    }

    private static void drain(Process p) {
        try {
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            while (in.read(buf) != -1) {
                // вычитываем, чтобы процесс не завис
            }
        } catch (IOException ignored) {
            // нечего делать
        }
    }
}
