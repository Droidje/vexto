package com.vexto.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PriorityInferenceGateTest {
    @Test fun foregroundPreemptsButWaitsForNativeResourceCleanup() = runBlocking {
        withTimeout(5_000) {
            val gate = PriorityInferenceGate()
            val started = CompletableDeferred<Unit>()
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val low = async {
                gate.background {
                    started.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            closing.complete(Unit)
                            release.await()
                            events += "closed"
                        }
                    }
                }
            }
            started.await()
            val high = async(start = CoroutineStart.UNDISPATCHED) { gate.foreground { events += "high" } }
            closing.await()
            assertFalse(high.isCompleted)
            assertFalse(gate.background { fail("Background overtook pending foreground") })
            release.complete(Unit)
            assertFalse(low.await())
            high.await()
            assertEquals(listOf("closed", "high"), events)
        }
    }

    @Test fun cancelledWaitingForegroundDoesNotLeakPriority() = runBlocking {
        withTimeout(5_000) {
            val gate = PriorityInferenceGate()
            val release = CompletableDeferred<Unit>()
            val active = launch(start = CoroutineStart.UNDISPATCHED) { gate.foreground { release.await() } }
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { gate.foreground { fail("Cancelled waiter ran") } }
            waiter.cancelAndJoin()
            assertFalse(gate.background { fail("Concurrent native work") })
            release.complete(Unit)
            active.join()
            assertTrue(gate.background {})
        }
    }

    @Test fun cancellingBackgroundCallerStillJoinsCleanupBeforeUnlocking() = runBlocking {
        withTimeout(5_000) {
            val gate = PriorityInferenceGate()
            val started = CompletableDeferred<Unit>()
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val low = launch {
                gate.background {
                    started.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { closing.complete(Unit); release.await() }
                    }
                }
            }
            started.await()
            low.cancel()
            closing.await()
            val high = async(start = CoroutineStart.UNDISPATCHED) { gate.foreground { true } }
            assertFalse(high.isCompleted)
            release.complete(Unit)
            low.join()
            assertTrue(high.await())
            assertTrue(gate.background {})
        }
    }

    @Test fun waitingBackgroundCannotOvertakeQueuedForeground() = runBlocking {
        withTimeout(5_000) {
            val gate = PriorityInferenceGate()
            val started = CompletableDeferred<Unit>()
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val low = async {
                gate.background {
                    started.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { closing.complete(Unit); release.await() }
                    }
                }
            }
            started.await()
            val waitingLow = async(start = CoroutineStart.UNDISPATCHED) {
                gate.background { fail("Background overtook queued foreground") }
            }
            val high = async(start = CoroutineStart.UNDISPATCHED) { gate.foreground {} }
            closing.await()
            release.complete(Unit)
            assertFalse(low.await())
            assertFalse(waitingLow.await())
            high.await()
        }
    }
}
