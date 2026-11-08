package com.vexto.engine.runtime

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeDiarizationProviderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun legacyAndCurrentCliKeepBothSpeakersWithoutUnsupportedFlags() = runBlocking {
        val legacy = "Vexto Nemotron: MODEL PCM16_WAV --gpu -1 --preset v3-offline --rttm ID"
        for (modern in listOf(false, true)) {
            val work = temp.newFolder()
            val model = File(work, "model.gguf").apply { writeText("fixture") }
            val audio = File(work, "input.wav").apply { writeText("fixture") }
            var invoked = 0
            var published = 0
            val rows = "SPEAKER recording 1 0.100 1.000 <NA> <NA> speaker_1 <NA> <NA>\n" +
                "SPEAKER recording 1 1.200 0.800 <NA> <NA> speaker_2 <NA> <NA>\n"
            val executor = DiarizationExecutor { args, stdout, _, _, tick ->
                invoked++
                if (args == listOf("--help")) {
                    stdout.writeText(legacy + if (modern) " [--progress PATH] [--threads 2|4]" else "")
                    NativeProcessResult(20, 0)
                } else {
                    // The installed legacy binary rejects either flag before opening a model.
                    assertEquals(modern, "--threads" in args)
                    assertEquals(modern, "--progress" in args)
                    assertEquals(listOf(model.path, audio.path, "--gpu", "-1", "--preset", "v3-offline",
                        "--rttm", "recording"), args.take(8))
                    if (modern) {
                        assertEquals("2", args[args.indexOf("--threads") + 1])
                        File(args[args.indexOf("--progress") + 1]).writeText("# stable_ms=2000\n$rows")
                        requireNotNull(tick).invoke()
                    }
                    stdout.writeText(rows)
                    NativeProcessResult(10, 0)
                }
            }
            val result = NativeDiarizationProvider.run(model, audio, File(work, "turns.rttm"), work,
                executor, 2) { frontier, turns ->
                assertEquals(2000L, frontier)
                assertEquals(2, turns.size)
                published++
            }
            assertEquals(2, invoked)
            assertEquals(listOf("speaker_1", "speaker_2"), result.turns.map { it.speakerId })
            assertEquals(if (modern) 1 else 0, published)
            assertEquals(20L, result.process.peakCombinedRssBytes)
        }
    }
}
