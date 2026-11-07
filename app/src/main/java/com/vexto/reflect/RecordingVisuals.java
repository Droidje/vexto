package com.vexto.reflect;

import java.util.Arrays;

/** A short envelope of real microphone peaks and a clock-driven orbit. */
final class RecordingVisuals {
    static final int BARS = 32;
    private static final long SAMPLE_MS = 48;
    private static final long ORBIT_MS = 9_000;
    private static final long SETTLE_MS = 380;
    private final float[] peaks = new float[BARS];
    private final float[] from = new float[BARS];
    private final float[] to = new float[BARS];
    private int newest;
    private long sampledAt, orbitAt;
    private float origin, settled, morphOrigin;
    private boolean orbiting, settling;

    static long sampleInterval() { return SAMPLE_MS; }

    void start(long now, boolean animateOrbit) {
        origin = animateOrbit ? angle(now) : 0;
        morphOrigin = animateOrbit ? morphAmount(now) : 0;
        orbitAt = now;
        orbiting = animateOrbit;
        settling = false;
        Arrays.fill(peaks, 0);
        Arrays.fill(from, 0);
        Arrays.fill(to, 0);
        newest = 0;
        sampledAt = now;
    }

    void pause(long now) {
        if (!orbiting) {
            reset();
            return;
        }
        origin = angle(now);
        morphOrigin = morphAmount(now);
        // Return to the idle orientation over the shorter arc.
        settled = origin > 180 ? 360 : 0;
        orbitAt = now;
        orbiting = false;
        settling = origin != settled || morphOrigin != 0;
    }

    void reset() {
        orbiting = false;
        settling = false;
    }

    void sample(int peak, long now) {
        float progress = sampleProgress(now);
        for (int i = 0; i < BARS; i++) from[i] += (to[i] - from[i]) * progress;
        newest = (newest + BARS - 1) % BARS;
        peaks[newest] = normalizedPeak(peak);
        int middle = BARS / 2;
        for (int i = 0; i < BARS; i++) {
            int distance = i < middle ? middle - 1 - i : i - middle;
            int age = 2 * distance + (i < middle ? 0 : 1);
            to[i] = peaks[(newest + age) % BARS];
        }
        sampledAt = now;
    }

    float sampleProgress(long now) { return Math.min(1, Math.max(0, (now - sampledAt) / (float) SAMPLE_MS)); }

    float height(int index, float progress) {
        float level = from[index] + (to[index] - from[index]) * progress;
        float distance = Math.abs(index - (BARS - 1) / 2f) / ((BARS - 1) / 2f);
        float taper = 1 - .76f * distance;
        return 2.5f + 57 * taper * level;
    }

    float angle(long now) {
        if (orbiting) return (origin + 360f * ((now - orbitAt) % ORBIT_MS) / ORBIT_MS) % 360;
        if (!settling) return 0;
        return origin + (settled - origin) * settleProgress(now);
    }

    float morphAmount(long now) {
        if (orbiting) return morphOrigin + (1 - morphOrigin) * settleProgress(now);
        if (settling) return morphOrigin * (1 - settleProgress(now));
        return 0;
    }

    float reveal(long now) { return orbiting ? settleProgress(now) : 1; }

    boolean needsFrame(long now) { return orbiting || (settling && now - orbitAt < SETTLE_MS); }

    private float settleProgress(long now) {
        float progress = Math.min(1, Math.max(0, (now - orbitAt) / (float) SETTLE_MS));
        return progress * progress * (3 - 2 * progress);
    }

    private static float normalizedPeak(int peak) {
        if (peak <= 0) return 0;
        double db = 20 * Math.log10(Math.min(32768, peak) / 32768.0);
        return (float) Math.max(0, Math.min(1, (db + 55) / 50));
    }
}
