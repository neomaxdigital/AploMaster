package com.aploworks.aplomaster.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.aploworks.aplomaster.domain.BpmAnalysisResult
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class BpmAnalysisController(context: Context) {
    private val detector = BpmDetector(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private var currentTask: Future<*>? = null

    fun analyze(
        uri: Uri,
        onComplete: (BpmAnalysisResult) -> Unit,
        onError: () -> Unit,
    ) {
        cancel()
        val requestGeneration = generation.incrementAndGet()
        currentTask = executor.submit {
            try {
                val result = detector.detect(uri) {
                    requestGeneration != generation.get() || Thread.currentThread().isInterrupted
                }
                mainHandler.post {
                    if (requestGeneration == generation.get()) onComplete(result)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                mainHandler.post {
                    if (requestGeneration == generation.get()) onError()
                }
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
