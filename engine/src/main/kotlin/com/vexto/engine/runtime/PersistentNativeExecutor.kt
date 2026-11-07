package com.vexto.engine.runtime

import android.os.Process as AndroidProcess
import android.os.SystemClock
import com.vexto.engine.review.LocalNativeExecutor
import com.vexto.engine.review.LocalNativeResult
import com.vexto.engine.review.NativeRequestRejectedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** A bounded, file-backed request mailbox; one packaged child/model per stage worker.
 * The dedicated executor thread stays alive between requests: PDEATHSIG tracks that
 * spawning thread. The service watchdog and this runner both sum all same-UID RSS.
 */
class PersistentNativeExecutor internal constructor(
    private val runner: NativeProcessRunner,
    workDir: File,
) : LocalNativeExecutor, Closeable {
    private val directory = File(workDir, "native-session-${UUID.randomUUID()}").canonicalFile
    private val closed = AtomicBoolean()
    private val requests = Mutex()
    private val processStart = Any()
    private val owner = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vexto-native-session").apply { isDaemon = true }
    }
    @Volatile private var child: Process? = null
    private var executable: String? = null
    private var configuration: List<String>? = null
    private val ownedPids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var peak = 0L

    override suspend fun run(executableName: String, args: List<String>, workDir: File,
                             stdout: File, stderr: File, timeoutMs: Long): LocalNativeResult = requests.withLock {
        require(executableName in setOf("libvexto_whisper_exec.so", "libvexto_llama_exec.so"))
        require(timeoutMs > 0 && args.size <= 128 && args.none { it.contains('\u0000') || it.length > 8192 })
        check(!closed.get()) { "Persistent native session is closed" }
        val future = owner.submit<LocalNativeResult> { request(executableName, args, stdout, stderr, timeoutMs) }
        try {
            withContext(Dispatchers.IO) {
                while (true) {
                    coroutineContext.ensureActive()
                    try { return@withContext future.get(100, TimeUnit.MILLISECONDS) }
                    catch (_: TimeoutException) { /* Cancellation is checked independently of native work. */ }
                    catch (failed: ExecutionException) { throw failed.cause ?: failed }
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        } catch (failure: Throwable) {
            if (failure !is NativeRequestRejectedException) {
                close()
                future.cancel(true)
            }
            throw failure
        }
    }

    private fun request(name: String, args: List<String>, stdout: File, stderr: File, timeoutMs: Long): LocalNativeResult {
        check(!closed.get())
        val started = SystemClock.elapsedRealtime()
        val stable = configuration(args)
        if (child == null) {
            runner.cleanupOrphans()
            val binary = NativeCpuDispatch.executable(runner.nativeDir, name)
            if (!binary.isFile || !binary.canExecute()) throw NativeProcessException(
                NativeProcessException.Reason.IO, "Native executable is not installer-extracted: $name")
            check(directory.mkdirs() || directory.isDirectory)
            observe()
            executable = name
            configuration = stable
            synchronized(processStart) {
                check(!closed.get()) { "Persistent native session is closed" }
                child = ProcessBuilder(listOf(binary.absolutePath, "--worker-dir", directory.absolutePath) + args)
                    .directory(directory).redirectOutput(File(directory, "worker.stdout"))
                    .redirectError(File(directory, "worker.stderr")).apply {
                        environment()["LD_LIBRARY_PATH"] = runner.nativeDir.absolutePath
                        environment()["HF_HUB_OFFLINE"] = "1"
                        environment()["HF_HUB_DISABLE_TELEMETRY"] = "1"
                    }.start()
            }
        } else require(executable == name && configuration == stable) { "Model/session configuration changed" }
        stdout.parentFile?.mkdirs(); stderr.parentFile?.mkdirs()
        val response = File(directory, "response.json")
        require(!response.exists()) { "Previous native response not acknowledged" }
        val request = buildJsonObject {
            put("args", JsonArray(args.map(::JsonPrimitive)))
            put("stdout", stdout.canonicalPath); put("stderr", stderr.canonicalPath)
        }.toString().toByteArray(Charsets.UTF_8)
        require(request.size <= 65536) { "Native mailbox request too large" }
        val pending = File(directory, "request.pending").apply { writeBytes(request) }
        Files.move(pending.toPath(), File(directory, "request.json").toPath(), StandardCopyOption.ATOMIC_MOVE)
        while (true) {
            if (closed.get() || Thread.currentThread().isInterrupted) throw IOException("Native session closed")
            observe()
            if (response.isFile) {
                require(response.length() in 1..65536) { "Native response missing or oversized" }
                val result = Json.parseToJsonElement(response.readText()).jsonObject
                response.delete()
                if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    val error = result["error"]?.jsonPrimitive?.content ?: "Native request failed"
                    if (error == NativeRequestRejectedException.ContextBudget) throw NativeRequestRejectedException(error)
                    throw NativeProcessException(NativeProcessException.Reason.EXIT, error)
                }
                return LocalNativeResult(SystemClock.elapsedRealtime() - started, peak)
            }
            val code = runCatching { child!!.exitValue() }.getOrNull()
            if (code != null) throw NativeProcessException(NativeProcessException.Reason.EXIT,
                "$name worker exited with status $code", code)
            if (SystemClock.elapsedRealtime() - started > timeoutMs) throw NativeProcessException(
                NativeProcessException.Reason.TIMEOUT, "$name worker request exceeded its time limit")
            Thread.sleep(50)
        }
    }

    private fun observe() {
        val snapshot = runner.snapshot()
        snapshot.filter { it.parentPid == AndroidProcess.myPid() && it.nativeExecutable &&
            it.arguments.split('\u0000').contains(directory.absolutePath) }.forEach { process ->
            if (ownedPids.add(process.pid)) {
                NativeProcessRunner.activeSessionPids.add(process.pid)
                runner.register(process)
            }
        }
        peak = maxOf(peak, snapshot.sumOf { it.rss })
        runner.rejectMemory(peak)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val process = synchronized(processStart) { child?.also { it.destroyForcibly() } }
        owner.shutdownNow()
        runCatching { process?.waitFor(2, TimeUnit.SECONDS) }
        ownedPids.forEach(runner::forgetSessionPid)
    }

    private fun configuration(args: List<String>): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            if (arg in setOf("-f", "-of", "--json-schema-file", "--chat-template-file", "--prompt-audit-file")) {
                require(i < args.size); i++
            } else result += arg
        }
        return result
    }
}
