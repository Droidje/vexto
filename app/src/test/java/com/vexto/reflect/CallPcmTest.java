package com.vexto.reflect;

import static org.junit.Assert.assertEquals;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import org.junit.Test;

public class CallPcmTest {
    @Test public void mixesBothInputsWithoutIntegerWraparound() {
        byte[] mixed = new byte[8];
        assertEquals(32768, CallPcm.mix(new short[]{1000, 30000, -30000, 0},
                new short[]{2000, 30000, -30000, 900}, mixed));
        assertEquals(3000, sample(mixed, 0));
        assertEquals(32767, sample(mixed, 1));
        assertEquals(-32768, sample(mixed, 2));
        assertEquals(900, sample(mixed, 3));
    }

    @Test public void recoversLengthFromWrittenSamplesAfterAnInterruptedHeaderUpdate() throws Exception {
        File file = Files.createTempFile("vexto-call", ".wav").toFile();
        try (var wav = new RandomAccessFile(file, "rw")) {
            CallPcm.header(wav, 0);
            wav.write(new byte[1921]);
            assertEquals(1920, CallPcm.repair(wav));
            assertEquals(1964, wav.length());
            wav.seek(40);
            assertEquals(1920, Integer.reverseBytes(wav.readInt()));
            wav.seek(4);
            assertEquals(1956, Integer.reverseBytes(wav.readInt()));
        } finally { Files.deleteIfExists(file.toPath()); }
    }

    private static short sample(byte[] pcm, int index) {
        return (short) ((pcm[index * 2] & 255) | (pcm[index * 2 + 1] << 8));
    }
}
