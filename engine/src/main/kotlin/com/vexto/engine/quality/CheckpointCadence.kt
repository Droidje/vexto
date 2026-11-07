package com.vexto.engine.quality

/** RAW artifacts remain durable per chunk; amortize rewriting the growing stage index. */
internal class CheckpointCadence {
    private var count = 0
    private var time = System.nanoTime()
    fun due(completed: Int, total: Int): Boolean {
        val now = System.nanoTime()
        if (completed == total || completed - count >= 4 || now - time >= 1_000_000_000L) {
            count = completed; time = now; return true
        }
        return false
    }
}
