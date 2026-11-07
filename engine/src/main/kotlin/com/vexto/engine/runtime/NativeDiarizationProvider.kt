package com.vexto.engine.runtime

import java.io.File
import kotlin.math.roundToLong

data class NativeSpeakerTurn(val speakerId: String, val startMs: Long, val endMs: Long)
data class NativeDiarizationResult(val turns: List<NativeSpeakerTurn>, val process: NativeProcessResult)

internal fun interface DiarizationExecutor {
    suspend fun run(args: List<String>, stdout: File, stderr: File, timeoutMs: Long,
                    onTick: (() -> Unit)?): NativeProcessResult
}

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
                    runner: NativeProcessRunner, threads: Int = 4,
                    onStable: ((Long, List<NativeSpeakerTurn>) -> Unit)? = null): NativeDiarizationResult =
        run(model, inputWav, outRttm, workDir, DiarizationExecutor { args, stdout, stderr, timeout, tick ->
            runner.run("libvexto_nemotron_exec.so", args, workDir, stdout, stderr, timeout, tick)
        }, threads, onStable)

    internal suspend fun run(model: File, inputWav: File, outRttm: File, workDir: File,
                             executor: DiarizationExecutor, threads: Int = 4,
                             onStable: ((Long, List<NativeSpeakerTurn>) -> Unit)? = null): NativeDiarizationResult {
        require(model.isFile && inputWav.isFile)
        require(threads == 2 || threads == 4)
        require(!outRttm.exists()) { "Refusing stale RTTM output" }
        // Developer APKs may reuse an older packaged native runtime. Never send new flags
        // before checking its contract: legacy CLI exits 1 before even opening the model.
        val help = File(workDir, "native-help.stdout")
        val probe = executor.run(listOf("--help"), help, File(workDir, "native-help.stderr"), 15_000, null)
        val contract = help.readText(Charsets.UTF_8)
        require(contract.startsWith("Vexto Nemotron:")) { "Unrecognized packaged Nemotron CLI" }
        val threadArgs = if (contract.contains("[--threads 2|4]")) listOf("--threads", threads.toString()) else emptyList()
        val streaming = onStable != null && contract.contains("[--progress PATH]")
        val progress = File(workDir, "stable-prefix.rttm")
        var frontier = -1L
        val args = listOf(model.absolutePath, inputWav.absolutePath,
            "--gpu", "-1", "--preset", "v3-offline", "--rttm", "recording") + threadArgs +
            if (streaming) listOf("--progress", progress.absolutePath) else emptyList()
        val result = executor.run(args,
            outRttm, File(workDir, "nemotron.stderr.log"), 30 * 60 * 1000L) {
            if (streaming && progress.isFile && progress.length() <= 4_000_000) runCatching {
                val text = progress.readText()
                val next = text.substringBefore('\n').removePrefix("# stable_ms=").toLong()
                if (next > frontier) {
                    requireNotNull(onStable)(next, RttmParser.parse(text.substringAfter('\n')))
                    frontier = next
                }
            }
        }
        return NativeDiarizationResult(RttmParser.parse(outRttm.readText(Charsets.UTF_8)), result.copy(
            peakCombinedRssBytes = maxOf(probe.peakCombinedRssBytes, result.peakCombinedRssBytes),
            elapsedMs = probe.elapsedMs + result.elapsedMs))
    }
}
