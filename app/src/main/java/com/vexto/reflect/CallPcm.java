package com.vexto.reflect;

import java.io.IOException;
import java.io.RandomAccessFile;

/** PCM16 mixing and a crash-repairable mono WAV container. */
final class CallPcm {
    static final int SAMPLE_RATE = 48_000;
    static final int HEADER_BYTES = 44;
    static final long MAX_DATA_BYTES = 0xffff_ffffL - 36;

    private CallPcm() {}

    static int mix(short[] microphone, short[] playback, byte[] output) {
        if (microphone.length != playback.length || output.length != microphone.length * 2) {
            throw new IllegalArgumentException("PCM blocks must have matching lengths");
        }
        int peak = 0;
        for (int index = 0; index < microphone.length; index++) {
            int sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                    (int) microphone[index] + playback[index]));
            output[index * 2] = (byte) sample;
            output[index * 2 + 1] = (byte) (sample >>> 8);
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    static long repair(RandomAccessFile output) throws IOException {
        long bytes = Math.max(0, output.length() - HEADER_BYTES) & ~1L;
        if (bytes > MAX_DATA_BYTES) throw new IOException("WAV exceeds RIFF size limit");
        output.setLength(HEADER_BYTES + bytes);
        header(output, bytes);
        output.getFD().sync();
        return bytes;
    }

    static void header(RandomAccessFile output, long dataBytes) throws IOException {
        long position = output.getFilePointer();
        output.seek(0);
        output.writeBytes("RIFF");
        littleInt(output, 36 + dataBytes);
        output.writeBytes("WAVEfmt ");
        littleInt(output, 16);
        littleShort(output, 1);
        littleShort(output, 1);
        littleInt(output, SAMPLE_RATE);
        littleInt(output, SAMPLE_RATE * 2);
        littleShort(output, 2);
        littleShort(output, 16);
        output.writeBytes("data");
        littleInt(output, dataBytes);
        output.seek(Math.max(HEADER_BYTES, position));
    }

    private static void littleInt(RandomAccessFile output, long value) throws IOException {
        for (int byteIndex = 0; byteIndex < 4; byteIndex++) output.write((int) (value >>> (byteIndex * 8)) & 255);
    }

    private static void littleShort(RandomAccessFile output, int value) throws IOException {
        output.write(value & 255);
        output.write(value >>> 8 & 255);
    }
}
