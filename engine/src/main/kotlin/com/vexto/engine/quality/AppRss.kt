package com.vexto.engine.quality

import java.io.File

/** Sum RSS conservatively, including pages shared between same-UID app processes. */
internal object AppRss {
    const val BudgetBytes = 2_000_000_000L
    const val ReserveBytes = 128L * 1024 * 1024
    fun bytes(uid: Int): Long {
        var total = 0L
        File("/proc").listFiles().orEmpty().forEach { process ->
            if (process.name.toIntOrNull() == null) return@forEach
            runCatching {
                val lines = File(process, "status").readLines()
                fun value(key: String) = lines.firstOrNull { it.startsWith(key) }?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
                if (value("Uid:") == uid.toLong()) total += (value("VmRSS:") ?: 0L) * 1024
            }
        }
        check(total > 0) { "App RSS is not observable" }
        return total
    }
}
