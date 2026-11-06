package com.vexto.engine

import java.io.File

/** Пиковый RSS процесса из /proc: учитывает и нативную память моделей, и Java-кучу. */
internal object MemoryProbe {

    fun peakRssMb(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmHWM:") }
        ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
        ?.div(1024) ?: 0

    /** Сбрасывает VmHWM, чтобы пик относился к текущей расшифровке. Ядро может запретить запись. */
    fun resetPeak() {
        runCatching { File("/proc/self/clear_refs").writeText("5") }
    }
}
