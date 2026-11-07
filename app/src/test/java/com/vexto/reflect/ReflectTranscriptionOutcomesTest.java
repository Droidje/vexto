package com.vexto.reflect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReflectTranscriptionOutcomesTest {
    @Test public void onlyATerminalFailureCreatesARetryAndSuccessClearsIt() {
        var context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("transcription_outcomes", 0).edit().clear().commit();
        File recording = new File(context.getFilesDir(), "recordings/failed.wav");
        var outcomes = new ReflectTranscriptionOutcomes(context);
        assertNull(outcomes.failure(recording));
        outcomes.finish(recording, false, null);
        assertNull(outcomes.failure(recording));
        outcomes.finish(recording, false, "Речь не распознана");
        assertEquals("Речь не распознана", new ReflectTranscriptionOutcomes(context).failure(recording));
        assertNull(outcomes.failure(new File("another.wav")));
        outcomes.finish(recording, true, "Расшифровка сохранена с предупреждением");
        assertNull(outcomes.failure(recording));
    }
}
