package com.vexto.engine.quality

import kotlinx.serialization.Serializable

/** Coverage and confirmed LLM decisions are different from a stage merely returning. */
@Serializable
data class PipelineCoverage(
    val expectedChunks: Int,
    val gigaamChunks: Int,
    val whisperChunks: Int,
    val selectedChunks: Int,
    val llmSelectedChunks: Int,
    val unresolvedChunks: Int,
    val equivalentChunks: Int = 0,
) {
    init {
        require(expectedChunks >= 0)
        require(listOf(gigaamChunks, whisperChunks, selectedChunks, llmSelectedChunks, unresolvedChunks, equivalentChunks)
            .all { it in 0..expectedChunks })
        require(llmSelectedChunks + equivalentChunks + unresolvedChunks <= selectedChunks)
    }
    val complete: Boolean get() = selectedChunks == expectedChunks
    val selectionResolved: Boolean get() = complete && unresolvedChunks == 0
    fun fullProfileCompleted(stagesSucceeded: Boolean, warnings: List<String>, withinBudget: Boolean): Boolean =
        expectedChunks > 0 && complete && selectionResolved && gigaamChunks == expectedChunks &&
            whisperChunks == expectedChunks && llmSelectedChunks + equivalentChunks == expectedChunks && stagesSucceeded && withinBudget &&
            warnings.none { it.contains("_FAILED:") || it.startsWith("ASR_MISSING:") || it.startsWith("INVALID_") }
}

@Serializable
data class PipelineReport(
    val schemaVersion: Int = 3,
    val audioMs: Long,
    val processingMs: Long,
    val modelProfile: String,
    val coverage: PipelineCoverage,
    val complete: Boolean,
    val fullProfileCompleted: Boolean,
    val selectionResolved: Boolean,
    val peakRssBytes: Long,
    val reserveBytes: Long,
    val budgetBytes: Long,
    val stageMs: Map<String, Long>,
    val stages: List<WorkerResult>,
    val transcriptSha256: String,
    val metadataSha256: String,
    val warnings: List<String>,
    val modelVerificationReused: Boolean = false,
    val processingProfile: ProcessingProfile? = null,
    val skippedSilenceMs: Long = 0,
)
