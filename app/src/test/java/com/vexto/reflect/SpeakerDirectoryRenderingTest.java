package com.vexto.reflect;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SpeakerDirectoryRenderingTest {
    @Test public void retainedDirectoryPublishesRenamedRowsBeforeTheReturnTransitionDrawsThem() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        var selected = new AtomicReference<ReflectCatalog.Speaker>();
        var actions = (ReflectLibraryView.Actions) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ReflectLibraryView.Actions.class}, (proxy, method, args) -> {
                    if (method.getName().equals("openSpeaker")) selected.set((ReflectCatalog.Speaker) args[0]);
                    return null;
                });
        var page = new ReflectLibraryView(activity, new ReflectArtwork(activity.getAssets()),
                new ReflectPreferences(activity), ReflectLibraryView.Page.SPEAKERS, actions);
        page.setSpeakers(List.of(new ReflectCatalog.Speaker("global-1", "Спикер 1", "1 минута", null, false)));
        activity.setContentView(page);
        layout(page);
        ListView list = findList(page);
        assertNotNull(list);
        View retainedRow = list.getChildAt(0);
        assertNotNull(retainedRow);
        assertEquals("Спикер 1, 1 минута", retainedRow.getContentDescription());

        // Opening a profile detaches the directory, but keeps its ListView and rows.
        activity.setContentView(new View(activity));
        page.setSpeakers(List.of(new ReflectCatalog.Speaker("global-1", "Анна", "1 минута", null, false)));
        assertEquals("Анна, 1 минута", retainedRow.getContentDescription());
        retainedRow.performClick();
        assertEquals("global-1", selected.get().id);
        assertEquals("Анна", selected.get().name);

        // The same publication path must preserve the dedicated self presentation.
        page.setSpeakers(List.of(new ReflectCatalog.Speaker("global-1", "Анна", "1 минута", null, true)));
        assertEquals("Вы, 1 минута", retainedRow.getContentDescription());
        retainedRow.performClick();
        assertTrue(selected.get().self);
        activity.finish();
    }

    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 393, 800);
    }

    private static ListView findList(View view) {
        if (view instanceof ListView list) return list;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            ListView list = findList(group.getChildAt(index));
            if (list != null) return list;
        }
        return null;
    }
}
