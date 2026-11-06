package com.vexto.engine.diarization

import com.vexto.engine.audio.Segment
import com.vexto.engine.model.SpeakerTurn

/**
 * Кластеризация окон по эмбеддингам спикера и сборка интервалов речи по кадрам 10 мс.
 * Эмбеддинги на входе нормированы, сходство = косинус.
 */
internal object SpeakerClustering {

    /**
     * Агломеративная кластеризация, средняя связь с весами-длительностями окон. Сливает самую
     * похожую пару, пока сходство не ниже [threshold] или, если задано [speakerCount], пока
     * кластеров больше нужного. Возвращает номер кластера для каждого окна.
     */
    fun cluster(embeddings: List<FloatArray>, weights: FloatArray, threshold: Float, speakerCount: Int?): IntArray {
        val n = embeddings.size
        val labels = IntArray(n) { it }
        if (n < 2) return labels
        val sim = FloatArray(n * n)
        for (i in 0 until n) for (j in i + 1 until n) {
            val s = dot(embeddings[i], embeddings[j])
            sim[i * n + j] = s
            sim[j * n + i] = s
        }
        val size = weights.copyOf()
        val active = BooleanArray(n) { true }
        var clusters = n
        val best = IntArray(n)
        val bestSim = FloatArray(n)

        fun refresh(i: Int) {
            best[i] = -1
            bestSim[i] = Float.NEGATIVE_INFINITY
            for (k in 0 until n) {
                if (k != i && active[k] && sim[i * n + k] > bestSim[i]) {
                    best[i] = k
                    bestSim[i] = sim[i * n + k]
                }
            }
        }
        for (i in 0 until n) refresh(i)

        while (clusters > 1) {
            var i = -1
            for (k in 0 until n) if (active[k] && (i < 0 || bestSim[k] > bestSim[i])) i = k
            if (speakerCount != null) {
                if (clusters <= speakerCount) break
            } else if (bestSim[i] < threshold) {
                break
            }
            val j = best[i]
            val total = size[i] + size[j]
            for (k in 0 until n) {
                if (!active[k] || k == i || k == j) continue
                val s = (sim[i * n + k] * size[i] + sim[j * n + k] * size[j]) / total
                sim[i * n + k] = s
                sim[k * n + i] = s
            }
            size[i] = total
            active[j] = false
            clusters--
            for (k in 0 until n) if (labels[k] == j) labels[k] = i
            refresh(i)
            for (k in 0 until n) {
                if (!active[k] || k == i) continue
                if (best[k] == i || best[k] == j) {
                    refresh(k)
                } else if (sim[k * n + i] > bestSim[k]) {
                    best[k] = i
                    bestSim[k] = sim[k * n + i]
                }
            }
        }
        return labels
    }

    /** Кластеры с суммарной речью меньше [minWeight] вливаются в самый похожий крупный по центроидам. */
    fun absorbSmall(labels: IntArray, embeddings: List<FloatArray>, weights: FloatArray, minWeight: Float): IntArray {
        val ids = labels.distinct()
        val total = ids.associateWith { id -> labels.indices.filter { labels[it] == id }.sumOf { weights[it].toDouble() } }
        val big = ids.filter { total.getValue(it) >= minWeight }.ifEmpty { listOf(ids.maxBy { total.getValue(it) }) }
        val centroid = ids.associateWith { id ->
            val members = labels.indices.filter { labels[it] == id }
            FloatArray(embeddings[0].size) { d -> members.sumOf { embeddings[it][d].toDouble() }.toFloat() / members.size }
        }
        val target = ids.associateWith { id ->
            if (id in big) id else big.maxBy { dot(centroid.getValue(id), centroid.getValue(it)) }
        }
        return IntArray(labels.size) { target.getValue(labels[it]) }
    }

    /** Голосование перекрывающихся окон по кадрам 10 мс; подряд идущие кадры одного кластера дают интервал. */
    fun turns(windows: List<Segment>, labels: IntArray, frameSamples: Int, samplesPerMs: Int): List<SpeakerTurn> {
        if (windows.isEmpty()) return emptyList()
        val ids = labels.distinct().sorted()
        val index = ids.withIndex().associate { (k, id) -> id to k }
        val frames = (windows.maxOf { it.end } / frameSamples + 1).toInt()
        val votes = Array(ids.size) { IntArray(frames) }
        windows.forEachIndexed { w, s ->
            val row = votes[index.getValue(labels[w])]
            for (f in (s.start / frameSamples).toInt() until (s.end / frameSamples).toInt()) row[f]++
        }
        val msPerFrame = frameSamples / samplesPerMs
        val out = mutableListOf<SpeakerTurn>()
        var current = -1
        var start = 0
        for (f in 0..frames) {
            var label = -1
            if (f < frames) {
                var top = 0
                for (k in ids.indices) if (votes[k][f] > top) {
                    top = votes[k][f]
                    label = k
                }
            }
            if (label != current) {
                if (current >= 0) out += SpeakerTurn(current, start.toLong() * msPerFrame, f.toLong() * msPerFrame)
                current = label
                start = f
            }
        }
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
