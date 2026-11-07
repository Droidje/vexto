package com.vexto.engine.model

import kotlinx.serialization.Serializable

/** A request snapshots this preference; AUTO is resolved once using decoded audio duration. */
@Serializable
enum class ProcessingMode {
    AUTO, FAST, QUALITY;

    fun resolve(audioDurationMs: Long): ProcessingMode {
        require(audioDurationMs >= 0)
        return if (this == AUTO) {
            if (audioDurationMs > 300_000L) FAST else QUALITY
        } else this
    }
}
