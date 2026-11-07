package com.vexto.engine.quality

import com.vexto.engine.review.ImmutableAsrCandidate
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
internal data class RefinementPlan(val chunks: Map<String, List<String>>) {
    val ids: List<String> get() = chunks.keys.toList()
}

/** Acoustic boundaries stay fixed. This policy only schedules additional independent ASR candidates. */
internal object AdaptiveRefinement {
    const val Version = "dual-asr-cascade-v2-stream"
    private val lexical = Regex("[\\p{L}\\p{N}]+")
    fun plan(plan: ChunkPlan, giga: Map<String, ImmutableAsrCandidate>,
             whisper: Map<String, ImmutableAsrCandidate>, turns: List<PipelineTurn>, slavic: Boolean): RefinementPlan {
        val reasons = linkedMapOf<String, List<String>>()
        for (chunk in plan.chunks) {
            val g = giga[chunk.id]?.text
            val w = whisper[chunk.id]?.text
            val flags = mutableListOf<String>()
            if (w.isNullOrBlank() || (slavic && g.isNullOrBlank())) flags += "missing_or_empty"
            if (slavic && g != null && w != null) {
                val a = words(g); val b = words(w)
                if (distance(a, b) > 0.18) flags += "lexical_disagreement"
                if (a.filter { it.any(Char::isDigit) } != b.filter { it.any(Char::isDigit) }) flags += "number_disagreement"
            }
            for (text in listOfNotNull(g, w)) {
                val words = words(text)
                if (words.size > 512 || words.size > chunk.durationMs / 90 ||
                    words.windowed(4).any { run -> run.distinct().size == 1 }) flags += "suspicious_rate_or_repetition"
            }
            val local = turns.filter { it.startMs < chunk.startMs + chunk.durationMs && it.endMs > chunk.startMs }
                .sortedBy { it.startMs }
            if (local.indices.any { i -> local.drop(i + 1).takeWhile { it.startMs < local[i].endMs }.any {
                    it.speaker != local[i].speaker && minOf(it.endMs, local[i].endMs) - maxOf(it.startMs, local[i].startMs) >= 200
                } }) flags += "overlapping_speech"
            if (flags.isNotEmpty()) reasons[chunk.id] = flags.distinct()
        }
        return RefinementPlan(reasons)
    }
    private fun words(value: String) = lexical.findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()
    private fun distance(a: List<String>, b: List<String>): Double {
        if (maxOf(a.size, b.size) > 512) return 1.0
        var previous = IntArray(b.size + 1) { it }
        for ((i, word) in a.withIndex()) {
            val next = IntArray(b.size + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(previous[j + 1] + 1, next[j] + 1,
                previous[j] + if (word == b[j]) 0 else 1)
            previous = next
        }
        return previous.last().toDouble() / maxOf(a.size, b.size, 1)
    }
}

/** Heavy outputs supersede only the same source/chunk. All light originals remain on disk. */
internal object CandidateSources {
    fun read(job: java.io.File, slavic: Boolean): List<CandidateRef> =
        listOfNotNull(WorkerStage.GIGAAM.takeIf { slavic }, WorkerStage.WHISPER,
            WorkerStage.GIGAAM_REFINE.takeIf { slavic }, WorkerStage.WHISPER_REFINE).flatMap { stage ->
            runCatching { JobFiles.load<WorkerResult>(stage.resultFile(job)).candidates }.getOrDefault(emptyList())
        }.filter { ref -> runCatching {
            val plan = JobFiles.load<ChunkPlan>(java.io.File(job, "chunks.json"))
            val chunk = plan.chunks.single { it.id == ref.chunkId }
            ImmutableAsrCandidate.load(JobFiles.inside(job, ref.file), ref.sha256).verifyIdentity(
                chunk.id, chunk.inputSha256, com.vexto.engine.review.AsrSource.entries.single { it.wireName == ref.source })
        }.isSuccess }.associateBy { it.chunkId to it.source }.values.toList()
}
