package com.vexto.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes native inference. Cancellation is cooperative: no native-call latency guarantee. */
internal class PriorityInferenceGate {
    private val mutex = Mutex()
    private val state = Any()
    private var foregroundRequests = 0
    private var backgroundJob: Job? = null

    private class Preempted : CancellationException("Foreground inference requested")

    suspend fun <T> foreground(block: suspend () -> T): T {
        synchronized(state) {
            // Register before waiting, so a newly arriving background task cannot overtake us.
            foregroundRequests++
            backgroundJob?.cancel(Preempted())
        }
        try {
            return mutex.withLock { block() }
        } finally {
            synchronized(state) { foregroundRequests-- }
        }
    }

    /** False means yielded; caller must schedule a later retry, never spin on this method. */
    suspend fun background(block: suspend () -> Unit): Boolean {
        currentCoroutineContext().ensureActive()
        if (synchronized(state) { foregroundRequests > 0 }) return false
        return mutex.withLock {
            // The scope joins child cleanup BEFORE the lock is released, including caller cancellation.
            supervisorScope {
                val child = async(start = CoroutineStart.LAZY) { block() }
                val admitted = synchronized(state) {
                    if (foregroundRequests > 0) false else {
                        backgroundJob = child
                        true
                    }
                }
                if (!admitted) {
                    child.cancel()
                    false
                } else {
                    try {
                        child.start()
                        child.await()
                        true
                    } catch (e: Preempted) {
                        currentCoroutineContext().ensureActive()
                        false
                    } finally {
                        synchronized(state) { backgroundJob = null }
                    }
                }
            }
        }
    }
}

internal val sharedPriorityInferenceGate = PriorityInferenceGate()
