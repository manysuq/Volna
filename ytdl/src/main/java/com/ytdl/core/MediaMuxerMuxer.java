package com.ytdl.core;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Склейка на чистом Android API: MediaExtractor + MediaMuxer, без ffmpeg.
 *
 * Ограничения MediaMuxer: контейнер только mp4 (mpeg4) или webm,
 * перекодирования нет, кодек должен соответствовать контейнеру.
 * С MP3 не работает — для него нужен FfmpegMuxer или внешний конвертер.
 */
public final class MediaMuxerMuxer implements Muxer {

    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void merge(File video, File audio, File output, String container) throws YtDlpException {
        int outputFormat = outputFormatFor(container);
        MediaMuxer muxer = null;
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        int audioTrack = -1;
        try {
            FileUtils.deleteQuietly(output);
            muxer = new MediaMuxer(output.getAbsolutePath(), outputFormat);

            if (video == null) {
                throw new YtDlpException("merge требует видеофайл; для аудио используйте extractAudio");
            }
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(video.getAbsolutePath());
            int videoTrack = findTrack(videoExtractor, "video/");
            if (videoTrack < 0) {
                throw new YtDlpException("В видеофайле нет видеодорожки");
            }
            videoExtractor.selectTrack(videoTrack);
            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrack);
            muxer.addTrack(videoFormat);

            if (audio != null && audio.isFile()) {
                audioExtractor = new MediaExtractor();
                audioExtractor.setDataSource(audio.getAbsolutePath());
                audioTrack = findTrack(audioExtractor, "audio/");
                if (audioTrack >= 0) {
                    audioExtractor.selectTrack(audioTrack);
                    muxer.addTrack(audioExtractor.getTrackFormat(audioTrack));
                } else {
                    audioExtractor.release();
                    audioExtractor = null;
                }
            }

            muxer.start();
            // индексы дорожек в muxer соответствуют порядку addTrack
            writeSamples(muxer, videoExtractor, videoTrack, 0);
            if (audioExtractor != null && audioTrack >= 0) {
                writeSamples(muxer, audioExtractor, audioTrack, 1);
            }
        } catch (IOException e) {
            throw new YtDlpException("Ошибка склейки: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new YtDlpException("MediaMuxer отклонил формат: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new YtDlpException("Ошибка состояния MediaMuxer: " + e.getMessage(), e);
        } finally {
            release(videoExtractor);
            release(audioExtractor);
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (IllegalStateException ignored) {
                    // muxer мог не стартовать
                }
                muxer.release();
            }
        }
    }

    @Override
    public void extractAudio(File source, File output, String audioFormat) throws YtDlpException {
        if ("mp3".equals(audioFormat)) {
            throw new YtDlpException("MediaMuxer не умеет MP3 — используйте FfmpegMuxer "
                    + "или оставьте исходный формат (m4a/webm/opus)");
        }
        String container = "m4a".equals(audioFormat) || "aac".equals(audioFormat)
                ? "mp4" : audioFormat;
        int outputFormat = outputFormatFor(container);
        MediaMuxer muxer = null;
        MediaExtractor extractor = null;
        try {
            FileUtils.deleteQuietly(output);
            muxer = new MediaMuxer(output.getAbsolutePath(), outputFormat);
            extractor = new MediaExtractor();
            extractor.setDataSource(source.getAbsolutePath());
            int track = findTrack(extractor, "audio/");
            if (track < 0) {
                throw new YtDlpException("В файле нет аудиодорожки: " + source.getName());
            }
            extractor.selectTrack(track);
            muxer.addTrack(extractor.getTrackFormat(track));
            muxer.start();
            writeSamples(muxer, extractor, track, 0);
        } catch (IOException e) {
            throw new YtDlpException("Ошибка извлечения аудио: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new YtDlpException("Формат не принимается контейнером " + container
                    + ": " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new YtDlpException("Ошибка MediaMuxer: " + e.getMessage(), e);
        } finally {
            release(extractor);
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (IllegalStateException ignored) {
                    // muxer мог не стартовать
                }
                muxer.release();
            }
        }
    }

    @Override
    public void writeTags(File media, Map<String, String> tags) {
        // MediaMuxer не умеет писать теги: заполняйте метаданные у себя в приложении
    }

    private static int outputFormatFor(String container) throws YtDlpException {
        if (container == null) {
            return MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
        }
        if ("mp4".equals(container) || "m4a".equals(container) || "mov".equals(container)) {
            return MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
        }
        if ("webm".equals(container)) {
            return MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
        }
        if ("3gp".equals(container)) {
            return MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP;
        }
        throw new YtDlpException("MediaMuxer не поддерживает контейнер: " + container);
    }

    private static int findTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return i;
            }
        }
        return -1;
    }

    /** Копирует семплы дорожки в муксер без перекодирования. */
    private static void writeSamples(MediaMuxer muxer, MediaExtractor extractor,
                                     int sourceTrack, int muxerTrack) throws YtDlpException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            info.offset = 0;
            info.size = size;
            long timeUs = extractor.getSampleTime();
            if (timeUs < 0) {
                break;
            }
            info.presentationTimeUs = timeUs;
            info.flags = extractor.getSampleFlags();
            try {
                muxer.writeSampleData(muxerTrack, buffer, info);
            } catch (IllegalArgumentException e) {
                throw new YtDlpException("Формат дорожки не принимается контейнером: "
                        + e.getMessage(), e);
            } catch (IllegalStateException e) {
                throw new YtDlpException("MediaMuxer отклонил семпл: " + e.getMessage(), e);
            }
            extractor.advance();
        }
    }

    private static void release(MediaExtractor extractor) {
        if (extractor != null) {
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
                // нечего делать
            }
        }
    }
}
