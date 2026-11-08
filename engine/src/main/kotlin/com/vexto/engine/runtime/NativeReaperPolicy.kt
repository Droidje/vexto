package com.vexto.engine.runtime

/** Only proven-dead owners are eligible; a missing full /proc snapshot is insufficient. */
internal object NativeReaperPolicy {
    fun shouldReap(process: NativeProcessRunner.Proc, saved: List<String>, expectedUid: Int,
                   activeLease: Boolean, parentAlive: Boolean): Boolean =
        !activeLease && !parentAlive && process.uid == expectedUid && process.nativeExecutable &&
            saved.size == 2 && saved[0] == process.startTicks && saved[1] == process.command
}
