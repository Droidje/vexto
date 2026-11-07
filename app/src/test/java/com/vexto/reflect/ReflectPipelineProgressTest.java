package com.vexto.reflect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.vexto.engine.model.Stage;
import org.junit.Test;

public class ReflectPipelineProgressTest {
    @Test public void phaseChangesAndRetriesNeverResetTheOverallScale() {
        var progress = ReflectLocalTranscription.WAITING;
        Stage[] actualOrder = {Stage.Decoding, Stage.Preparing, Stage.Denoising, Stage.Diarization,
                Stage.Voiceprints, Stage.Recognition, Stage.Comparison, Stage.Selection, Stage.Assembly, Stage.Emotion};
        for (Stage stage : actualOrder) {
            int before = progress.overallPercent();
            progress = progress.advance(stage, 0);
            assertTrue(stage.name(), progress.overallPercent() >= before);
            progress = progress.advance(stage, 85);
            int advanced = progress.overallPercent();
            progress = progress.advance(stage, 20);
            assertEquals(advanced, progress.overallPercent());
            progress = progress.advance(stage, 100);
            assertTrue(progress.overallPercent() < 100);
        }
        assertEquals(100, progress.advance(null, 100).overallPercent());
    }

    @Test public void fastWhisperOnlyRouteUsesTheSameForwardPipelineGroups() {
        var progress = ReflectLocalTranscription.WAITING;
        int lastStep = 0;
        int lastPercent = 0;
        for (Stage stage : new Stage[]{Stage.Decoding, Stage.Preparing, Stage.Diarization,
                Stage.Comparison, Stage.Selection, Stage.Assembly}) {
            progress = progress.advance(stage, 50);
            assertTrue(ReflectPipelineProgress.step(stage) >= lastStep);
            assertTrue(progress.overallPercent() >= lastPercent);
            lastStep = ReflectPipelineProgress.step(stage);
            lastPercent = progress.overallPercent();
        }
        assertEquals(4, lastStep);
        assertEquals(100, progress.advance(null, 100).withSpeakers(3).overallPercent());
    }
}
