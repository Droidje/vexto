package com.vexto.engine.emotion;

/** Narrow JNI wrapper over the ONNX Runtime already bundled with the speech engine. */
public final class GigaAmOrt implements AutoCloseable {
    static { System.loadLibrary("vexto_emotion"); }

    private long handle;

    public GigaAmOrt(String model, int threads) {
        handle = nativeOpen(model, threads);
    }

    float[] predict(float[] features, int frames) {
        if (handle == 0) throw new IllegalStateException("Emotion session closed");
        return nativeRun(handle, features, frames);
    }

    @Override public void close() {
        nativeClose(handle);
        handle = 0;
    }

    private static native long nativeOpen(String model, int threads);
    private static native float[] nativeRun(long handle, float[] features, int frames);
    private static native void nativeClose(long handle);
}
