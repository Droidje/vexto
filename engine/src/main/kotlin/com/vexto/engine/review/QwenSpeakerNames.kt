package com.vexto.engine.review

import com.vexto.engine.model.Transcript
import com.vexto.engine.runtime.NativeProcessException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.nio.ByteBuffer

/** One optional constrained request per recording, on the SELECT stage's existing native session. */
internal class QwenSpeakerNames(private val executor: LocalNativeExecutor, private val model: File) {
    suspend fun infer(transcript: Transcript, directory: File): SpeakerNamesArtifact {
        val started = System.nanoTime()
        val plan = SpeakerNamePlan.create(transcript)
        fun result(status: String) = SpeakerNamesArtifact(plan.sourceSha256, status,
            elapsedMs = (System.nanoTime() - started) / 1_000_000)
        val request = plan.request ?: return result("no_identity_cues")
        if (!model.isFile) return result("model_unavailable")
        return try {
            val args = BoundedQwenRequest.arguments(model, request, directory)
            val output = File(directory, "names.stdout")
            executor.run("libvexto_llama_exec.so", args, directory, output, File(directory, "names.stderr"), TimeoutMs)
            require(output.isFile && output.length() in 1..256) { "Invalid names response size" }
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.readBytes())).toString()
            plan.resolve(text).copy(elapsedMs = (System.nanoTime() - started) / 1_000_000)
        } catch (_: TimeoutCancellationException) {
            result("timeout")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: NativeProcessException) {
            result(if (failure.reason == NativeProcessException.Reason.TIMEOUT) "timeout" else "runtime_failed")
        } catch (_: IllegalArgumentException) {
            result("invalid_response")
        } catch (_: Exception) {
            result("runtime_failed")
        }
    }
    companion object { const val TimeoutMs = 10_000L }
}
