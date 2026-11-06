package com.vexto.reflect;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.vexto.engine.ReflectEmotionEngine;
import com.vexto.engine.ReflectEngine;
import com.vexto.engine.model.EmotionProbabilities;
import com.vexto.engine.model.ProcessingStats;
import com.vexto.engine.model.Stage;
import com.vexto.engine.model.Transcript;
import com.vexto.engine.model.Utterance;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises real ONNX cleanup followed by real foreground pipeline startup, with no network. */
@RunWith(AndroidJUnit4.class)
public class ReflectEmotionPriorityDeviceTest {
    @Test public void foregroundTranscriptionPreemptsActiveEmotionBatch() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File audio = File.createTempFile("emotion-priority", ".wav", context.getCacheDir());
        ReflectEmotionDeviceTest.writeTone(audio);
        ArrayList<Utterance> lines = new ArrayList<>();
        for (int i = 0; i < 32; i++) lines.add(new Utterance("speaker_1", 0, 2000,
                "Native priority fixture", Collections.emptyList(), null));
        CountDownLatch yielded = new CountDownLatch(1), foregroundDone = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<ReflectEngine.Task> foreground = new AtomicReference<>();
        AtomicLong requestedAt = new AtomicLong(), admittedAt = new AtomicLong();
        Handler main = new Handler(Looper.getMainLooper());
        ReflectEmotionEngine.Task background = ReflectEmotionEngine.analyze(context, Uri.fromFile(audio), lines,
                new ReflectEmotionEngine.Listener() {
            @Override public boolean onUtterance(int index, EmotionProbabilities scores) {
                if (index == 0) main.post(() -> {
                    requestedAt.set(SystemClock.elapsedRealtime());
                    foreground.set(ReflectEngine.transcribe(context, Uri.fromFile(audio), new ReflectEngine.Listener() {
                        @Override public void onProgress(Stage stage, float fraction) {
                            admittedAt.compareAndSet(0, SystemClock.elapsedRealtime());
                        }
                        @Override public void onPartial(Transcript transcript) {}
                        @Override public void onDone(Transcript transcript, ProcessingStats stats) {
                            if (transcript.getUtterances().stream().anyMatch(line -> line.getEmotionProbabilities() != null)) {
                                error.set(new AssertionError("Foreground pipeline still evaluates emotions inline"));
                            }
                            foregroundDone.countDown();
                        }
                        @Override public void onError(Throwable failure) { error.set(failure); foregroundDone.countDown(); }
                    }));
                });
                return true;
            }
            @Override public void onDone() { error.set(new AssertionError("Low-priority batch was not preempted")); yielded.countDown(); }
            @Override public void onYielded() { yielded.countDown(); }
            @Override public void onError(Throwable failure) { error.set(failure); yielded.countDown(); foregroundDone.countDown(); }
        });
        try {
            assertTrue("No cooperative yield", yielded.await(120, TimeUnit.SECONDS));
            assertTrue("Foreground pipeline did not finish", foregroundDone.await(180, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError(error.get());
            assertTrue(admittedAt.get() >= requestedAt.get() && requestedAt.get() > 0);
            android.util.Log.i("ReflectEmotionTest", "foreground admission ms=" + (admittedAt.get() - requestedAt.get()));
        } finally {
            background.cancel();
            if (foreground.get() != null) foreground.get().cancel();
            audio.delete();
        }
    }
}
