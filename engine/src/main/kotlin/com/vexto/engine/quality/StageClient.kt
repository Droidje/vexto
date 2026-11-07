package com.vexto.engine.quality

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A stage completes only after its exact worker Binder dies, releasing its model heap. */
internal class StageClient(private val context: Context) {
    suspend fun run(request: WorkerRequest, checkpoint: WorkerResult? = null,
                    onProgress: (Float) -> Unit = {}): WorkerResult {
        val slot = InferenceResources.acquire(InferenceResources.cost(request, ProcessingProfile.read(File(request.jobDir))))
        var releaseSlot = true
        try { return runInSlot(request, checkpoint, slot, onProgress) }
        catch (error: IOException) {
            // Never admit a replacement while an old native heap may still be alive.
            if (error.message?.contains("TERMINATION_UNCONFIRMED") == true) releaseSlot = false
            throw error
        } finally {
            if (releaseSlot) withContext(NonCancellable) { InferenceResources.release(slot) }
        }
    }

    private suspend fun runInSlot(request: WorkerRequest, checkpoint: WorkerResult?, slot: Int,
                                  onProgress: (Float) -> Unit): WorkerResult {
        withContext(Dispatchers.Main) {
            blockedLease?.let {
                if (!it.dead.isCompleted) throw IOException("PREVIOUS_WORKER_TERMINATION_UNCONFIRMED")
                blockedLease = null
            }
        }
        val job = File(request.jobDir)
        val path = request.stage.requestFile(job)
        if (checkpoint != null) {
            require(checkpoint.requestId == request.requestId && checkpoint.stage == request.stage)
            // The prior watchdog used the same request ID. Preserve its partial results, but
            // retire the terminal marker before starting a new worker lease.
            java.nio.file.Files.deleteIfExists(File(job, "stages/${request.stage.name}.terminal.json").toPath())
        }
        JobFiles.save(path, request)
        JobFiles.save(request.stage.resultFile(job), checkpoint?.copy(completed = false, error = "WORKER_RUNNING")
            ?: WorkerResult(request.stage, requestId = request.requestId, error = "WORKER_RUNNING"))
        val plan = runCatching { JobFiles.load<ChunkPlan>(File(job, "chunks.json")) }.getOrNull()
        val audioMs = com.vexto.engine.audio.PcmWave.inspect(JobFiles.inside(job, request.inputWav)).sampleCount / 16
        val speakers = runCatching { JobFiles.load<List<PipelineTurn>>(File(job, "turns.json")).map { it.speaker }.distinct().size }.getOrDefault(1)
        val timeout = StageTimeoutPolicy.milliseconds(request.stage, audioMs, plan?.chunks?.size ?: 1, speakers)
        val started = SystemClock.elapsedRealtime()
        val lease = Lease(context, request, path, slot, onProgress)
        var failure: Throwable? = null
        val result = try {
            // Only this deadline becomes null. Cancellation of the caller, including an outer
            // timeout, still propagates after cleanup rather than silently advancing the pipeline.
            withContext(Dispatchers.Main) { withTimeoutOrNull(timeout) { lease.await() } }
        } catch (error: Exception) { failure = error; null }
        if (result != null) return result

        withContext(NonCancellable + Dispatchers.Main) {
            try { withTimeout(5_000) { lease.cancelAndJoin() } }
            catch (error: Exception) {
                blockedLease = lease
                throw IOException("WORKER_TERMINATION_UNCONFIRMED", error)
            }
        }
        failure?.let { if (it is CancellationException) throw it }
        currentCoroutineContext().ensureActive()
        val reason = if (failure == null) "WORKER_TIMEOUT" else "WORKER_BIND_OR_TRANSPORT_ERROR:${failure!!.javaClass.simpleName}"
        val partial = lease.read()
        return partial.copy(completed = false, elapsedMs = maxOf(partial.elapsedMs, SystemClock.elapsedRealtime() - started),
            error = listOfNotNull(partial.error, reason).distinct().joinToString(";"))
    }

    /** State is confined to the main thread; death callbacks remain installed during cancellation. */
    private class Lease(val context: Context, val request: WorkerRequest, val path: File, val slot: Int, val onProgress: (Float) -> Unit) {
        val dead = CompletableDeferred<Unit>()
        private val main = Handler(Looper.getMainLooper())
        private var bound = false
        private var worker: Messenger? = null
        private var binder: IBinder? = null
        private var connection: ServiceConnection? = null
        private var death: IBinder.DeathRecipient? = null
        private var continuation: CancellableContinuation<WorkerResult>? = null
        private var cancellationRequested = false
        private var released = false

