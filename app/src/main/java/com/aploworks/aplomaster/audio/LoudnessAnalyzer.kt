package com.aploworks.aplomaster.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aploworks.aplomaster.domain.LoudnessAnalysisResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

class LoudnessAnalyzer(context: Context) {
    private val applicationContext = context.applicationContext

    fun analyze(
        uri: Uri,
        isCancelled: () -> Boolean = { false },
    ): LoudnessAnalysisResult {
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
            val sampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            val channels = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            val accumulator = LoudnessAccumulator(sampleRate, channels)

            if (mimeType == "audio/raw") {
                decodeRaw(extractor, inputFormat, accumulator, isCancelled)
            } else {
                codec = MediaCodec.createDecoderByType(mimeType).apply {
                    configure(inputFormat, null, null, 0)
                    start()
                }
                decodeCompressed(
                    extractor,
                    codec,
                    accumulator,
                    channels,
                    isCancelled,
                )
            }
            return accumulator.result()
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun decodeRaw(
        extractor: MediaExtractor,
        format: MediaFormat,
        accumulator: LoudnessAccumulator,
        isCancelled: () -> Boolean,
    ) {
        val channelCount = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        val encoding = format.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val buffer = ByteBuffer.allocateDirect(RAW_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            checkCancellation(isCancelled)
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            accumulator.consume(buffer, channelCount, encoding)
            extractor.advance()
        }
    }

    private fun decodeCompressed(
        extractor: MediaExtractor,
        codec: MediaCodec,
        accumulator: LoudnessAccumulator,
        initialChannels: Int,
        isCancelled: () -> Boolean,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var channels = initialChannels
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        while (!outputEnded) {
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
            when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    channels = outputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    encoding = outputFormat.intOrDefault(MediaFormat.KEY_PCM_ENCODING, encoding)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outputIndex >= 0) {
                    codec.getOutputBuffer(outputIndex)?.let { output ->
                        if (info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            accumulator.consume(
                                output.slice().order(ByteOrder.LITTLE_ENDIAN),
                                channels,
                                encoding,
                            )
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun checkCancellation(isCancelled: () -> Boolean) {
        if (isCancelled() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Análise de loudness cancelada.")
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: -1

    private fun MediaFormat.intOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    private class LoudnessAccumulator(sampleRate: Int, channelCount: Int) {
        private val channels = channelCount.coerceAtLeast(1)
        private val samplesPerSegment = (sampleRate / SEGMENTS_PER_SECOND).coerceAtLeast(1)
        private val filters = Array(channels) { KWeightingFilter(sampleRate) }
        private val truePeakEstimators = Array(channels) { TruePeakEstimator() }
        private val segmentEnergies = ArrayList<Double>()
        private var segmentSquaredSum = 0.0
        private var segmentFrames = 0
        private var samplePeak = 0f

        fun consume(buffer: ByteBuffer, decodedChannels: Int, encoding: Int) {
            val actualChannels = decodedChannels.coerceAtLeast(1)
            check(actualChannels == channels) { "A configuração de canais mudou durante a análise." }
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val frameSize = actualChannels * bytesPerSample
            val frames = buffer.remaining() / frameSize
            repeat(frames) { frame ->
                var frameWeightedEnergy = 0.0
                repeat(actualChannels) { channel ->
                    val offset = frame * frameSize + channel * bytesPerSample
                    val value = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        buffer.getFloat(offset).coerceIn(-1f, 1f)
                    } else {
                        buffer.getShort(offset).toInt() / 32768f
                    }
                    samplePeak = max(samplePeak, abs(value))
                    truePeakEstimators[channel].add(value)
                    val weighted = filters[channel].process(value.toDouble())
                    frameWeightedEnergy += weighted * weighted
                }
                segmentSquaredSum += frameWeightedEnergy
                segmentFrames++
                if (segmentFrames >= samplesPerSegment) finishSegment()
            }
        }

        fun result(): LoudnessAnalysisResult {
            if (segmentFrames > 0) finishSegment()
            if (samplePeak <= SILENCE_EPSILON || segmentEnergies.size < BLOCK_SEGMENTS) {
                return LoudnessAnalysisResult.failure("O áudio não possui sinal mensurável.")
            }
            val blockEnergies = ArrayList<Double>()
            for (index in BLOCK_SEGMENTS - 1 until segmentEnergies.size) {
                var sum = 0.0
                repeat(BLOCK_SEGMENTS) { offset -> sum += segmentEnergies[index - offset] }
                blockEnergies += sum / BLOCK_SEGMENTS
            }
            val absoluteGated = blockEnergies.filter { loudnessFromEnergy(it) > ABSOLUTE_GATE_LUFS }
            if (absoluteGated.isEmpty()) return LoudnessAnalysisResult.failure("Loudness abaixo do gate.")
            val relativeGate = loudnessFromEnergy(absoluteGated.average()) + RELATIVE_GATE_LU
            val finalGate = maxOf(ABSOLUTE_GATE_LUFS, relativeGate)
            val gated = absoluteGated.filter { loudnessFromEnergy(it) > finalGate }
            if (gated.isEmpty()) return LoudnessAnalysisResult.failure("Loudness abaixo do gate relativo.")

            val integrated = loudnessFromEnergy(gated.average()).toFloat()
            val truePeak = max(
                samplePeak,
                truePeakEstimators.maxOf { it.peak },
            )
            return LoudnessAnalysisResult(
                integratedLufs = integrated,
                truePeakDbtp = amplitudeToDb(truePeak),
                samplePeakDbfs = amplitudeToDb(samplePeak),
                success = true,
            )
        }

        private fun finishSegment() {
            if (segmentFrames > 0) segmentEnergies += segmentSquaredSum / segmentFrames
            segmentSquaredSum = 0.0
            segmentFrames = 0
        }

        private fun loudnessFromEnergy(energy: Double): Double =
            LOUDNESS_OFFSET + 10.0 * log10(energy.coerceAtLeast(MINIMUM_ENERGY))

        private fun amplitudeToDb(amplitude: Float): Float =
            (20.0 * log10(amplitude.coerceAtLeast(SILENCE_EPSILON).toDouble())).toFloat()
    }

    private class KWeightingFilter(sampleRate: Int) {
        private val shelf = Biquad(highShelfCoefficients(sampleRate))
        private val highPass = Biquad(highPassCoefficients(sampleRate))

        fun process(value: Double): Double = highPass.process(shelf.process(value))
    }

    private class Biquad(private val coefficients: Coefficients) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(input: Double): Double {
            val output = coefficients.b0 * input + coefficients.b1 * x1 + coefficients.b2 * x2 -
                coefficients.a1 * y1 - coefficients.a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = output
            return output
        }
    }

    private class TruePeakEstimator {
        private val history = FloatArray(4)
        private var count = 0
        var peak: Float = 0f
            private set

        fun add(sample: Float) {
            peak = max(peak, abs(sample))
            if (count < history.size) {
                history[count++] = sample
                if (count < history.size) return
            } else {
                history[0] = history[1]
                history[1] = history[2]
                history[2] = history[3]
                history[3] = sample
            }
            for (phase in 1 until TRUE_PEAK_OVERSAMPLING) {
                val t = phase.toFloat() / TRUE_PEAK_OVERSAMPLING
                peak = max(peak, abs(catmullRom(history[0], history[1], history[2], history[3], t)))
            }
        }

        private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5f * (
                2f * p1 + (-p0 + p2) * t +
                    (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                    (-p0 + 3f * p1 - 3f * p2 + p3) * t3
                )
        }
    }

    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    private companion object {
        const val SEGMENTS_PER_SECOND = 10
        const val BLOCK_SEGMENTS = 4
        const val LOUDNESS_OFFSET = -0.691
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = -10.0
        const val MINIMUM_ENERGY = 1e-15
        const val SILENCE_EPSILON = 1e-9f
        const val TRUE_PEAK_OVERSAMPLING = 4
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val RAW_BUFFER_SIZE = 256 * 1_024

        fun highShelfCoefficients(sampleRate: Int): Coefficients {
            val frequency = 1_681.974450955533
            val gainDb = 3.999843853973347
            val quality = 0.7071752369554196
            val k = tan(PI * frequency / sampleRate.coerceAtLeast(1))
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / quality + k * k
            return Coefficients(
                b0 = (vh + vb * k / quality + k * k) / a0,
                b1 = 2.0 * (k * k - vh) / a0,
                b2 = (vh - vb * k / quality + k * k) / a0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / quality + k * k) / a0,
            )
        }

        fun highPassCoefficients(sampleRate: Int): Coefficients {
            val frequency = 38.13547087602444
            val quality = 0.5003270373238773
            val k = tan(PI * frequency / sampleRate.coerceAtLeast(1))
            val a0 = 1.0 + k / quality + k * k
            return Coefficients(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / quality + k * k) / a0,
            )
        }
    }
}
