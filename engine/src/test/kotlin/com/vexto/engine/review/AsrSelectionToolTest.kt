package com.vexto.engine.review

import com.vexto.engine.model.Word
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AsrSelectionToolTest {
    @get:Rule val temp = TemporaryFolder()
    private fun candidate(source: AsrSource, text: String = source.wireName) = ImmutableAsrCandidate.create(
        "chunk01", source, "a".repeat(64), text, listOf(AsrToken(text, 0, 100)),
        listOf(Word(text, 0, 100)), temp.root.resolve(source.wireName + ".json"))

    @Test fun `valid local tool copies whole candidate identity and immutable bytes`() {
        val giga = candidate(AsrSource.GIGAAM, "Один вариант.")
        val whisper = candidate(AsrSource.WHISPER, " Другой вариант! ")
        val before = whisper.verifiedBytes()
        val result = AsrSelectionTool().execute(
            """{"tool":"select_asr_variant","chunk_id":"chunk01","source":"whisper"}[end of text]""",
            AsrCandidatePair("chunk01", giga.inputSha256, giga, whisper), temp.root.resolve("selected.json"))
        val selected = (result as AsrToolResult.Selected).selection
        assertEquals(AsrSelectionStatus.SELECTED_BY_LLM, selected.status)
        assertSame(whisper, selected.displayCandidate)
        assertArrayEquals(before, selected.selectedArtifact.readBytes())
        assertArrayEquals(before, whisper.verifiedBytes())
        assertEquals(whisper.tokens, selected.displayCandidate.tokens)
        assertEquals(whisper.words, selected.displayCandidate.words)
        assertTrue(selected.audit.selectedByLlm)
        assertFalse(selected.audit.qualityValidated)
    }

    @Test fun `duplicates extras free text nested values wrong chunks never execute tool`() {
        val giga = candidate(AsrSource.GIGAAM)
        val whisper = candidate(AsrSource.WHISPER)
        val pair = AsrCandidatePair("chunk01", giga.inputSha256, giga, whisper)
        val valid = """{"tool":"select_asr_variant","chunk_id":"chunk01","source":"whisper"}"""
        val invalid = listOf("<think>reason</think>" + valid, "```json\n$valid\n```", valid + valid,
            valid.dropLast(1) + ",\"text\":\"rewritten\"}",
            valid.dropLast(1) + ",\"source\":\"gigaam\"}",
            valid.replace("\"whisper\"", "{\"source\":\"whisper\"}"),
            valid.replace("chunk01", "other"), valid.replace("whisper", "unknown"))
        invalid.forEachIndexed { index, response ->
            val output = temp.root.resolve("invalid$index.json")
            assertTrue(AsrSelectionTool().execute(response, pair, output) is AsrToolResult.Rejected)
            assertFalse(output.exists())
        }
    }

    @Test fun `invalid tool is unresolved and display policy is not a quality preference`() {
        val giga = candidate(AsrSource.GIGAAM, "")
        val whisper = candidate(AsrSource.WHISPER, "Есть речь.")
        val result = AsrSelectionTool().fallback(AsrCandidatePair("chunk01", giga.inputSha256, giga, whisper),
            temp.root.resolve("display.json"), "invalid_tool_json")
        assertEquals(AsrSelectionStatus.UNRESOLVED, result.status)
        assertSame(giga, result.displayCandidate)
        assertEquals("", result.displayCandidate.text)
        assertFalse(result.audit.selectedByLlm)
        assertEquals("invalid_tool_json", result.audit.reason)
        assertFalse(result.audit.qualityValidated)
        assertArrayEquals(giga.verifiedBytes(), result.selectedArtifact.readBytes())
        assertEquals(giga.artifactSha256, result.audit.candidateSha256)
        assertEquals(giga.artifactSha256, result.audit.selectedSha256)
        assertEquals(whisper.artifactSha256, sha256(whisper.verifiedBytes()))
    }

    @Test fun `sole available empty candidate is preserved without a model verdict`() {
        val whisper = candidate(AsrSource.WHISPER, "")
        val result = AsrSelectionTool().fallback(AsrCandidatePair("chunk01", whisper.inputSha256, null, whisper),
            temp.root.resolve("display.json"), "one_available_candidate")
        assertEquals(AsrSelectionStatus.ONE_AVAILABLE, result.status)
        assertSame(whisper, result.displayCandidate)
        assertFalse(result.audit.selectedByLlm)
        assertFalse(result.audit.qualityValidated)
        assertArrayEquals(whisper.verifiedBytes(), result.selectedArtifact.readBytes())
        assertEquals(whisper.artifactSha256, result.audit.selectedSha256)
    }

    @Test fun `mismatched chunk input hash cannot be selected`() {
        val giga = candidate(AsrSource.GIGAAM)
        val result = AsrSelectionTool().execute(
            """{"tool":"select_asr_variant","chunk_id":"chunk01","source":"gigaam"}""",
            AsrCandidatePair("chunk01", "b".repeat(64), giga, null), temp.root.resolve("selected.json"))
        assertEquals(AsrToolResult.Rejected("candidate_identity_or_hash_mismatch"), result)
        assertFalse(temp.root.resolve("selected.json").exists())
    }
}
