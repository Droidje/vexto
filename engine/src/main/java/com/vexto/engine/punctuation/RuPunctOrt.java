package com.vexto.engine.punctuation;

/** Сессия RUPunct на ONNX Runtime, уже упакованном в sherpa-onnx. */
final class RuPunctOrt implements AutoCloseable {
    static { System.loadLibrary("vexto_punct"); }

    private long handle;

    RuPunctOrt(String model, int threads) {
        handle = nativeOpen(model, threads);
        if (handle == 0) throw new IllegalStateException("Сессия RUPunct не создана");
    }

    int[] labels(int[] tokenIds) {
        if (handle == 0) throw new IllegalStateException("Сессия RUPunct закрыта");
        int[] result = nativeLabels(handle, tokenIds);
        if (result == null) throw new IllegalStateException("RUPunct не вернул метки");
        return result;
    }

    @Override public void close() {
        nativeClose(handle);
        handle = 0;
    }

    private static native long nativeOpen(String model, int threads);
    private static native int[] nativeLabels(long handle, int[] tokenIds);
    private static native void nativeClose(long handle);
}
