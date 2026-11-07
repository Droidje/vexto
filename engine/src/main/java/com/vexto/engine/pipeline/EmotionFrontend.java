package com.vexto.engine.pipeline;

/** GigaAM official frontend; parity checked against Torchaudio2.9.1. Bounded to8s. */
public final class EmotionFrontend {
    private static final int RATE=16000, FFT=400, HOP=160, MELS=64, MAX_SAMPLES=8*RATE;
    private static final int BINS = FFT / 2 + 1;
    // Construct once per worker/classloader. Tables are private and never mutated after init.
    private static final double[] WINDOW = new double[FFT];
    private static final float[][] FILTER = new float[MELS][BINS];
    private static final int[] FIRST = new int[MELS], LAST = new int[MELS];
    private EmotionFrontend() {}
    static {
        for (int n = 0; n < FFT; n++) {
            WINDOW[n] = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * n / FFT));
        }
        float[] hz = new float[MELS + 2];
        float maxMel = (float)(2595 * Math.log10(1 + 8000.0 / 700));
        for (int m = 0; m < hz.length; m++) {
            float mel = maxMel * m / (MELS + 1);
            hz[m] = (float)(700 * (Math.pow(10, mel / 2595.0f) - 1));
        }
        for (int m = 0; m < MELS; m++) for (int k = 0; k < BINS; k++) {
            float freq = (float)RATE * k / FFT;
            FILTER[m][k] = Math.max(0, Math.min((freq - hz[m]) / (hz[m+1] - hz[m]),
                (hz[m+2] - freq) / (hz[m+2] - hz[m+1])));
        }
        for (int m = 0; m < MELS; m++) {
            int first = 0, last = BINS;
            while (first < last && FILTER[m][first] == 0) first++;
            while (last > first && FILTER[m][last - 1] == 0) last--;
            FIRST[m] = first; LAST[m] = last;
        }
    }
    public static float[] logMel(float[] audio) {
        if (audio.length <= FFT / 2 || audio.length > MAX_SAMPLES)
            throw new IllegalArgumentException("Audio length outside frontend contract");
        int frames = audio.length / HOP + 1;
        float[] result = new float[MELS * frames];
        double[] segment = new double[FFT]; float[] power = new float[BINS];
        RealFft400 fft = new RealFft400();
        for (int t = 0; t < frames; t++) {
            for (int n = 0; n < FFT; n++) {
                int index = t * HOP + n - FFT / 2;
                if (index < 0) index = -index;
                if (index >= audio.length) index = 2 * audio.length - 2 - index;
                segment[n] = (float)(audio[index] * WINDOW[n]);
            }
            fft.power(segment, power);
            for (int m = 0; m < MELS; m++) {
                double value = 0;
                for (int k = FIRST[m]; k < LAST[m]; k++) value += (float)(power[k] * FILTER[m][k]);
                result[m * frames + t] = (float)Math.log(Math.max(1e-9, Math.min(1e9, value)));
            }
        }
        return result;
    }

}
