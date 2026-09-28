package com.aploworks.aplomaster.audio

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aploworks.aplomaster.domain.LoudnessAnalysisResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LoudnessAnalyzerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val analyzer = LoudnessAnalyzer(context)

    @Test
    fun measuresMinus20AndMinus10DbfsSines() {
        val minus20 = analyzer.analyze(Uri.fromFile(createSine("sine-minus20.wav", -20f)))
        val minus10 = analyzer.analyze(Uri.fromFile(createSine("sine-minus10.wav", -10f)))
        println("LOUDNESS_TEST minus20=$minus20")
        println("LOUDNESS_TEST minus10=$minus10")

        assertTrue(minus20.success)
        assertTrue(minus10.success)
        assertEquals(-20f, minus20.samplePeakDbfs!!, 0.2f)
        assertEquals(-10f, minus10.samplePeakDbfs!!, 0.2f)
        assertTrue(minus20.truePeakDbtp!! >= minus20.samplePeakDbfs!! - 0.01f)
        assertTrue(minus10.truePeakDbtp!! >= minus10.samplePeakDbfs!! - 0.01f)
        assertEquals(10f, minus10.integratedLufs!! - minus20.integratedLufs!!, 0.4f)
    }

    @Test
    fun silenceFailsAndNearFullScalePeakIsFinite() {
        val silence = analyzer.analyze(Uri.fromFile(createWave("silence-loudness.wav", 1) { _, _ -> 0 }))
        val nearPeak = analyzer.analyze(Uri.fromFile(createSine("near-peak.wav", -0.2f, 9_973.0)))
        println("LOUDNESS_TEST silence=$silence")
        println("LOUDNESS_TEST nearPeak=$nearPeak")

        assertFalse(silence.success)
        assertTrue(silence.integratedLufs == null)
        assertTrue(nearPeak.success)
        assertNotNull(nearPeak.truePeakDbtp)
        assertEquals(-0.2f, nearPeak.samplePeakDbfs!!, 0.25f)
        assertTrue(nearPeak.truePeakDbtp!! >= nearPeak.samplePeakDbfs!! - 0.01f)
    }

    @Test
    fun stereoUsesBothChannels() {
        val mono = analyzer.analyze(Uri.fromFile(createSine("mono.wav", -20f, channels = 1)))
        val stereo = analyzer.analyze(Uri.fromFile(createSine("stereo.wav", -20f, channels = 2)))
        println("LOUDNESS_TEST mono=$mono")
        println("LOUDNESS_TEST stereo=$stereo")

        assertTrue(mono.success)
        assertTrue(stereo.success)
        assertEquals(3.01f, stereo.integratedLufs!! - mono.integratedLufs!!, 0.35f)
        assertEquals(mono.samplePeakDbfs!!, stereo.samplePeakDbfs!!, 0.1f)
    }

    @Test
    fun automaticGainRespectsLoudnessAndTruePeak() {
        val peakLimited = AutoGainCalculator.calculate(
            LoudnessAnalysisResult(-18f, -4f, -4.5f, true),
        )
        val reduction = AutoGainCalculator.calculate(
            LoudnessAnalysisResult(-10f, -1.5f, -2f, true),
        )
        println("LOUDNESS_TEST gainPeakLimited=${peakLimited.finalGainDb}")
        println("LOUDNESS_TEST gainReduction=${reduction.finalGainDb}")

        assertEquals(3f, peakLimited.finalGainDb, 0.001f)
        assertEquals(-3f, reduction.finalGainDb, 0.001f)
        assertTrue(-4f + peakLimited.finalGainDb <= -1f)
    }

    @Test(expected = Exception::class)
    fun invalidFileFailsWithoutProcessCrash() {
        val invalid = File(context.cacheDir, "invalid-loudness.bin").apply { writeText("not audio") }
        analyzer.analyze(Uri.fromFile(invalid))
    }

    private fun createSine(
        name: String,
        peakDbfs: Float,
        frequency: Double = 1_000.0,
        channels: Int = 1,
    ): File {
        val amplitude = 10.0.pow(peakDbfs / 20.0) * 32767.0
        return createWave(name, channels) { index, sampleRate ->
            (sin(2.0 * PI * frequency * index / sampleRate) * amplitude).toInt()
        }
    }

    private fun createWave(
        name: String,
        channels: Int,
        sample: (index: Int, sampleRate: Int) -> Int,
    ): File {
        val sampleRate = 48_000
        val sampleCount = sampleRate * TEST_DURATION_SECONDS
        val dataSize = sampleCount * channels * 2
        return File(context.cacheDir, name).also { file ->
            FileOutputStream(file).use { output ->
                fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
                fun writeLe16(value: Int) {
                    output.write(value and 0xff)
                    output.write(value ushr 8 and 0xff)
                }
                fun writeLe32(value: Int) {
                    output.write(value and 0xff)
                    output.write(value ushr 8 and 0xff)
                    output.write(value ushr 16 and 0xff)
                    output.write(value ushr 24 and 0xff)
                }
                writeAscii("RIFF")
                writeLe32(36 + dataSize)
                writeAscii("WAVEfmt ")
                writeLe32(16)
                writeLe16(1)
                writeLe16(channels)
                writeLe32(sampleRate)
                writeLe32(sampleRate * channels * 2)
                writeLe16(channels * 2)
                writeLe16(16)
                writeAscii("data")
                writeLe32(dataSize)
                repeat(sampleCount) { index ->
                    val value = sample(index, sampleRate)
                    repeat(channels) { writeLe16(value) }
                }
            }
        }
    }

    private companion object {
        const val TEST_DURATION_SECONDS = 5
    }
}
