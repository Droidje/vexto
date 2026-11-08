package com.vexto.engine.quality

import com.vexto.engine.model.Transcript
import com.vexto.engine.review.AsrSource
import com.vexto.engine.review.ImmutableAsrCandidate
import java.io.File

/** Same immutable text choice and acoustic placement as the final transcript, without voice/emotion metadata. */
internal object SpeakerNamingInput {
    fun create(job: File, plan: ChunkPlan, refs: List<CandidateRef>, choices: List<ChoiceRef>): Transcript {
        val chunks = plan.chunks.associateBy { it.id }
        fun load(ref: CandidateRef): ImmutableAsrCandidate? = runCatching {
            val chunk = chunks.getValue(ref.chunkId)
            ImmutableAsrCandidate.load(JobFiles.inside(job, ref.file), ref.sha256).also {
                it.verifyIdentity(chunk.id, chunk.inputSha256, AsrSource.entries.single { source -> source.wireName == ref.source })
            }
        }.getOrNull()
        val display = choices.mapNotNull { load(it.candidate) }.associateBy { it.chunkId }
        val turns = JobFiles.load<List<PipelineTurn>>(File(job, "turns.json"))
        return AcousticTurnTranscript.place(CandidateTranscriptAssembler().assemble(plan, display, turns).transcript, turns)
    }
}
