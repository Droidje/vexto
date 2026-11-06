package com.vexto.engine.diarization

import com.vexto.engine.audio.Segment
import com.vexto.engine.model.SpeakerTurn
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class SpeakerClusteringTest {

    private val random = Random(7)

    /** Нормированный вектор рядом с базисной осью [axis]. */
    private fun voice(axis: Int, noise: Float = 0.3f): FloatArray {
        val v = FloatArray(8) { (random.nextFloat() - 0.5f) * noise }
        v[axis] += 1f
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    @Test
    fun `two voices give two clusters`() {
        val embeddings = List(10) { voice(0) } + List(10) { voice(1) }
        val labels = SpeakerClustering.cluster(embeddings, FloatArray(20) { 1f }, 0.35f, null)

        assertEquals(1, labels.take(10).distinct().size)
        assertEquals(1, labels.drop(10).distinct().size)
        assertEquals(2, labels.distinct().size)
    }

    @Test
    fun `known speaker count overrides threshold`() {
        val embeddings = List(6) { voice(0) } + List(6) { voice(1) } + List(6) { voice(2) }
        val labels = SpeakerClustering.cluster(embeddings, FloatArray(18) { 1f }, -1f, 3)

        assertEquals(3, labels.distinct().size)
    }

    @Test
    fun `small cluster joins the closest big one`() {
        val embeddings = List(8) { voice(0) } + List(8) { voice(1) } + listOf(voice(1, 0.9f))
        val labels = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8, 8, 8, 8, 8, 16)

        val merged = SpeakerClustering.absorbSmall(labels, embeddings, FloatArray(17) { 1f }, 5f)

        assertEquals(8, merged[16])
    }

    @Test
    fun `overlapping windows vote per frame`() {
        val windows = listOf(Segment(0, 16_000), Segment(8_000, 24_000), Segment(16_000, 32_000), Segment(48_000, 64_000))
        val turns = SpeakerClustering.turns(windows, intArrayOf(4, 4, 9, 9), 160, 16)

        assertEquals(
            listOf(SpeakerTurn(0, 0, 1500), SpeakerTurn(1, 1500, 2000), SpeakerTurn(1, 3000, 4000)),
            turns,
        )
    }
}
