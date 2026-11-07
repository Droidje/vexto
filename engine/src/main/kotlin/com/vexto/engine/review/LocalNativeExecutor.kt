package com.vexto.engine.review

import java.io.File

data class LocalNativeResult(val elapsedMs: Long, val peakCombinedRssBytes: Long)

/** Adapter to NativeProcessRunner; implementations execute packaged nativeLibraryDir ELF only. */
fun interface LocalNativeExecutor {
    suspend fun run(executableName: String, args: List<String>, workDir: File,
        stdout: File, stderr: File, timeoutMs: Long): LocalNativeResult
}
