package com.vexto.reflect;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.vexto.engine.model.EmotionProbabilities;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real packaged ONNX smoke test, including the production background queue and atomic sidecar.
 * The generated tone tests execution only; it is deliberately not an emotion accuracy fixture. */
@RunWith(AndroidJUnit4.class)
public class ReflectEmotionDeviceTest {
    @Test public void packagedModelFillsPendingLinesThroughBackgroundQueue() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "recordings");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File audio = new File(directory, "emotion-device-test-" + System.nanoTime() + ".wav");
        writeTone(audio);
        ReflectTranscriptStore store = new ReflectTranscriptStore(context.getFilesDir());
        try {
            ReflectTranscript transcript = new ReflectTranscript(List.of(
                    new ReflectTranscript.Line("speaker_1", 0, 1000, "First test interval"),
                    new ReflectTranscript.Line("speaker_2", 1000, 2000, "Second test interval")));
            assertEquals("none", transcript.lines.get(0).emotionName());
            store.save(audio, transcript);
            // Production listener schedules the JobService. No direct inference or scheduler override.
            long deadline = SystemClock.elapsedRealtime() + 240_000;
            ReflectTranscript result;
            do {
                result = store.load(audio);
                if (result.lines.stream().allMatch(line -> line.emotionProbabilities != null)) break;
                SystemClock.sleep(250);
            } while (SystemClock.elapsedRealtime() < deadline);
            for (ReflectTranscript.Line line : result.lines) {
                EmotionProbabilities p = line.emotionProbabilities;
                assertNotNull("Background inference did not persist " + line.startMs, p);
                assertEquals(1.0, p.getNeutral() + p.getJoy() + p.getSadness() + p.getAnger(), 0.001);
                assertNotEquals("none", line.emotionName());
            }
            assertEquals("First test interval", result.lines.get(0).text);
            assertEquals("speaker_2", result.lines.get(1).speakerId);
        } finally {
            assertTrue(audio.delete());
            new File(new File(context.getFilesDir(), "transcripts"), audio.getName() + ".json").delete();
            ReflectEmotionCoordinator.get(context).reconcile();
        }
    }

    static void writeTone(File file) throws Exception {
        int samples = 32000;
        ByteBuffer wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + samples * 2);
        wav.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put(new byte[]{'d','a','t','a'}).putInt(samples * 2);
        for (int i = 0; i < samples; i++) wav.putShort((short) (3000 * Math.sin(i * 2 * Math.PI * 220 / 16000)));
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(wav.array()); }
    }
}
