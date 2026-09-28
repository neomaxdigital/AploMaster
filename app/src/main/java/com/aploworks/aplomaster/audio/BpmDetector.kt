package com.aploworks.aplomaster.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aploworks.aplomaster.domain.BpmAnalysisResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.roundToInt
import kotlin.math.sqrt

class BpmDetector(context: Context) {
    private val applicationContext = context.applicationContext

    fun detect(
        uri: Uri,
        isCancelled: () -> Boolean = { false },
    ): BpmAnalysisResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(applicationContext, uri, null)
            val trackIndex = findAudioTrack(extractor)
            check(trackIndex >= 0) { "O arquivo não contém uma faixa de áudio compatível." }
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Formato de áudio desconhecido.")
            val initialSampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            val initialChannels = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            val collector = EnergyEnvelopeCollector(initialSampleRate)

            if (mimeType == "audio/raw") {
                decodeRaw(extractor, inputFormat, collector, isCancelled)
            } else {
                codec = MediaCodec.createDecoderByType(mimeType).apply {
                    configure(inputFormat, null, null, 0)
                    start()
                }
                decodeCompressed(
                    extractor = extractor,
                    codec = codec,
                    collector = collector,
                    initialSampleRate = initialSampleRate,
                    initialChannels = initialChannels,
                    isCancelled = isCancelled,
                )
            }
            return analyzeEnvelope(collector.finish())
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun decodeRaw(
        extractor: MediaExtractor,
        format: MediaFormat,
        collector: EnergyEnvelopeCollector,
        isCancelled: () -> Boolean,
    ) {
        val channelCount = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        val encoding = format.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val buffer = ByteBuffer.allocateDirect(RAW_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        while (!collector.isFull) {
            checkCancellation(isCancelled)
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            collector.consume(buffer, channelCount, encoding)
            extractor.advance()
        }
    }

    private fun decodeCompressed(
        extractor: MediaExtractor,
        codec: MediaCodec,
        collector: EnergyEnvelopeCollector,
        initialSampleRate: Int,
        initialChannels: Int,
        isCancelled: () -> Boolean,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var sampleRate = initialSampleRate
        var channelCount = initialChannels
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        while (!outputEnded && !collector.isFull) {
            checkCancellation(isCancelled)
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                        ?: error("Buffer de entrada indisponível.")
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    sampleRate = outputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channelCount = outputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    encoding = outputFormat.intOrDefault(MediaFormat.KEY_PCM_ENCODING, encoding)
                    collector.updateSampleRate(sampleRate)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outputIndex >= 0) {
                    codec.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                        if (bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            collector.consume(
                                outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN),
                                channelCount,
                                encoding,
                            )
                        }
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun analyzeEnvelope(energy: FloatArray): BpmAnalysisResult {
        if (energy.size < MINIMUM_ENVELOPE_POINTS) return BpmAnalysisResult.failure()
        val maximumEnergy = energy.maxOrNull() ?: 0f
        if (maximumEnergy < MINIMUM_SIGNAL_RMS) return BpmAnalysisResult.failure()

        val onset = FloatArray(energy.size)
        var previous = ln1p(energy.first() * ENERGY_LOG_SCALE)
        var onsetCount = 0
        for (index in 1 until energy.size) {
            val current = ln1p(energy[index] * ENERGY_LOG_SCALE)
            val difference = (current - previous).coerceAtLeast(0f)
            onset[index] = difference
            if (difference > MINIMUM_ONSET) onsetCount++
            previous = current
        }
        if (onsetCount < MINIMUM_ONSET_COUNT) return BpmAnalysisResult.failure()

        val mean = onset.average().toFloat()
        for (index in onset.indices) onset[index] -= mean
        val minimumLag = (ENVELOPE_RATE_HZ * 60f / MAX_BPM).roundToInt()
        val maximumLag = (ENVELOPE_RATE_HZ * 60f / MIN_BPM).roundToInt()
        var bestLag = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (lag in minimumLag..maximumLag) {
            val score = normalizedCorrelation(onset, lag)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || !bestScore.isFinite()) return BpmAnalysisResult.failure()

        val fasterLag = (bestLag / 2f).roundToInt()
        if (fasterLag >= minimumLag) {
            val fasterScore = normalizedCorrelation(onset, fasterLag)
            if (fasterScore >= bestScore * HALF_TIME_EQUIVALENCE) {
                bestLag = fasterLag
                bestScore = fasterScore
            }
        }

        val refinedLag = refineLag(onset, bestLag)
        val bpm = (60f * ENVELOPE_RATE_HZ / refinedLag).coerceIn(MIN_BPM, MAX_BPM)
        val confidence = ((bestScore - MINIMUM_USEFUL_CORRELATION) /
            (STRONG_CORRELATION - MINIMUM_USEFUL_CORRELATION)).coerceIn(0f, 1f)
        return if (confidence >= SUCCESS_CONFIDENCE) {
            BpmAnalysisResult(bpm = bpm, confidence = confidence, success = true)
        } else {
            BpmAnalysisResult.failure(confidence)
        }
    }

    private fun normalizedCorrelation(values: FloatArray, lag: Int): Float {
        var numerator = 0.0
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        for (index in lag until values.size) {
            val left = values[index].toDouble()
            val right = values[index - lag].toDouble()
            numerator += left * right
            leftEnergy += left * left
            rightEnergy += right * right
        }
        val denominator = sqrt(leftEnergy * rightEnergy)
        return if (denominator > 1e-9) (numerator / denominator).toFloat() else 0f
    }

    private fun refineLag(values: FloatArray, lag: Int): Float {
        if (lag <= 1) return lag.toFloat()
        val left = normalizedCorrelation(values, lag - 1)
        val center = normalizedCorrelation(values, lag)
        val right = normalizedCorrelation(values, lag + 1)
        val denominator = left - 2f * center + right
        val offset = if (abs(denominator) > 1e-6f) {
            (0.5f * (left - right) / denominator).coerceIn(-0.5f, 0.5f)
        } else {
            0f
        }
        return lag + offset
    }

    private fun checkCancellation(isCancelled: () -> Boolean) {
        if (isCancelled() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Análise de BPM cancelada.")
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: -1

    private fun MediaFormat.intOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    private class EnergyEnvelopeCollector(initialSampleRate: Int) {
        private val values = ArrayList<Float>(MAX_ENVELOPE_POINTS)
        private var samplesPerWindow = samplesPerWindow(initialSampleRate)
        private var squaredSum = 0.0
        private var frameCount = 0
        val isFull: Boolean get() = values.size >= MAX_ENVELOPE_POINTS

        fun updateSampleRate(sampleRate: Int) {
            samplesPerWindow = samplesPerWindow(sampleRate)
        }

        fun consume(buffer: ByteBuffer, channelCount: Int, encoding: Int) {
            val channels = channelCount.coerceAtLeast(1)
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val frameSize = channels * bytesPerSample
            val totalFrames = buffer.remaining() / frameSize
            for (frame in 0 until totalFrames) {
                var mono = 0f
                repeat(channels) { channel ->
                    val offset = frame * frameSize + channel * bytesPerSample
                    mono += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        buffer.getFloat(offset).coerceIn(-1f, 1f)
                    } else {
                        buffer.getShort(offset).toInt() / 32768f
                    }
                }
                mono /= channels
                squaredSum += mono * mono
                frameCount++
                if (frameCount >= samplesPerWindow) {
                    values += sqrt(squaredSum / frameCount).toFloat()
                    squaredSum = 0.0
                    frameCount = 0
                    if (isFull) return
                }
            }
        }

        fun finish(): FloatArray {
            if (frameCount > 0 && !isFull) values += sqrt(squaredSum / frameCount).toFloat()
            return values.toFloatArray()
        }

        private fun samplesPerWindow(sampleRate: Int) =
            (sampleRate.coerceAtLeast(1) / ENVELOPE_RATE_HZ).coerceAtLeast(1)
    }

    private companion object {
        const val MIN_BPM = 60f
        const val MAX_BPM = 200f
        const val ENVELOPE_RATE_HZ = 100
        const val MAX_ANALYSIS_SECONDS = 180
        const val MAX_ENVELOPE_POINTS = ENVELOPE_RATE_HZ * MAX_ANALYSIS_SECONDS
        const val MINIMUM_ENVELOPE_POINTS = ENVELOPE_RATE_HZ * 4
        const val MINIMUM_SIGNAL_RMS = 0.001f
        const val ENERGY_LOG_SCALE = 50f
        const val MINIMUM_ONSET = 0.01f
        const val MINIMUM_ONSET_COUNT = 4
        const val MINIMUM_USEFUL_CORRELATION = 0.18f
        const val STRONG_CORRELATION = 0.75f
        const val SUCCESS_CONFIDENCE = 0.35f
        const val HALF_TIME_EQUIVALENCE = 0.92f
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val RAW_BUFFER_SIZE = 256 * 1_024
    }
}
