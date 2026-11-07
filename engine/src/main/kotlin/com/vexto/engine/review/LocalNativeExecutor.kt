package com.vexto.engine.review

import java.io.File

data class LocalNativeResult(val elapsedMs: Long, val peakCombinedRssBytes: Long)

/** A request rejected before evaluation; the persistent model remains usable. */
class NativeRequestRejectedException(val code: String) : java.io.IOException(code) {
    companion object { const val ContextBudget = "QWEN_CONTEXT_BUDGET_EXCEEDED" }
}

/** Adapter to NativeProcessRunner; implementations execute packaged nativeLibraryDir ELF only. */
fun interface LocalNativeExecutor {
    suspend fun run(executableName: String, args: List<String>, workDir: File,
        stdout: File, stderr: File, timeoutMs: Long): LocalNativeResult
}
