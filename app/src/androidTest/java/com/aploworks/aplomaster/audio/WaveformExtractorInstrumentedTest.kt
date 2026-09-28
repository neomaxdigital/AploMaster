package com.aploworks.aplomaster.audio

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class WaveformExtractorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val extractor = WaveformExtractor(context)

    @Test
    fun extractsNormalizedWaveformAndDistinguishesFiles() {
        val steady = createWave("steady.wav") { index, sampleRate ->
            (sin(2.0 * PI * 440.0 * index / sampleRate) * 14_000).toInt()
        }
        val delayed = createWave("delayed.wav") { index, sampleRate ->
            if (index < sampleRate) 0 else (sin(2.0 * PI * 220.0 * index / sampleRate) * 8_000).toInt()
        }

        val first = extractor.extract(Uri.fromFile(steady), pointCount = 400)
        val second = extractor.extract(Uri.fromFile(delayed), pointCount = 400)

        assertEquals(400, first.amplitudes.size)
        assertEquals(400, second.amplitudes.size)
        assertTrue(first.amplitudes.all { it in 0f..1f })
        assertTrue(second.amplitudes.all { it in 0f..1f })
        assertTrue(first.amplitudes.maxOrNull()!! > 0.95f)
        assertNotEquals(first.amplitudes.toList(), second.amplitudes.toList())
        assertTrue(second.amplitudes.take(150).average() < second.amplitudes.takeLast(150).average())
    }

    @Test(expected = Exception::class)
    fun invalidFileFailsWithoutProcessCrash() {
        val invalid = File(context.cacheDir, "invalid-audio.bin").apply { writeText("not audio") }
        extractor.extract(Uri.fromFile(invalid))
    }

    private fun createWave(name: String, sample: (Int, Int) -> Int): File {
        val sampleRate = 22_050
        val seconds = 2
        val sampleCount = sampleRate * seconds
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
}
