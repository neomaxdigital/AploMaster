package com.aploworks.aplomaster.audio

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class BpmDetectorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val detector = BpmDetector(context)

    @Test
    fun detectsControlledClickTracksWithinTolerance() {
        listOf(60, 90, 120, 128, 150).forEach { expectedBpm ->
            val file = createWave("click-$expectedBpm.wav") { index, sampleRate ->
                val beatLength = (sampleRate * 60.0 / expectedBpm).toInt()
                val position = index % beatLength
                val clickLength = sampleRate / 125
                if (position < clickLength) {
                    val decay = 1.0 - position.toDouble() / clickLength
                    (sin(2.0 * PI * 1_500.0 * position / sampleRate) * decay * 28_000).toInt()
                } else {
                    0
                }
            }
            val result = detector.detect(Uri.fromFile(file))
            println("BPM_TEST expected=$expectedBpm actual=${result.bpm} confidence=${result.confidence}")
            assertNotNull("BPM $expectedBpm não foi detectado", result.bpm)
            assertTrue(
                "Esperado $expectedBpm, obtido ${result.bpm}",
                abs(result.bpm!! - expectedBpm) <= 2f,
            )
            assertTrue(result.confidence >= 0.35f)
        }
    }

    @Test
    fun silenceAndNoiseAreNotReportedAsConfidentTempo() {
        val silence = createWave("silence.wav") { _, _ -> 0 }
        var noiseState = 0x12345678
        val noise = createWave("noise.wav") { _, _ ->
            noiseState = noiseState * 1_103_515_245 + 12_345
            ((noiseState ushr 16 and 0x7fff) - 16_384) / 8
        }

        val silenceResult = detector.detect(Uri.fromFile(silence))
        val noiseResult = detector.detect(Uri.fromFile(noise))
        println("BPM_TEST silence confidence=${silenceResult.confidence}")
        println("BPM_TEST noise bpm=${noiseResult.bpm} confidence=${noiseResult.confidence}")
        assertFalse(silenceResult.success)
        assertFalse(noiseResult.success)
        assertTrue(silenceResult.confidence < 0.35f)
        assertTrue(noiseResult.confidence < 0.35f)
    }

    @Test(expected = Exception::class)
    fun invalidFileFailsWithoutProcessCrash() {
        val invalid = File(context.cacheDir, "invalid-bpm-audio.bin").apply {
            writeText("not audio")
        }
        detector.detect(Uri.fromFile(invalid))
    }

    private fun createWave(
        name: String,
        sample: (index: Int, sampleRate: Int) -> Int,
    ): File {
        val sampleRate = 22_050
        val sampleCount = sampleRate * TEST_DURATION_SECONDS
        val dataSize = sampleCount * 2
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
                writeLe16(1)
                writeLe32(sampleRate)
                writeLe32(sampleRate * 2)
                writeLe16(2)
                writeLe16(16)
                writeAscii("data")
                writeLe32(dataSize)
                repeat(sampleCount) { index -> writeLe16(sample(index, sampleRate)) }
            }
        }
    }

    private companion object {
        const val TEST_DURATION_SECONDS = 20
    }
}
