package com.vexto.reflect;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Two concurrent audio readers feed a bounded, pause-aware PCM mixer. Android 10+. */
@TargetApi(29)
final class CallRecordingSession {
    private static final int BLOCK_SAMPLES = CallPcm.SAMPLE_RATE / 50;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Handler main;
    private final Consumer<Throwable> failure;
    private final File draft;
    private final RandomAccessFile output;
    private final Input microphone, playback;
    private final Thread mixer;
    private volatile boolean paused;
    private volatile int generation;
    private volatile long writtenBytes;
    private volatile int peak;

    CallRecordingSession(Context context, MediaProjection projection, Handler main,
                         Consumer<Throwable> failure) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Playback capture requires Android 10");
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Microphone permission is required for call capture");
        }
        this.main = main;
        this.failure = failure;
        File directory = new File(context.getFilesDir(), "call-drafts");
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("No call draft directory");
        draft = new File(directory, UUID.randomUUID() + ".wav");
        RandomAccessFile created = null;
        AudioRecord mic = null, system = null;
        try {
            created = new RandomAccessFile(draft, "rw");
            CallPcm.header(created, 0);
            created.getFD().sync();
            AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(CallPcm.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int minimum = AudioRecord.getMinBufferSize(CallPcm.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("PCM capture format is not supported");
            int bufferBytes = Math.max(minimum * 2, BLOCK_SAMPLES * 8);
            mic = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format).setBufferSizeInBytes(bufferBytes).build();
            AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            system = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture)
                    .setAudioFormat(format).setBufferSizeInBytes(bufferBytes).build();
            if (mic.getState() != AudioRecord.STATE_INITIALIZED || system.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("Both audio sources must be available");
            }
            output = created;
            microphone = new Input(mic, "vexto-call-microphone");
            playback = new Input(system, "vexto-call-playback");
            mixer = new Thread(this::mix, "vexto-call-mixer");
        } catch (IOException | RuntimeException problem) {
            if (mic != null) mic.release();
            if (system != null) system.release();
            if (created != null) created.close();
            draft.delete();
            throw problem;
        }
    }

    void start() {
        microphone.recorder.startRecording();
        playback.recorder.startRecording();
        if (microphone.recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING
                || playback.recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("Both audio readers must be recording");
        }
        microphone.thread.start();
        playback.thread.start();
        mixer.start();
    }

    void pause() { paused = true; generation++; peak = 0; }
    void resume() { generation++; paused = false; }
    boolean paused() { return paused; }
    long elapsedMillis() { return writtenBytes * 1000 / (CallPcm.SAMPLE_RATE * 2L); }
    int peakAmplitude() { return paused ? 0 : peak; }

    /** Call from the service's IO executor, after detaching this session from service state. */
    File finish(Context context, boolean discard) throws IOException {
        if (!closed.compareAndSet(false, true)) return null;
        stop(microphone.recorder);
        stop(playback.recorder);
        microphone.thread.interrupt();
        playback.thread.interrupt();
        mixer.interrupt();
        join(microphone.thread);
        join(playback.thread);
        join(mixer);
        microphone.recorder.release();
        playback.recorder.release();
        if (discard) {
            try { synchronized (output) { output.close(); } }
            finally { Files.deleteIfExists(draft.toPath()); }
            return null;
        }
        long bytes;
        synchronized (output) {
            try { bytes = CallPcm.repair(output); }
            finally { output.close(); }
        }
        if (bytes == 0) {
            Files.deleteIfExists(draft.toPath());
            return null;
        }
        return publish(context, draft);
    }

    static void recover(Context context) throws IOException {
        File[] drafts = new File(context.getFilesDir(), "call-drafts").listFiles(
                file -> file.isFile() && file.getName().endsWith(".wav"));
        if (drafts == null) return;
        for (File draft : drafts) {
            long bytes;
            try (RandomAccessFile output = new RandomAccessFile(draft, "rw")) { bytes = CallPcm.repair(output); }
            if (bytes == 0) Files.deleteIfExists(draft.toPath());
            else publish(context, draft);
        }
    }

    private static File publish(Context context, File draft) throws IOException {
        File directory = new File(context.getFilesDir(), "recordings");
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("No recording directory");
        File saved = new File(directory, draft.getName());
        try { Files.move(draft.toPath(), saved.toPath(), StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(draft.toPath(), saved.toPath()); }
        return saved;
    }

    private void mix() {
        byte[] mixed = new byte[BLOCK_SAMPLES * 2];
        try {
            Block mic = null, system = null;
            long lastBlock = SystemClock.elapsedRealtime();
            while (!closed.get()) {
                if (mic == null) mic = microphone.blocks.poll(200, TimeUnit.MILLISECONDS);
                if (system == null) system = playback.blocks.poll(200, TimeUnit.MILLISECONDS);
                if (paused) {
                    mic = system = null;
                    lastBlock = SystemClock.elapsedRealtime();
                    continue;
                }
                if (mic == null || system == null) {
                    if (SystemClock.elapsedRealtime() - lastBlock > 2_000) throw new IOException("Audio source stopped delivering samples");
                    continue;
                }
                int epoch = generation;
                if (mic.generation != epoch) { mic = null; continue; }
                if (system.generation != epoch) { system = null; continue; }
                if (mic.sequence < system.sequence) { mic = null; continue; }
                if (system.sequence < mic.sequence) { system = null; continue; }
                peak = CallPcm.mix(mic.samples, system.samples, mixed);
                synchronized (output) {
                    if (closed.get()) return;
                    if (writtenBytes + mixed.length > CallPcm.MAX_DATA_BYTES) throw new IOException("Recording reached WAV size limit");
                    output.write(mixed);
                    writtenBytes += mixed.length;
                    CallPcm.header(output, writtenBytes);
                }
                mic = system = null;
                lastBlock = SystemClock.elapsedRealtime();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException problem) {
            report(problem);
        }
    }

    private void report(Throwable problem) {
        if (!closed.get() && failed.compareAndSet(false, true)) main.post(() -> failure.accept(problem));
    }

    private final class Input {
        final AudioRecord recorder;
        final ArrayBlockingQueue<Block> blocks = new ArrayBlockingQueue<>(16);
        final Thread thread;

        Input(AudioRecord recorder, String name) {
            this.recorder = recorder;
            thread = new Thread(this::read, name);
        }

        void read() {
            int observedGeneration = -1;
            long sequence = 0;
            try {
                while (!closed.get()) {
                    int epoch = generation;
                    short[] samples = new short[BLOCK_SAMPLES];
                    int filled = 0;
                    while (filled < samples.length && !closed.get()) {
                        int count = recorder.read(samples, filled, samples.length - filled, AudioRecord.READ_BLOCKING);
                        if (count <= 0) throw new IOException("AudioRecord read failed: " + count);
                        filled += count;
                    }
                    if (paused || epoch != generation || closed.get()) continue;
                    if (observedGeneration != epoch) {
                        observedGeneration = epoch;
                        sequence = 0;
                    }
                    if (!blocks.offer(new Block(epoch, sequence++, samples), 200, TimeUnit.MILLISECONDS)) {
                        throw new IOException("Audio mixing could not keep up with capture");
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException problem) {
                report(problem);
            }
        }
    }

    private static void stop(AudioRecord recorder) {
        try { recorder.stop(); } catch (IllegalStateException ignored) { /* A failing reader may already be stopped. */ }
    }

    private static void join(Thread thread) {
        try { thread.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private record Block(int generation, long sequence, short[] samples) {}
}
