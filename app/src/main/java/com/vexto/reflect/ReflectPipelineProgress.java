package com.vexto.reflect;

import com.vexto.engine.model.Stage;

/** One pipeline scale for the library, foreground screen, and notification. */
final class ReflectPipelineProgress {
    private ReflectPipelineProgress() {}

    static int step(Stage stage) {
        if (stage == null) return 4;
        return switch (stage) {
            case Decoding, Preparing, Denoising -> 0;
            case Diarization, Language, Voiceprints -> 1;
            case Recognition, Comparison -> 2;
            case Selection -> 3;
            case Assembly, Punctuation, Emotion -> 4;
        };
    }

    // These phase weights describe pipeline completion, not elapsed or remaining time.
    // A skipped phase advances directly to the next real phase's position on the scale.
    static int overall(Stage stage, int percent) {
        if (stage == null) return 100;
        return switch (stage) {
            case Decoding -> part(0, 3, percent);
            case Preparing -> part(3, 8, percent);
            case Denoising -> part(8, 13, percent);
            case Diarization, Language -> part(13, 22, percent);
            case Voiceprints -> part(22, 25, percent);
            case Recognition -> part(25, 45, percent);
            case Comparison -> part(45, 75, percent);
            case Selection -> part(75, 93, percent);
            case Assembly -> part(93, 95, percent);
            case Punctuation -> part(95, 96, percent);
            case Emotion -> part(96, 99, percent);
        };
    }

    private static int part(int start, int end, int percent) {
        int bounded = Math.max(0, Math.min(100, percent));
        return start + Math.round((end - start) * bounded / 100f);
    }
}
