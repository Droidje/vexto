package com.vexto.engine.review

import com.vexto.engine.model.Word
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImmutableAsrCandidateTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `snapshot keeps exact text and defensively copies tokens words`() {
        val tokens = mutableListOf(AsrToken(" слово", 0, 100, 7))
        val words = mutableListOf(Word("слово", 0, 100))
        val value = ImmutableAsrCandidate.create("chunk01", AsrSource.GIGAAM, "a".repeat(64),
            " слово! ", tokens, words, temp.root.resolve("raw.json"))
        tokens.clear(); words.clear()
        assertEquals(" слово! ", value.text)
        assertEquals(listOf(AsrToken(" слово", 0, 100, 7)), value.tokens)
        assertEquals(listOf(Word("слово", 0, 100)), value.words)
        val loaded = ImmutableAsrCandidate.load(value.artifactFile, value.artifactSha256)
        assertEquals(value.text, loaded.text)
        assertEquals(value.tokens, loaded.tokens)
        assertEquals(value.words, loaded.words)
        assertArrayEquals(value.verifiedBytes(), loaded.verifiedBytes())
        try { (value.tokens as MutableList<AsrToken>).clear(); fail("mutable candidate tokens") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun `trusted journal hash detects mutation and wrong identity`() {
        val value = ImmutableAsrCandidate.create("chunk01", AsrSource.WHISPER, "a".repeat(64),
            "", emptyList(), emptyList(), temp.root.resolve("raw.json"))
        try { value.verifyIdentity("other", value.inputSha256, value.source); fail("wrong chunk accepted") }
        catch (_: IllegalStateException) { }
        value.artifactFile.appendText(" ")
        try { value.verifiedBytes(); fail("changed artifact accepted") } catch (_: IllegalStateException) { }
        try { ImmutableAsrCandidate.load(value.artifactFile, value.artifactSha256); fail("journal hash ignored") }
        catch (_: IllegalStateException) { }
    }
}
