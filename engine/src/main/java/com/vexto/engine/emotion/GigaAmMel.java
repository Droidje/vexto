package com.vexto.engine.emotion;

/** Torchaudio-compatible GigaAM-Emo frontend shared with the standalone Android benchmark. */
public final class GigaAmMel {
    private static final int RATE = 16000, FFT = 400, HOP = 160, MELS = 64, BINS = FFT / 2 + 1;
    private static final int MAX_SAMPLES = 30 * RATE;

    private final double[][] cos = new double[BINS][FFT], sin = new double[BINS][FFT];
    private final double[] window = new double[FFT];
    private final float[][] filter = new float[MELS][BINS];

    public GigaAmMel() {
        for (int n = 0; n < FFT; n++) {
            window[n] = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * n / FFT));
            for (int k = 0; k < BINS; k++) {
                double phase = 2 * Math.PI * k * n / FFT;
                cos[k][n] = Math.cos(phase);
                sin[k][n] = Math.sin(phase);
            }
        }
        float[] hz = new float[MELS + 2];
        float maxMel = (float)(2595 * Math.log10(1 + 8000.0 / 700));
        for (int m = 0; m < hz.length; m++) {
            float mel = maxMel * m / (MELS + 1);
            hz[m] = (float)(700 * (Math.pow(10, mel / 2595.0f) - 1));
        }
        for (int m = 0; m < MELS; m++) for (int k = 0; k < BINS; k++) {
            float freq = (float)RATE * k / FFT;
            filter[m][k] = Math.max(0, Math.min((freq - hz[m]) / (hz[m+1] - hz[m]),
                    (hz[m+2] - freq) / (hz[m+2] - hz[m+1])));
        }
    }

    public static int frames(int samples) { return samples / HOP + 1; }

    /** Power spectrogram, periodic Hann, reflect padding and HTK mel. Output is mel-major [64,T]. */
    public float[] logMel(float[] audio) {
        if (audio.length <= FFT / 2 || audio.length > MAX_SAMPLES)
            throw new IllegalArgumentException("Audio length outside frontend contract");
        int frames = frames(audio.length);
        float[] result = new float[MELS * frames];
        double[] segment = new double[FFT];
        float[] power = new float[BINS];
        for (int t = 0; t < frames; t++) {
            for (int n = 0; n < FFT; n++) {
                int index = t * HOP + n - FFT / 2;
                if (index < 0) index = -index;
                if (index >= audio.length) index = 2 * audio.length - 2 - index;
                segment[n] = (float)(audio[index] * window[n]);
            }
            for (int k = 0; k < BINS; k++) {
                double re = 0, im = 0;
                double[] real = cos[k], imaginary = sin[k];
                for (int n = 0; n < FFT; n++) {
                    re += segment[n] * real[n];
                    im -= segment[n] * imaginary[n];
                }
                power[k] = (float)(re * re + im * im);
            }
            for (int m = 0; m < MELS; m++) {
                double value = 0;
                for (int k = 0; k < BINS; k++) value += power[k] * filter[m][k];
                result[m * frames + t] = (float)Math.log(Math.max(1e-9, Math.min(1e9, value)));
            }
        }
        return result;
    }
}
