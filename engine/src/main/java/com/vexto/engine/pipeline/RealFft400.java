package com.vexto.engine.pipeline;

/** Mixed-radix 2/5 FFT. The 400-point geometry matches the trained frontend exactly. */
final class RealFft400 {
    private static final int SIZE = 400;
    private static final double[] COS = new double[SIZE], SIN = new double[SIZE];
    static {
        for (int k = 0; k < SIZE; k++) {
            COS[k] = Math.cos(2 * Math.PI * k / SIZE);
            SIN[k] = -Math.sin(2 * Math.PI * k / SIZE);
        }
    }
    private final double[] real = new double[SIZE], imaginary = new double[SIZE];
    private final double[][] scratchReal = new double[7][5], scratchImaginary = new double[7][5];

    void power(double[] samples, float[] output) {
        transform(samples, 0, 1, 0, SIZE, 0);
        for (int k = 0; k < output.length; k++)
            output[k] = (float)(real[k] * real[k] + imaginary[k] * imaginary[k]);
    }

    private void transform(double[] samples, int from, int stride, int offset, int size, int depth) {
        if (size == 1) { real[offset] = samples[from]; imaginary[offset] = 0; return; }
        int radix = size % 2 == 0 ? 2 : 5;
        int block = size / radix;
        for (int j = 0; j < radix; j++)
            transform(samples, from + j * stride, stride * radix, offset + j * block, block, depth + 1);
        double[] tr = scratchReal[depth], ti = scratchImaginary[depth];
        for (int k = 0; k < block; k++) {
            for (int j = 0; j < radix; j++) {
                tr[j] = real[offset + j * block + k];
                ti[j] = imaginary[offset + j * block + k];
            }
            for (int q = 0; q < radix; q++) {
                double re = 0, im = 0;
                for (int j = 0; j < radix; j++) {
                    int at = j * (k + q * block) * (SIZE / size) % SIZE;
                    re += tr[j] * COS[at] - ti[j] * SIN[at];
                    im += tr[j] * SIN[at] + ti[j] * COS[at];
                }
                real[offset + q * block + k] = re;
                imaginary[offset + q * block + k] = im;
            }
        }
    }
}
