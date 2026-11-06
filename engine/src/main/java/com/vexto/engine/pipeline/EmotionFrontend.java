package com.vexto.engine.pipeline;

/** GigaAM official frontend; parity checked against Torchaudio2.9.1. Bounded to8s. */
public final class EmotionFrontend {
    private static final int RATE=16000, FFT=400, HOP=160, MELS=64, MAX_SAMPLES=8*RATE;
    private EmotionFrontend() {}
    public static float[] logMel(float[] audio) {
        if (audio.length <= FFT / 2 || audio.length > MAX_SAMPLES)
            throw new IllegalArgumentException("Audio length outside frontend contract");
        int bins = FFT / 2 + 1, frames = audio.length / HOP + 1;
        double[][] cos = new double[bins][FFT], sin = new double[bins][FFT];
        double[] window = new double[FFT];
        for (int n = 0; n < FFT; n++) {
            window[n] = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * n / FFT));
            for (int k = 0; k < bins; k++) {
                double phase = 2 * Math.PI * k * n / FFT;
                cos[k][n] = Math.cos(phase); sin[k][n] = Math.sin(phase);
            }
        }
        float[] hz = new float[MELS + 2];
        float maxMel = (float)(2595 * Math.log10(1 + 8000.0 / 700));
        for (int m = 0; m < hz.length; m++) {
            float mel = maxMel * m / (MELS + 1);
            hz[m] = (float)(700 * (Math.pow(10, mel / 2595.0f) - 1));
        }
        float[][] filter = new float[MELS][bins];
        for (int m = 0; m < MELS; m++) for (int k = 0; k < bins; k++) {
            float freq = (float)RATE * k / FFT;
            filter[m][k] = Math.max(0, Math.min((freq - hz[m]) / (hz[m+1] - hz[m]),
                (hz[m+2] - freq) / (hz[m+2] - hz[m+1])));
        }
        float[] result = new float[MELS * frames];
        double[] segment = new double[FFT]; float[] power = new float[bins];
        for (int t = 0; t < frames; t++) {
            for (int n = 0; n < FFT; n++) {
                int index = t * HOP + n - FFT / 2;
                if (index < 0) index = -index;
                if (index >= audio.length) index = 2 * audio.length - 2 - index;
                segment[n] = (float)(audio[index] * window[n]);
            }
            for (int k = 0; k < bins; k++) {
                double re = 0, im = 0;
                for (int n = 0; n < FFT; n++) {
                    re += segment[n] * cos[k][n]; im -= segment[n] * sin[k][n];
                }
                power[k] = (float)(re * re + im * im);
            }
            for (int m = 0; m < MELS; m++) {
                double value = 0;
                for (int k = 0; k < bins; k++) value += (float)(power[k] * filter[m][k]);
                result[m * frames + t] = (float)Math.log(Math.max(1e-9, Math.min(1e9, value)));
            }
        }
        return result;
    }

}
