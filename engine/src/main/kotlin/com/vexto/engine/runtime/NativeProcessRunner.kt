package com.vexto.engine.runtime

import android.content.Context
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class NativeProcessResult(val peakCombinedRssBytes: Long, val elapsedMs: Long)

class NativeProcessException(val reason: Reason, message: String, val exitCode: Int? = null,
                             cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { EXIT, TIMEOUT, MEMORY, IO }
}

/** Executes installer-extracted PIE binaries, never writable downloaded executable files.
 * RSS sums every process with this app UID (UI, :inference and children). The 100 ms
 * watchdog plus reserve is an observed bound, not a cgroup/hard allocation limit.
 */
class NativeProcessRunner(
    context: Context,
    private val budgetBytes: Long = 2_000_000_000L,
    private val reserveBytes: Long = 128L * 1024 * 1024,
) {
    internal val nativeDir = File(context.applicationInfo.nativeLibraryDir).canonicalFile
    private val uid = context.applicationInfo.uid
    private val registry = File(context.filesDir, "native-child-registry")

    init { require(budgetBytes > 0 && reserveBytes in 0 until budgetBytes) }

    suspend fun run(executableName: String, args: List<String>, workDir: File,
                    stdout: File, stderr: File, timeoutMs: Long,
                    onTick: (() -> Unit)? = null): NativeProcessResult = withContext(Dispatchers.IO) {
        require(executableName in Executables) { "Unknown packaged native executable" }
        require(timeoutMs > 0 && args.none { it.contains('\u0000') })
        require(stdout.canonicalFile != stderr.canonicalFile)
        lock.withLock {
            cleanupOrphans()
            val executable = NativeCpuDispatch.executable(nativeDir, executableName)
            if (!executable.isFile || !executable.canExecute()) {
                throw NativeProcessException(NativeProcessException.Reason.IO,
                    "Native executable is not installer-extracted: $executableName")
            }
            check(workDir.mkdirs() || workDir.isDirectory)
            stdout.parentFile?.mkdirs()
            stderr.parentFile?.mkdirs()
            val initial = snapshot()
            val existingPids = initial.map { it.pid }.toSet()
            var peak = initial.sumOf { it.rss }
            rejectMemory(peak)
            val started = SystemClock.elapsedRealtime()
            val child = try {
                ProcessBuilder(listOf(executable.absolutePath) + args).directory(workDir)
                    .redirectOutput(stdout).redirectError(stderr).apply {
                        environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
                        environment()["VEXTO_NEMOTRON_THREADS"] = "2"
                        environment()["OMP_NUM_THREADS"] = "4"
                        environment()["OPENBLAS_NUM_THREADS"] = "4"
                        environment()["HF_HUB_OFFLINE"] = "1"
                        environment()["HF_HUB_DISABLE_TELEMETRY"] = "1"
                    }.start()
            } catch (error: IOException) {
                throw NativeProcessException(NativeProcessException.Reason.IO,
                    "Cannot start packaged native executable: $executableName", cause = error)
            }
            val registeredPids = mutableSetOf<Pair<Int, String>>()
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    onTick?.invoke()
                    val observed = snapshot()
                    observed.filter { it.pid !in existingPids && it.parentPid == AndroidProcess.myPid() &&
                        it.nativeExecutable && it.command == executable.absolutePath }
                        .forEach { if (registeredPids.add(it.pid to it.startTicks)) register(it) }
                    peak = maxOf(peak, observed.sumOf { it.rss })
                    rejectMemory(peak)
                    if (SystemClock.elapsedRealtime() - started > timeoutMs) {
                        throw NativeProcessException(NativeProcessException.Reason.TIMEOUT,
                            "$executableName exceeded its time limit")
                    }
                    val code = runCatching { child.exitValue() }.getOrNull()
                    if (code != null) {
                        if (code != 0) throw NativeProcessException(NativeProcessException.Reason.EXIT,
                            "$executableName exited with status $code", code)
                        break
                    }
                    // Keep the spawning IO thread alive: Linux PDEATHSIG follows the parent thread.
                    Thread.sleep(100)
                }
                NativeProcessResult(peak, SystemClock.elapsedRealtime() - started)
            } finally {
                child.destroyForcibly()
                runCatching { child.waitFor(2, TimeUnit.SECONDS) }
                registeredPids.forEach { (pid, _) -> forgetSessionPid(pid) }
                runCatching { cleanupOrphans() }
            }
        }
    }

    internal fun rejectMemory(observed: Long) {
        if (observed > budgetBytes - reserveBytes) throw NativeProcessException(
            NativeProcessException.Reason.MEMORY, "App process RSS plus reserve exceeds stage budget")
    }

    /** Only terminate a PID registered after this runner started it, with UID, executable
     * and kernel start-time identity checked to avoid PID reuse or unrelated app services. */
    fun cleanupOrphans() {
        val live = snapshot().associateBy { it.pid }
        registry.listFiles().orEmpty().forEach { record ->
            val pid = record.name.toIntOrNull() ?: return@forEach
            if (pid in activeSessionPids) return@forEach
            val saved = runCatching { record.readLines() }.getOrDefault(emptyList())
            val process = live[pid]
            // Release ART processes may hide /proc/<pid>/stat even from the same UID.
            // Their executable children remain visible, so absence from snapshot is not death.
            val parentAlive = process != null && (process.parentPid in live || parentMayBeAlive(process.parentPid))
            if (process != null && NativeReaperPolicy.shouldReap(process, saved, uid,
                    pid in activeSessionPids, parentAlive)) {
                AndroidProcess.killProcess(pid)
            }
            if (!parentAlive) record.delete()
        }
    }

    private fun parentMayBeAlive(pid: Int): Boolean {
        if (pid <= 1) return false
        // status is less restricted than stat/cmdline. A same-UID parent is a live lease,
        // including ordinary (non-persistent) invocations in this process.
        val parentUid = runCatching { File("/proc/$pid/status").readLines()
            .firstOrNull { it.startsWith("Uid:") }?.trim()?.split(Regex("\\s+"))
            ?.getOrNull(1)?.toIntOrNull() }.getOrNull()
        if (parentUid == uid) return true
        return try {
            Os.kill(pid, 0)
            true
        } catch (failure: ErrnoException) {
            // EPERM, an unreadable /proc entry, or a transient probe error is not proof of death.
            failure.errno != OsConstants.ESRCH
        }
    }

    internal fun register(process: Proc) {
        // Publish the live lease before its shared registry record can be reaped.
        activeSessionPids.add(process.pid)
        registry.mkdirs()
        File(registry, process.pid.toString()).writeText(process.startTicks + "\n" + process.command + "\n")
    }

    internal data class Proc(val pid: Int, val parentPid: Int, val rss: Long,
                            val nativeExecutable: Boolean, val command: String, val startTicks: String,
                            val arguments: String, val uid: Int)

    internal fun snapshot(): List<Proc> {
        val result = ArrayList<Proc>()
        File("/proc").listFiles().orEmpty().forEach { directory ->
            val pid = directory.name.toIntOrNull() ?: return@forEach
            try {
                val lines = File(directory, "status").readLines()
                fun number(key: String) = lines.firstOrNull { it.startsWith(key) }
                    ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
                if (number("Uid:") != uid.toLong()) return@forEach
                val rss = (number("VmRSS:") ?: 0L) * 1024
                val arguments = runCatching { File(directory, "cmdline").readBytes()
                    .toString(Charsets.UTF_8) }.getOrDefault("")
                val command = arguments.substringBefore('\u0000')
                val native = command.startsWith(nativeDir.absolutePath + File.separator) &&
                    (File(command).name in Executables || File(command).name.replace("_avx2_exec.so", "_exec.so") in Executables)
                val stat = File(directory, "stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))
                result += Proc(pid, (number("PPid:") ?: 0L).toInt(), rss, native, command, stat[19], arguments, uid)
            } catch (_: IOException) { /* Process exited or /proc hides a different UID. */ }
        }
        check(result.any { it.pid == AndroidProcess.myPid() && it.rss > 0 }) { "Cannot observe app RSS" }
        return result
    }

    /** Each executor owns exactly one child and one model lifetime. Close it at stage exit. */
    fun persistentExecutor(workDir: File): PersistentNativeExecutor = PersistentNativeExecutor(this, workDir)

    internal fun forgetSessionPid(pid: Int) {
        activeSessionPids.remove(pid)
        File(registry, pid.toString()).delete()
    }

    internal companion object {
        val activeSessionPids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val lock = Mutex()
        val Executables = setOf("libvexto_nemotron_exec.so", "libvexto_whisper_exec.so", "libvexto_llama_exec.so")
    }
}