        fun read(): WorkerResult {
            val job = File(request.jobDir)
            // A terminal watchdog artifact cannot be overwritten by a late chunk checkpoint.
            for (file in listOf(File(job, "stages/${request.stage.name}.terminal.json"), request.stage.resultFile(job))) {
                val value = runCatching { JobFiles.load<WorkerResult>(file) }.getOrNull()
                if (value != null && value.requestId == request.requestId && value.stage == request.stage) return value
            }
            return WorkerResult(request.stage, requestId = request.requestId, error = "WORKER_DIED_BEFORE_RESULT")
        }

        private fun release() {
            if (released) return
            released = true
            death?.let { recipient -> runCatching { binder?.unlinkToDeath(recipient, 0) } }
            if (bound) connection?.let { runCatching { context.unbindService(it) } }
            bound = false
        }

        private fun died() {
            if (dead.isCompleted) return
            dead.complete(Unit)
            release()
            if (blockedLease === this) blockedLease = null
            continuation?.takeIf { it.isActive }?.resume(read())
        }

        private fun transportFailure(error: RemoteException) {
            if (binder?.isBinderAlive == false) died()
            else continuation?.takeIf { it.isActive }?.resumeWithException(IOException("Worker transport failed", error))
        }

        suspend fun cancelAndJoin() {
            cancellationRequested = true
            if (!bound && binder == null) { died(); return }
            try { worker?.send(Message.obtain(null, InferenceStageService.Cancel)) }
            catch (error: RemoteException) { transportFailure(error) }
            if (binder?.isBinderAlive == false) died()
            // A cancellation before onServiceConnected retains the binding: the callback
            // links the exact Binder and sends Cancel, instead of starting another stage.
            dead.await()
            release()
        }

        suspend fun await(): WorkerResult = suspendCancellableCoroutine { waiting ->
            continuation = waiting
            val reply = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    if (dead.isCompleted) return
                    if (message.what == InferenceStageService.Progress) {
                        val fraction = message.data.getFloat("fraction")
                        if (fraction.isFinite()) onProgress(fraction.coerceIn(0f, 1f))
                        return
                    }
                    if (message.what != InferenceStageService.Done) return
                    try { worker?.send(Message.obtain(null, InferenceStageService.Shutdown)) }
                    catch (error: RemoteException) { transportFailure(error) }
                }
            })
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, remote: IBinder) {
                    if (dead.isCompleted) return
                    binder = remote
                    worker = Messenger(remote)
                    death = IBinder.DeathRecipient { main.post { died() } }
                    try {
                        remote.linkToDeath(death!!, 0)
                        if (cancellationRequested || !waiting.isActive) {
                            worker!!.send(Message.obtain(null, InferenceStageService.Cancel))
                        } else worker!!.send(Message.obtain(null, InferenceStageService.Run).apply {
                            replyTo = reply; data = Bundle().apply { putString("request", path.absolutePath) }
                        })
                    } catch (error: RemoteException) { transportFailure(error) }
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    if (binder == null || binder?.isBinderAlive == false) died()
                }
                override fun onBindingDied(name: ComponentName) {
                    if (binder?.isBinderAlive == false) died()
                    else continuation?.takeIf { it.isActive }?.resumeWithException(IOException("Worker binding died"))
                }
                override fun onNullBinding(name: ComponentName) {
                    continuation?.takeIf { it.isActive }?.resumeWithException(IOException("Worker returned no Binder"))
                }
            }
            try {
                bound = context.bindService(Intent(context, listOf(InferenceSlot0::class.java, InferenceSlot1::class.java, InferenceSlot2::class.java, InferenceSlot3::class.java)[slot]), connection!!, Context.BIND_AUTO_CREATE)
                if (!bound && waiting.isActive) waiting.resumeWithException(IOException("Cannot bind inference worker"))
            } catch (error: Exception) {
                if (waiting.isActive) waiting.resumeWithException(error)
            }
            // Cleanup is joined by run() in NonCancellable, keeping this exact lease alive.
        }
    }

    private companion object {
        // A failed death confirmation must not let a following job bind the old busy worker.
        var blockedLease: Lease? = null
    }
}
