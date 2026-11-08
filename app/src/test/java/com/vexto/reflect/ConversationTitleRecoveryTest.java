package com.vexto.reflect;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ConversationTitleRecoveryTest {
    private Context context;
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase("conversations.db"); }
    @After public void cleanup() { context.deleteDatabase("conversations.db"); }

    @Test public void failedGenerationDoesNotConsumeTheSingleSuccessfulAssignment() {
        File audio = new File("retry.wav");
        try (var store = new ReflectConversationStore(context)) {
            store.title(audio);
            assertFalse(store.completeTitle(audio, null));
            assertFalse(store.completeTitle(audio, " "));
            assertTrue(store.needsTitle(audio));
            assertTrue(store.completeTitle(audio, "Перенос запуска"));
            assertFalse(store.completeTitle(audio, "Другая попытка"));
            assertEquals("Перенос запуска", store.title(audio));
        }
    }

    @Test public void migrationRepairsOnlyAutomaticRowsWithoutAResult() {
        try (var old = context.openOrCreateDatabase("conversations.db", Context.MODE_PRIVATE, null)) {
            old.execSQL("CREATE TABLE conversations (number INTEGER PRIMARY KEY AUTOINCREMENT, recording TEXT NOT NULL UNIQUE, title TEXT, title_state INTEGER NOT NULL DEFAULT 0)");
            old.execSQL("INSERT INTO conversations VALUES (1,'failed.wav',NULL,1),(2,'named.wav','Название',1),(3,'manual.wav',NULL,2),(4,'deleted.wav',NULL,3),(5,'pending.wav',NULL,0)");
            old.setVersion(2);
        }
        try (var store = new ReflectConversationStore(context)) {
            assertTrue(store.needsTitle(new File("failed.wav")));
            assertTrue(store.needsTitle(new File("pending.wav")));
            assertFalse(store.needsTitle(new File("named.wav")));
            assertFalse(store.needsTitle(new File("manual.wav")));
            assertFalse(store.needsTitle(new File("deleted.wav")));
            assertEquals("Название", store.title(new File("named.wav")));
            assertEquals("Разговор №1", store.title(new File("failed.wav")));
            assertTrue(store.isDeleted(new File("deleted.wav")));
        }
    }
}
