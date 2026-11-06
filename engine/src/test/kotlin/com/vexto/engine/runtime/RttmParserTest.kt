package com.vexto.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RttmParserTest {
    @Test fun preservesOverlapAndAcousticLabels() {
        val turns = RttmParser.parse("""SPEAKER recording 1 0.181 5.068 <NA> <NA> speaker_4 <NA> <NA>
SPEAKER recording 1 1.000 0.500 <NA> <NA> speaker_2 <NA> <NA>""")
        assertEquals(listOf("speaker_4", "speaker_2"), turns.map { it.speakerId })
        assertEquals(181L, turns[0].startMs)
        assertEquals(5249L, turns[0].endMs)
        assertEquals(1000L, turns[1].startMs)
    }

    @Test fun rejectsInvalidTimesAndMalformedRows() {
        for ((start, duration) in listOf("NaN" to "1", "0" to "Infinity", "-1" to "1", "1" to "0", "0" to "1e100")) {
            assertThrows(IllegalArgumentException::class.java) {
                RttmParser.parse("SPEAKER r 1 $start $duration <NA> <NA> speaker_1 <NA> <NA>")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { RttmParser.parse("truncated diagnostic") }
        assertEquals(emptyList<NativeSpeakerTurn>(), RttmParser.parse("\n"))
    }
}
