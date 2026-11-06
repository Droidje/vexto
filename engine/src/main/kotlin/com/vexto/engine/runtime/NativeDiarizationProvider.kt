package com.vexto.engine.runtime

import java.io.File
import kotlin.math.roundToLong

data class NativeSpeakerTurn(val speakerId: String, val startMs: Long, val endMs: Long)
data class NativeDiarizationResult(val turns: List<NativeSpeakerTurn>, val process: NativeProcessResult)

/** Independent speaker rows are retained, including simultaneous/overlapping speech. */
object RttmParser {
    fun parse(text: String): List<NativeSpeakerTurn> = text.lineSequence().filter { it.isNotBlank() }.map { line ->
        val fields = line.trim().split(Regex("\\s+"))
        require(fields.size == 10 && fields[0] == "SPEAKER") { "Malformed native RTTM" }
        val start = fields[3].toDouble()
        val duration = fields[4].toDouble()
        require(start.isFinite() && duration.isFinite() && start >= 0 && duration > 0 && (start + duration).isFinite())
        require((start + duration) * 1000 <= Long.MAX_VALUE.toDouble())
        require(fields[7].isNotBlank() && fields[7] != "<NA>")
        val from = (start * 1000).roundToLong()
        val to = ((start + duration) * 1000).roundToLong()
        require(from >= 0 && to > from)
        NativeSpeakerTurn(fields[7], from, to)
    }.toList()
}

object NativeDiarizationProvider {
    suspend fun run(model: File, inputWav: File, outRttm: File, workDir: File,
                    runner: NativeProcessRunner): NativeDiarizationResult {
        require(model.isFile && inputWav.isFile)
        require(!outRttm.exists()) { "Refusing stale RTTM output" }
        val result = runner.run("libvexto_nemotron_exec.so", listOf(model.absolutePath, inputWav.absolutePath,
            "--gpu", "-1", "--preset", "v3-offline", "--rttm", "recording"), workDir,
            outRttm, File(workDir, "nemotron.stderr.log"), 30 * 60 * 1000L)
        return NativeDiarizationResult(RttmParser.parse(outRttm.readText(Charsets.UTF_8)), result)
    }
}
