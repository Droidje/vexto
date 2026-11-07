package com.vexto.reflect;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class AudioImport {
    static final long MAX_BYTES = 1L << 30;

    private AudioImport() {}

    static void copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = limit;
        for (int count; (count = input.read(buffer)) != -1; ) {
            if (count > remaining) throw new TooLarge();
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    static final class TooLarge extends IOException {}
}
