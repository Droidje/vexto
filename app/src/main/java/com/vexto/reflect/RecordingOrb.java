package com.vexto.reflect;

/** Deforms the artwork around its dark spot while anchoring the sphere's edge. */
final class RecordingOrb {
    static final float LEFT = 39.49f, TOP = 251, WIDTH = 289.51f, HEIGHT = 278;
    static final float CENTER_X = 196.79f, CENTER_Y = 389.72f;
    static final int GRID = 24;
    static final float RADIUS = 130.5f;
    private static final float SPOT_X = 198.5f, SPOT_Y = 476;
    private final float[] base = new float[2 * (GRID + 1) * (GRID + 1)];
    private final float[] vertices = new float[base.length];
    private final float[] weights = new float[base.length / 2];

    RecordingOrb() {
        int i = 0;
        for (int row = 0; row <= GRID; row++) {
            float y = TOP + HEIGHT * row / GRID;
            for (int column = 0; column <= GRID; column++, i++) {
                float x = LEFT + WIDTH * column / GRID;
                base[2 * i] = x;
                base[2 * i + 1] = y;
                double distanceToSpot = Math.hypot((x - SPOT_X) / 104, (y - SPOT_Y) / 64);
                double distanceToCenter = Math.hypot(x - CENTER_X, y - CENTER_Y) / RADIUS;
                float spotWeight = smooth((float) (1 - distanceToSpot) / .62f);
                float rimWeight = smooth((float) (1 - distanceToCenter) / .16f);
                weights[i] = spotWeight * rimWeight;
            }
        }
    }

    float[] vertices(long now, float amount) {
        double phase = (now % 7800) * (2 * Math.PI / 7800);
        float stretch = .36f * (float) Math.sin(phase * 2);
        float curl = .27f * (float) Math.sin(phase * 3 + .5);
        for (int i = 0; i < weights.length; i++) {
            int j = 2 * i;
            float x = base[j], y = base[j + 1];
            float dx = x - SPOT_X, dy = y - SPOT_Y;
            float weight = weights[i] * amount;
            vertices[j] = x + weight * (stretch * dx + curl * dy);
            vertices[j + 1] = y + weight * (-.65f * stretch * dy + .56f * curl * dx);
        }
        return vertices;
    }

    private static float smooth(float value) {
        float t = Math.min(1, Math.max(0, value));
        return t * t * (3 - 2 * t);
    }
}
