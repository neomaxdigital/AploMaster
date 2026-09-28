package com.aploworks.aplomaster.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.aploworks.aplomaster.domain.LoudnessAnalysisResult
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class LoudnessAnalysisController(context: Context) {
    private val analyzer = LoudnessAnalyzer(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private var currentTask: Future<*>? = null

    fun analyze(uri: Uri, onComplete: (LoudnessAnalysisResult) -> Unit) {
        cancel()
        val requestGeneration = generation.incrementAndGet()
        currentTask = executor.submit {
            val result = try {
                analyzer.analyze(uri) {
                    requestGeneration != generation.get() || Thread.currentThread().isInterrupted
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@submit
            } catch (error: Exception) {
                LoudnessAnalysisResult.failure(
                    error.localizedMessage ?: "Não foi possível analisar o loudness.",
                )
            }
            mainHandler.post {
                if (requestGeneration == generation.get()) onComplete(result)
            }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        currentTask?.cancel(true)
        currentTask = null
    }

    fun release() {
        cancel()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }
}
