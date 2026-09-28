package com.aploworks.aplomaster.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.aploworks.aplomaster.domain.WaveformData
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class WaveformAnalysisController(context: Context) {
    private val extractor = WaveformExtractor(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private var currentTask: Future<*>? = null

    fun analyze(
        uri: Uri,
        onComplete: (WaveformData) -> Unit,
        onError: (String) -> Unit,
    ) {
        cancel()
        val requestGeneration = generation.incrementAndGet()
        currentTask = executor.submit {
            try {
                val waveform = extractor.extract(uri) {
                    requestGeneration != generation.get() || Thread.currentThread().isInterrupted
                }
                mainHandler.post {
                    if (requestGeneration == generation.get()) onComplete(waveform)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                mainHandler.post {
                    if (requestGeneration == generation.get()) {
                        onError(error.localizedMessage ?: "Não foi possível analisar a waveform.")
                    }
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
