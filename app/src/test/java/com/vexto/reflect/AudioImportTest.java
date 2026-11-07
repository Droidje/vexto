package com.vexto.reflect;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class AudioImportTest {
    @Test public void acceptsExactLimit() throws Exception {
        byte[] source = new byte[65_537];
        source[65_536] = 42;
        var output = new ByteArrayOutputStream();
        AudioImport.copy(new ByteArrayInputStream(source), output, source.length);
        assertArrayEquals(source, output.toByteArray());
    }

    @Test public void neverWritesPastLimit() {
        var output = new ByteArrayOutputStream();
        assertThrows(AudioImport.TooLarge.class, () ->
                AudioImport.copy(new ByteArrayInputStream(new byte[65_538]), output, 65_537));
        assertTrue(output.size() <= 65_537);
    }
}
