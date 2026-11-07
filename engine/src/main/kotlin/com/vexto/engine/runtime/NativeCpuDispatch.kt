package com.vexto.engine.runtime

import java.io.File

/** The baseline detector checks CPUID AND OS register state before executing any AVX instruction. */
internal object NativeCpuDispatch {
    private val accelerated by lazy {
        runCatching { System.loadLibrary("vexto_cpu"); supportsAvx2() }.getOrDefault(false)
    }
    private external fun supportsAvx2(): Boolean

    fun executable(directory: File, requested: String): File {
        val optimized = File(directory, requested.replace("_exec.so", "_avx2_exec.so"))
        return if (optimized.isFile && accelerated) optimized else File(directory, requested)
    }
}
