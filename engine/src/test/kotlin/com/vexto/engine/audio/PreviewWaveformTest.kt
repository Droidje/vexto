package com.vexto.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewWaveformTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun quietAndLoudSectionsKeepTheirPlacesInThePreview() {
        val frameSize = PcmAudio.FrameSize
        val samples = FloatArray(frameSize * 32)
        samples.fill(.25f, frameSize * 5, frameSize * 6)
        samples.fill(1f, frameSize * 23, frameSize * 24)

        PcmWriter(temp.newFile()).apply { write(samples, samples.size) }.finish().use { pcm ->
            val bars = pcm.previewWaveform()
            assertEquals(32, bars.size)
            assertEquals(2, bars[4])
            assertEquals(7, bars[5])
            assertEquals(22, bars[23])
            assertEquals(2, bars[24])
        }
    }
}
