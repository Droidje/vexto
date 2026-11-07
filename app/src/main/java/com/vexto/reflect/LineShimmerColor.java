package com.vexto.reflect;

/** Голубая волна по символам реплики. Без Android, чтобы цвет можно было проверить тестом. */
final class LineShimmerColor {
    static final int REST = 0xff1a6fe0;
    static final int PEAK = 0xffd7ecff;

    private LineShimmerColor() {}

    static int at(int index, int count, float phase) {
        int length = Math.max(count, 1);
        float center = (phase * 1.5f - 0.25f) * length;
        float band = Math.max(length * 0.42f, 1.5f);
        float distance = Math.abs(index + 0.5f - center) / band;
        if (distance >= 1f) return REST;
        float wave = (float) Math.cos(distance * Math.PI / 2d);
        return blend(REST, PEAK, wave * wave);
    }

    private static int blend(int from, int to, float amount) {
        float mix = Math.max(0f, Math.min(1f, amount));
        int red = channel(from, 16, to, mix);
        int green = channel(from, 8, to, mix);
        int blue = channel(from, 0, to, mix);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private static int channel(int from, int shift, int to, float mix) {
        int start = (from >> shift) & 0xff;
        int end = (to >> shift) & 0xff;
        return Math.round(start + (end - start) * mix);
    }
}
