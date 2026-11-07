package com.vexto.engine.quality

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InferenceResourcesTest {
    @Test fun fourThreadsAdmitTwoLightStagesButBlockAnother() = runBlocking {
        val slots = mutableListOf<Int>()
        var pending: Deferred<Int>? = null
        try {
            slots += InferenceResources.acquire(InferenceResources.Cost(2, 750_000_000))
            slots += InferenceResources.acquire(InferenceResources.Cost(2, 800_000_000))
            assertNotEquals(slots[0], slots[1])
            pending = async { InferenceResources.acquire(InferenceResources.Cost(1, 1)) }
            delay(50)
            assertFalse(pending.isCompleted)
            InferenceResources.release(slots.removeAt(0))
            slots += withTimeout(2000) { pending.await() }
        } finally {
            pending?.cancelAndJoin()
            slots.forEach { InferenceResources.release(it) }
        }
    }

    @Test fun memoryReservationRemainsUntilExactWorkerDeath() = runBlocking {
        val first = InferenceResources.acquire(InferenceResources.Cost(1, 1_200_000_000))
        val dead = CompletableDeferred<Unit>()
        InferenceResources.releaseAfterDeath(first, dead)
        val pending = async { InferenceResources.acquire(InferenceResources.Cost(1, 700_000_000)) }
        var second: Int? = null
        try {
            delay(50)
            assertFalse(pending.isCompleted)
            dead.complete(Unit)
            second = withTimeout(2000) { pending.await() }
        } finally {
            dead.complete(Unit)
            pending.cancelAndJoin()
            second?.let { InferenceResources.release(it) }
        }
    }
}
