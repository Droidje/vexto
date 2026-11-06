package com.vexto.reflect;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceVectorsTest {
    @Test public void normalizedLittleEndianRoundTrip() {
        float[] restored = VoiceVectors.decode(VoiceVectors.encode(new float[]{3,4}), 2);
        assertArrayEquals(new float[]{.6f,.8f}, restored, 1e-6f);
        assertEquals(1, VoiceVectors.cosine(restored, restored), 1e-6);
        assertEquals(-1, VoiceVectors.cosine(new float[]{1,0}, new float[]{-1,0}), 1e-6);
    }
    @Test public void rejectInvalidAndMismatchedVectors() {
        for (float[] values : new float[][]{{0,0},{Float.NaN,1},{Float.POSITIVE_INFINITY},{}}) {
            try { VoiceVectors.normalize(values); fail(); } catch (IllegalArgumentException expected) {}
        }
        try { VoiceVectors.decode(new byte[7],2); fail(); } catch (IllegalArgumentException expected) {}
        try { VoiceVectors.cosine(new float[2],new float[3]); fail(); } catch (IllegalArgumentException expected) {}
    }
}
