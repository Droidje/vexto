package com.vexto.engine.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeReaperPolicyTest {
    private val process = NativeProcessRunner.Proc(200, 100, 1, true,
        "/installed/libvexto_nemotron_exec.so", "12345", "", 10123)
    private val saved = listOf(process.startTicks, process.command)

    @Test fun `live sibling omitted from full proc snapshot is not an orphan`() {
        assertFalse(NativeReaperPolicy.shouldReap(process, saved, 10123, false, true))
    }

    @Test fun `ordinary live invocation has the same protection as a persistent session`() {
        assertFalse(NativeReaperPolicy.shouldReap(process, saved, 10123, true, false))
    }

    @Test fun `matching native orphan with a proven dead owner can be reaped`() {
        assertTrue(NativeReaperPolicy.shouldReap(process.copy(parentPid = 1), saved, 10123, false, false))
    }

    @Test fun `reused pid or different executable is never reaped`() {
        assertFalse(NativeReaperPolicy.shouldReap(process.copy(startTicks = "54321"), saved, 10123, false, false))
        assertFalse(NativeReaperPolicy.shouldReap(process.copy(command = "/other/binary"), saved, 10123, false, false))
    }

    @Test fun `another uid or unrecognized executable is never reaped`() {
        assertFalse(NativeReaperPolicy.shouldReap(process.copy(uid = 10124), saved, 10123, false, false))
        assertFalse(NativeReaperPolicy.shouldReap(process.copy(nativeExecutable = false), saved, 10123, false, false))
        assertFalse(NativeReaperPolicy.shouldReap(process, emptyList(), 10123, false, false))
    }
}
