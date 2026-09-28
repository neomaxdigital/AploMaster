package com.aploworks.aplomaster.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aploworks.aplomaster.domain.WaveformData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class WaveformExtractor(private val context: Context) {
    fun extract(
        uri: Uri,
        pointCount: Int = DEFAULT_POINT_COUNT,
        isCancelled: () -> Boolean = { false },
    ): WaveformData {
        require(pointCount > 0)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = findAudioTrack(extractor)
            check(trackIndex >= 0) { "O arquivo não contém uma faixa de áudio compatível." }
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Formato de áudio desconhecido.")
            val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            if (mimeType == "audio/raw") {
                return extractRawPcm(extractor, inputFormat, durationUs, pointCount, isCancelled)
            }
            codec = MediaCodec.createDecoderByType(mimeType).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val envelope = FloatArray(pointCount)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channelCount = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputEnded) {
                if (isCancelled() || Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Análise de waveform cancelada.")
                }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Buffer de entrada indisponível.")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = outputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        pcmEncoding = outputFormat.intOrDefault(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        codec.getOutputBuffer(outputIndex)?.let { buffer ->
                            if (bufferInfo.size > 0) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                accumulate(
                                    buffer.slice().order(ByteOrder.LITTLE_ENDIAN),
                                    bufferInfo.presentationTimeUs,
                                    durationUs,
                                    sampleRate,
                                    channelCount,
                                    pcmEncoding,
                                    envelope,
                                )
                            }
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            return WaveformData(normalize(envelope))
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun extractRawPcm(
        extractor: MediaExtractor,
        format: MediaFormat,
        durationUs: Long,
        pointCount: Int,
        isCancelled: () -> Boolean,
    ): WaveformData {
        val envelope = FloatArray(pointCount)
        val sampleRate = format.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
        val channelCount = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        val pcmEncoding = format.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val buffer = ByteBuffer.allocateDirect(RAW_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            if (isCancelled() || Thread.currentThread().isInterrupted) {
                throw InterruptedException("Análise de waveform cancelada.")
            }
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            accumulate(
                buffer,
                extractor.sampleTime.coerceAtLeast(0L),
                durationUs,
                sampleRate,
                channelCount,
                pcmEncoding,
                envelope,
            )
            extractor.advance()
        }
        return WaveformData(normalize(envelope))
    }

    private fun accumulate(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        durationUs: Long,
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int,
        envelope: FloatArray,
    ) {
        val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * channelCount.coerceAtLeast(1)
        val frameCount = buffer.remaining() / frameSize
        if (frameCount <= 0) return
        val stride = max(1, frameCount / MAX_FRAMES_PER_BUFFER)
        var frameIndex = 0
        while (frameIndex < frameCount) {
            var combinedAmplitude = 0f
            repeat(channelCount.coerceAtLeast(1)) { channel ->
                val offset = frameIndex * frameSize + channel * bytesPerSample
                val amplitude = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    abs(buffer.getFloat(offset)).coerceIn(0f, 1f)
                } else {
                    abs(buffer.getShort(offset).toInt()) / 32768f
                }
                combinedAmplitude += amplitude
            }
            combinedAmplitude /= channelCount.coerceAtLeast(1)
            val timeUs = presentationTimeUs + frameIndex * 1_000_000L / sampleRate.coerceAtLeast(1)
            val bucket = ((timeUs.toDouble() / durationUs) * envelope.size)
                .toInt()
                .coerceIn(0, envelope.lastIndex)
            envelope[bucket] = max(envelope[bucket], combinedAmplitude)
            frameIndex += stride
        }
    }

    private fun normalize(values: FloatArray): FloatArray {
        val peak = values.maxOrNull()?.takeIf { it > MINIMUM_PEAK } ?: return values
        return FloatArray(values.size) { index -> (values[index] / peak).coerceIn(0f, 1f) }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: -1

    private fun MediaFormat.intOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    private companion object {
        const val DEFAULT_POINT_COUNT = 640
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_FRAMES_PER_BUFFER = 2_048
        const val RAW_BUFFER_SIZE = 256 * 1_024
        const val MINIMUM_PEAK = 0.0001f
    }
}
