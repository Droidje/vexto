package com.vexto.engine.diarization

import com.vexto.engine.audio.PcmWave
import java.io.File
import java.io.RandomAccessFile

/**
 * Long recordings skip sustained silence before Nemotron. The model still sees one
 * stream, so speaker ids stay in that session. Output times are mapped back onto
 * the original sample clock, and a turn that crosses a removed pause is split.
 */
object DiarizationTimeline {
    const val FRAME_SAMPLES = 320
    const val COLLAR_SAMPLES = 4_800
    const val MIN_GAP_SAMPLES = 2 * 16_000
    const val MIN_OMITTED_SAMPLES = 60 * 16_000L
    const val LONG_RECORDING_MS = 300_000L
    private const val SPEECH_MEAN_SQUARE = 1.0e-4

    data class Range(val fromSample: Long, val toSample: Long)
    data class Plan(val sampleCount: Long, val kept: List<Range>, val compacted: Boolean) {
        val omittedSamples: Long get() = sampleCount - kept.sumOf { it.toSample - it.fromSample }
    }

    data class SpeakerInterval(val speakerId: String, val startMs: Long, val endMs: Long)

    fun plan(wav: File): Plan {
        val count = PcmWave.inspect(wav).sampleCount
        val full = Plan(count, if (count == 0L) emptyList() else listOf(Range(0, count)), compacted = false)
        if (count <= LONG_RECORDING_MS * 16) return full
        val kept = keep(count, speechFrames(wav, count))
        val omitted = count - kept.sumOf { it.toSample - it.fromSample }
        if (kept.isNotEmpty() && omitted < MIN_OMITTED_SAMPLES) return full
        return Plan(count, kept, compacted = true)
    }

    /** Speech frames expanded by 300 ms. Gaps shorter than 2 s stay in the model input. */
    internal fun keep(sampleCount: Long, speech: BooleanArray): List<Range> {
        require(sampleCount >= 0)
        val runs = mutableListOf<Range>()
        var frame = 0
        while (frame < speech.size) {
            if (!speech[frame]) { frame++; continue }
            var end = frame
            while (end < speech.size && speech[end]) end++
            val from = (frame.toLong() * FRAME_SAMPLES - COLLAR_SAMPLES).coerceAtLeast(0)
            val to = (end.toLong() * FRAME_SAMPLES + COLLAR_SAMPLES).coerceAtMost(sampleCount)
            if (to > from) runs += Range(from, to)
            frame = end
        }
        if (runs.isEmpty()) return emptyList()
        val merged = mutableListOf(runs.first())
        for (run in runs.drop(1)) {
            val previous = merged.last()
            if (run.fromSample - previous.toSample < MIN_GAP_SAMPLES) {
                merged[merged.lastIndex] = previous.copy(toSample = maxOf(previous.toSample, run.toSample))
            } else merged += run
        }
        return merged
    }

    fun writeCompact(input: File, output: File, plan: Plan) {
        require(plan.compacted && plan.kept.isNotEmpty())
        val info = PcmWave.inspect(input)
        require(info.sampleCount == plan.sampleCount)
        val keptSamples = plan.kept.sumOf { it.toSample - it.fromSample }
        output.parentFile?.mkdirs()
        RandomAccessFile(output, "rw").use { target ->
            target.setLength(0)
            target.write(header(keptSamples * 2))
            RandomAccessFile(input, "r").use { source ->
                for (range in plan.kept) {
                    source.seek(info.dataOffset + range.fromSample * 2)
                    copy(source, target, (range.toSample - range.fromSample) * 2)
                }
            }
            target.fd.sync()
        }
    }

    fun remap(plan: Plan, turns: List<SpeakerInterval>): List<SpeakerInterval> {
        val pieces = mutableListOf<Triple<Long, Long, Long>>()
        var compact = 0L
        for (range in plan.kept) {
            val length = range.toSample - range.fromSample
            pieces += Triple(compact, range.fromSample, length)
            compact += length
        }
        return turns.flatMap { turn ->
            val start = turn.startMs * 16
            val end = turn.endMs * 16
            if (end <= start) return@flatMap emptyList()
            pieces.mapNotNull { (compactFrom, originalFrom, length) ->
                val localFrom = maxOf(start, compactFrom)
                val localTo = minOf(end, compactFrom + length)
                if (localTo <= localFrom) return@mapNotNull null
                val originalStart = originalFrom + (localFrom - compactFrom)
                val originalEnd = originalFrom + (localTo - compactFrom)
                val startMs = originalStart * 1000 / 16_000
                val endMs = (originalEnd * 1000 + 15_999) / 16_000
                if (endMs <= startMs) null else SpeakerInterval(turn.speakerId, startMs, endMs)
            }
        }
    }

    private fun speechFrames(wav: File, sampleCount: Long): BooleanArray {
        val frames = ((sampleCount + FRAME_SAMPLES - 1) / FRAME_SAMPLES).toInt()
        val speech = BooleanArray(frames)
        var sample = 0L
        while (sample < sampleCount) {
            val count = minOf(16_000L * 60, sampleCount - sample).toInt()
            val audio = PcmWave.readFloat(wav, sample, count)
            var index = 0
            while (index < count) {
                val frame = ((sample + index) / FRAME_SAMPLES).toInt()
                val length = minOf(FRAME_SAMPLES, count - index)
                var sum = 0.0
                for (offset in 0 until length) {
                    val value = audio[index + offset].toDouble()
                    sum += value * value
                }
                if (sum / length >= SPEECH_MEAN_SQUARE) speech[frame] = true
                index += length
            }
            sample += count
        }
        return speech
    }

    private fun header(bytes: Long): ByteArray {
        require(bytes in 0..0xffffffffL - 36)
        val buffer = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()); buffer.putInt((bytes + 36).toInt()); buffer.put("WAVEfmt ".toByteArray())
        buffer.putInt(16); buffer.putShort(1); buffer.putShort(1); buffer.putInt(16_000); buffer.putInt(32_000)
        buffer.putShort(2); buffer.putShort(16); buffer.put("data".toByteArray()); buffer.putInt(bytes.toInt())
        return buffer.array()
    }

    private fun copy(input: RandomAccessFile, output: RandomAccessFile, bytes: Long) {
        val buffer = ByteArray(65536)
        var left = bytes
        while (left > 0) {
            val count = minOf(buffer.size.toLong(), left).toInt()
            input.readFully(buffer, 0, count)
            output.write(buffer, 0, count)
            left -= count
        }
    }
}
