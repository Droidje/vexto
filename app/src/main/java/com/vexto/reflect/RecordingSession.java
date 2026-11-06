package com.vexto.reflect;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;

final class RecordingSession {
    private MediaRecorder recorder;
    private File output;
    private long started;
    private long accumulated;
    private boolean paused;

    void start(Context context) throws IOException {
        if (recorder != null) throw new IllegalStateException("Recording already started");
        output = File.createTempFile("reflect-", ".m4a", context.getCacheDir());
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(output.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            started = SystemClock.elapsedRealtime();
            accumulated = 0;
            paused = false;
        } catch (IOException | RuntimeException error) {
            cancel();
            throw error;
        }
    }

    void pause() {
        if (recorder == null || paused) throw new IllegalStateException("No active recording");
        long elapsed = elapsedMillis();
        recorder.pause();
        accumulated = elapsed;
        paused = true;
    }

    void resume() {
        if (recorder == null || !paused) throw new IllegalStateException("No paused recording");
        recorder.resume();
        started = SystemClock.elapsedRealtime();
        paused = false;
    }

    long elapsedMillis() {
        if (recorder == null) return 0;
        return accumulated + (paused ? 0 : SystemClock.elapsedRealtime() - started);
    }

    int peakAmplitude() {
        return recorder == null || paused ? 0 : recorder.getMaxAmplitude();
    }

    File finish() {
        if (recorder == null) throw new IllegalStateException("Recording has not started");
        try {
            recorder.stop();
            return output;
        } catch (RuntimeException error) {
            output.delete();
            throw error;
        } finally {
            release();
        }
    }

    void cancel() {
        if (recorder != null) releaseRecorder();
        if (output != null) output.delete();
        output = null;
    }

    private void release() {
        releaseRecorder();
        output = null;
    }

    private void releaseRecorder() {
        try {
            recorder.release();
        } finally {
            recorder = null;
        }
    }

    static int duration(File file) throws IOException {
        MediaMetadataRetriever media = new MediaMetadataRetriever();
        try {
            media.setDataSource(file.getAbsolutePath());
            String millis = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (millis == null) throw new IOException("The audio has no duration");
            int duration = Integer.parseInt(millis);
            if (duration <= 0) throw new IOException("The audio is empty");
            return duration;
        } finally {
            media.release();
        }
    }
}
