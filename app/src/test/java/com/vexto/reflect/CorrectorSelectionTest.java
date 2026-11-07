package com.vexto.reflect;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CorrectorSelectionTest {
    @Test public void correctorIsOffUntilChosen() {
        Context context = RuntimeEnvironment.getApplication();
        ReflectPreferences preferences = new ReflectPreferences(context);
        assertEquals(ReflectPreferences.PUNCTUATION_OFF, preferences.punctuation());
        preferences.setPunctuation("unexpected");
        assertEquals(ReflectPreferences.PUNCTUATION_OFF, preferences.punctuation());
        preferences.setPunctuation(ReflectPreferences.PUNCTUATION_SELF);
        assertEquals(ReflectPreferences.PUNCTUATION_SELF, preferences.punctuation());
    }

    @Test public void selfModeSkipsOtherSpeakers() {
        assertFalse(CorrectorSelection.applies(ReflectPreferences.PUNCTUATION_OFF, true));
        assertFalse(CorrectorSelection.applies(ReflectPreferences.PUNCTUATION_SELF, false));
        assertTrue(CorrectorSelection.applies(ReflectPreferences.PUNCTUATION_SELF, true));
        assertTrue(CorrectorSelection.applies(ReflectPreferences.PUNCTUATION_ALL, false));
    }
}
