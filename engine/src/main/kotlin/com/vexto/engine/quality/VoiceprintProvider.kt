package com.vexto.engine.quality

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.vexto.engine.audio.PcmAudio
import com.vexto.engine.audio.PcmWave
import com.vexto.engine.diarization.SpeakerSignatures
import com.vexto.engine.model.SampleRate
import com.vexto.engine.model.SpeakerTurn
import com.vexto.engine.model.VoiceEmbedding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Voice signatures for the existing local profile matcher; Nemotron labels stay authoritative.
 * Reuses dev's sherpa frontend, PcmAudio AGC and SpeakerSignatures centroid without clustering.
 */
object VoiceprintProvider {
    suspend fun run(model: File, inputWav: File, turns: List<PipelineTurn>, threads: Int = 4): Map<Int, VoiceEmbedding> {
        require(model.isFile && threads in 1..4)
        val info = PcmWave.inspect(inputWav)
        val clean = cleanTurns(turns, info.sampleCount / 16)
        if (clean.isEmpty()) return emptyMap()
        val modelId = "sherpa-2.0.1:pcm16-16k-agc-v1:l2-centroid-v1:" + JobFiles.sha256(model)
        originalPcm(inputWav).use { audio ->
            val windows = windows(clean)
            if (windows.isEmpty()) return emptyMap()
            val embeddings = ArrayList<FloatArray>(windows.size)
            val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(
                model = model.absolutePath, numThreads = threads))
            try {
                for (window in windows) {
                    currentCoroutineContext().ensureActive()
                    val stream = extractor.createStream()
                    try {
                        stream.acceptWaveform(audio.read(window.from, window.to, agc = true), SampleRate)
                        stream.inputFinished()
                        val vector = extractor.compute(stream)
                        var norm = 0f
                        for (value in vector) norm += value * value
                        val scale = 1f / (sqrt(norm) + 1e-9f)
                        embeddings += FloatArray(vector.size) { vector[it] * scale }
                    } finally { stream.release() }
                }
            } finally { extractor.release() }
            return SpeakerSignatures.build(modelId, embeddings,
                FloatArray(windows.size) { (windows[it].to - windows[it].from).toFloat() / SampleRate },
                IntArray(windows.size) { windows[it].speaker },
                clean.map { SpeakerTurn(it.speaker, it.startMs, it.endMs) })
        }
    }

    /** Never create a voiceprint from simultaneous different speakers; duplicate same-ID rows are harmless. */
    internal fun cleanTurns(turns: List<PipelineTurn>, durationMs: Long): List<PipelineTurn> {
        data class Event(val at: Long, val speaker: Int, val delta: Int)
        val events = turns.flatMap {
            require(it.speaker >= 0 && it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= durationMs)
            listOf(Event(it.startMs, it.speaker, 1), Event(it.endMs, it.speaker, -1))
        }.sortedBy { it.at }
        val active = mutableMapOf<Int, Int>()
        val clean = mutableListOf<PipelineTurn>()
        var previous = 0L
        for ((at, changes) in events.groupBy { it.at }) {
            if (at > previous && active.size == 1) {
                val speaker = active.keys.single()
                val last = clean.lastOrNull()
                if (last != null && last.speaker == speaker && last.endMs == previous) {
                    clean[clean.lastIndex] = last.copy(endMs = at)
                } else clean += PipelineTurn(speaker, previous, at)
            }
            for (event in changes) {
                val count = active.getOrDefault(event.speaker, 0) + event.delta
                if (count == 0) active.remove(event.speaker) else active[event.speaker] = count
            }
            previous = at
        }
        return clean
    }

    private data class Window(val speaker: Int, val from: Long, val to: Long)
    private fun windows(turns: List<PipelineTurn>): List<Window> {
        val usable = turns.filter { it.endMs - it.startMs >= 300 }
        val hop = maxOf(8000L, usable.sumOf { (it.endMs - it.startMs) * 16 } / 2500)
        val windows = mutableListOf<Window>()
        for (turn in usable) {
            val from = turn.startMs * 16
            val to = turn.endMs * 16
            if (to - from <= SampleRate * 3 / 2) windows += Window(turn.speaker, from, to)
            else {
                var start = from
                while (start + SampleRate < to) { windows += Window(turn.speaker, start, start + SampleRate); start += hop }
                windows += Window(turn.speaker, to - SampleRate, to)
            }
        }
        // Spread clean samples over each person's entire appearance, not only their introduction.
        return windows.groupBy { it.speaker }.values.flatMap { all ->
            if (all.size <= 32) all else (0 until 32).map { index ->
                all[(index.toLong() * (all.size - 1) / 31).toInt()]
            }
        }
    }

    /** Scan only 10ms energies, then borrow the original PCM bytes through PcmAudio's unchanged AGC. */
    private suspend fun originalPcm(wav: File): PcmAudio {
        val info = PcmWave.inspect(wav)
        val energies = FloatArray(((info.sampleCount + PcmAudio.FrameSize - 1) / PcmAudio.FrameSize).toInt())
        RandomAccessFile(wav, "r").use { input ->
            input.seek(info.dataOffset)
            var left = info.sampleCount * 2
            val bytes = ByteArray(65536)
            var frame = 0; var fill = 0; var sum = 0.0
            while (left > 0) {
                currentCoroutineContext().ensureActive()
                val count = minOf(bytes.size.toLong(), left).toInt()
                input.readFully(bytes, 0, count)
                val shorts = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (shorts.hasRemaining()) {
                    val value = shorts.get() / 32768f
                    sum += value * value
                    if (++fill == PcmAudio.FrameSize) { energies[frame++] = (sum / fill).toFloat(); sum = 0.0; fill = 0 }
                }
                left -= count
            }
            if (fill > 0) energies[frame] = (sum / fill).toFloat()
        }
        return PcmAudio.borrowedWav(wav, info.sampleCount, energies, info.dataOffset)
    }
}
