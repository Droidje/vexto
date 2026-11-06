package com.vexto.engine.quality

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import java.io.File

/** Fresh same-UID process per ML stage. No PCM tensors or model text through Binder. */
class InferenceStageService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var finished = false
    private var active: Job? = null
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == Cancel || message.what == Shutdown) { active?.cancel(); android.os.Process.killProcess(android.os.Process.myPid()); return }
            if (message.what != Run || busy) return
            busy = true
            val reply = message.replyTo ?: return
            val path = message.data.getString("request") ?: return
            active = scope.launch { execute(path, reply) }
        }
    })

    override fun onBind(intent: Intent) = messenger.binder
    override fun onDestroy() { scope.cancel(); super.onDestroy(); android.os.Process.killProcess(android.os.Process.myPid()) }

    private suspend fun execute(path: String, reply: Messenger) {
        var request: WorkerRequest? = null
        var started = SystemClock.elapsedRealtime()
        var peak = 0L
        var watchdog: Runnable? = null
        try {
            val root = File(filesDir, "inference/jobs").canonicalFile
            val requestFile = File(path).canonicalFile
            require(requestFile.path.startsWith(root.path + File.separator))
            request = JobFiles.load<WorkerRequest>(requestFile)
            val job = File(request.jobDir).canonicalFile
            require(job.parentFile == root && requestFile == request.stage.requestFile(job).canonicalFile)
            JobFiles.inside(job, request.inputWav)
            started = SystemClock.elapsedRealtime()
            val captured = request
            watchdog = object : Runnable {
                override fun run() {
                    if (finished) return
                    val observed = runCatching { AppRss.bytes(applicationInfo.uid) }.getOrElse { AppRss.BudgetBytes }
                    peak = maxOf(peak, observed)
                    if (observed + AppRss.ReserveBytes > AppRss.BudgetBytes) {
                        // JNI can be non-cancellable while allocating. Persist failure, then terminate only this worker.
                        val partial = previous(captured, job)
                        JobFiles.save(File(job, "stages/${captured.stage.name}.terminal.json"), partial.copy(completed = false,
                            elapsedMs = SystemClock.elapsedRealtime() - started, peakCombinedRssBytes = peak,
                            requestId = captured.requestId, error = "MEMORY_BUDGET_EXCEEDED"))
                        finish(reply)
                    } else main.postDelayed(this, 100)
                }
            }
            main.post(watchdog)
            val result = StageDispatcher.run(this, request)
            withContext(Dispatchers.Main) {
                if (!finished) JobFiles.save(request.stage.resultFile(job), result.copy(completed = true, requestId = request.requestId,
                    elapsedMs = SystemClock.elapsedRealtime() - started, peakCombinedRssBytes = maxOf(peak, result.peakCombinedRssBytes)))
            }
        } catch (failure: Throwable) {
            request?.let { value ->
                val job = File(value.jobDir)
                withContext(NonCancellable + Dispatchers.Main) {
                    if (!finished) JobFiles.save(value.stage.resultFile(job), previous(value, job).copy(completed = false,
                        requestId = value.requestId, elapsedMs = SystemClock.elapsedRealtime() - started, peakCombinedRssBytes = peak,
                        error = if (failure is CancellationException) "CANCELLED" else failure.javaClass.simpleName + ": " + (failure.message ?: "stage failed")))
                }
            }
        } finally {
            watchdog?.let(main::removeCallbacks)
            withContext(NonCancellable + Dispatchers.Main) { if (!finished) finish(reply) }
        }
    }

    private fun previous(request: WorkerRequest, job: File): WorkerResult =
        runCatching { JobFiles.load<WorkerResult>(request.stage.resultFile(job)).also { require(it.requestId == request.requestId) } }.getOrElse { WorkerResult(request.stage, requestId = request.requestId) }

    private fun finish(reply: Messenger) {
        if (finished) return
        finished = true
        runCatching { reply.send(Message.obtain(null, Done).apply { arg1 = android.os.Process.myPid() }) }
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 250)
    }

    companion object { const val Run = 1; const val Done = 2; const val Cancel = 3; const val Shutdown = 4 }
}
