package com.vexto.reflect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Model-scoped vector operations; similarities are scores, not identity probabilities. */
final class VoiceVectors {
    private VoiceVectors() {}

    static float[] normalize(float[] input) {
        if (input == null || input.length == 0 || input.length > 4096) throw new IllegalArgumentException("Invalid dimension");
        double squared = 0;
        for (float value : input) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite embedding");
            squared += (double) value * value;
        }
        if (squared <= 1e-18) throw new IllegalArgumentException("Zero embedding");
        double scale = 1 / Math.sqrt(squared);
        float[] out = new float[input.length];
        for (int i = 0; i < out.length; i++) out[i] = (float) (input[i] * scale);
        return out;
    }

    static byte[] encode(float[] input) {
        float[] normalized = normalize(input);
        ByteBuffer bytes = ByteBuffer.allocate(normalized.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : normalized) bytes.putFloat(value);
        return bytes.array();
    }

    static float[] decode(byte[] bytes, int dimension) {
        if (dimension < 1 || dimension > 4096 || bytes == null || bytes.length != dimension * 4)
            throw new IllegalArgumentException("Corrupt embedding");
        float[] values = new float[dimension];
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < values.length; i++) values[i] = input.getFloat();
        return normalize(values);
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("Different embedding dimensions");
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return Math.max(-1, Math.min(1, dot));
    }
}
