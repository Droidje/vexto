package com.vexto.engine.quality

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide admission before binding workers. RSS watchdogs remain the final backstop. */
internal object InferenceResources {
    data class Cost(val threads: Int, val bytes: Long)
    private val mutex = Mutex()
    private val reaper = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var serialOnly = false
    private val active = mutableMapOf<Int, Cost>()
    private const val NativeBudget = 1_650_000_000L // Leaves room for UI, services and the RSS reserve.
    private const val Threads = 4

    suspend fun acquire(cost: Cost): Int {
        require(cost.threads in 1..Threads && cost.bytes in 1..NativeBudget)
        while (true) {
            currentCoroutineContext().ensureActive()
            val slot = mutex.withLock {
                if ((serialOnly && active.isNotEmpty()) || active.values.sumOf { it.threads } + cost.threads > Threads ||
                    active.values.sumOf { it.bytes } + cost.bytes > NativeBudget) null
                else (0..3).firstOrNull { it !in active }?.also { active[it] = cost }
            }
            if (slot != null) return slot
            delay(25)
        }
    }
    suspend fun release(slot: Int) { mutex.withLock { check(active.remove(slot) != null) } }
    fun releaseAfterDeath(slot: Int, dead: Deferred<Unit>) {
        reaper.launch { dead.await(); release(slot) }
    }

    fun cost(request: WorkerRequest, profile: ProcessingProfile): Cost = when (request.stage) {
        WorkerStage.DENOISE -> Cost(2, 160_000_000)
        WorkerStage.DIARIZE -> Cost(if (request.streaming) 2 else 4, 600_000_000)
        WorkerStage.VOICEPRINT -> Cost(2, 250_000_000)
        WorkerStage.EMOTION -> Cost(2, 650_000_000)
        WorkerStage.SELECT -> Cost(4, 1_650_000_000)
        WorkerStage.GIGAAM_REFINE -> Cost(4, 1_200_000_000)
        WorkerStage.GIGAAM ->
            Cost(if (profile.fast) 2 else 4, if (profile.fast) 750_000_000 else 1_200_000_000)
        WorkerStage.WHISPER, WorkerStage.WHISPER_REFINE ->
            if (profile.fast && request.stage == WorkerStage.WHISPER) Cost(2, 800_000_000)
            else Cost(4, NativeBudget)
    }
}
