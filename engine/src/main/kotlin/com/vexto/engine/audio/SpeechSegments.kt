package com.vexto.engine.audio

import com.vexto.engine.model.SampleRate

/** Отрезок записи в отсчётах, [start, end). */
data class Segment(val start: Long, val end: Long)

internal object SpeechSegments {

    /**
     * Расширяет сегменты VAD на [padSamples] с каждой стороны, чтобы ASR слышал начало и конец
     * слов. Соседние сегменты после расширения не пересекаются.
     */
    fun pad(segments: List<Segment>, padSamples: Long, total: Long): List<Segment> {
        val out = mutableListOf<Segment>()
        for (s in segments) {
            var start = maxOf(0, s.start - padSamples)
            val end = minOf(total, s.end + padSamples)
            if (out.isNotEmpty() && start < out.last().end) start = out.last().end
            if (end > start) out += Segment(start, end)
        }
        return out
    }

    /**
     * VAD не всегда соблюдает maxSpeechDuration (например, в шуме). Длинный сегмент режется в
     * самом тихом кадре 50 мс второй половины каждого окна [maxSamples].
     */
    fun splitLong(segment: Segment, maxSamples: Int, read: (Long, Long) -> FloatArray): List<Segment> {
        val frame = SampleRate / 20
        val out = mutableListOf<Segment>()
        var start = segment.start
        while (segment.end - start > maxSamples) {
            val lo = start + maxSamples / 2
            val samples = read(lo, start + maxSamples)
            val frames = samples.size / frame
            var best = 0
            var bestEnergy = Double.MAX_VALUE
            for (f in 0 until frames) {
                var e = 0.0
                for (i in f * frame until (f + 1) * frame) e += samples[i] * samples[i]
                if (e < bestEnergy) {
                    bestEnergy = e
                    best = f
                }
            }
            val cut = lo + best * frame + frame / 2
            out += Segment(start, cut)
            start = cut
        }
        out += Segment(start, segment.end)
        return out
    }
}
