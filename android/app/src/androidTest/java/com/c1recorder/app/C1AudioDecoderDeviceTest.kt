package com.c1recorder.app

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airoha.celt2chapi.AirohaCeltApi
import com.c1recorder.app.audio.C1AudioDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class C1AudioDecoderDeviceTest {

    private val tag = "C1AudioDecoderDeviceTest"

    @Test
    fun testAirohaCeltDecoder_loadsAndDecodesAvcFile() {
        val isAvail = C1AudioDecoder.isAvailable()
        Log.i(tag, "C1AudioDecoder.isAvailable() = $isAvail")
        assertTrue("C1AudioDecoder native library should be available", isAvail)

        val api = AirohaCeltApi()
        val avcId = api.celtDecodeInit(2)
        assertTrue("celtDecodeInit(2) should return non-negative avcId, got $avcId", avcId >= 0)
        api.celtDecodeUninit(avcId)

        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val avcFile = File(appContext.cacheDir, "sample_test.avc")

        testContext.assets.open("sample_session.avc").use { input ->
            FileOutputStream(avcFile).use { output -> input.copyTo(output) }
        }
        assertEquals("Asset size should match expected 56,000 bytes", 56000L, avcFile.length())

        val wavFileMono = File(appContext.cacheDir, "sample_test_mono.wav")
        if (wavFileMono.exists()) wavFileMono.delete()

        val decoder = C1AudioDecoder()
        val successMono = decoder.decodeAvcFileToWav(avcFile, wavFileMono)
        assertTrue("Mono decoding should succeed", successMono)
        assertTrue("Mono WAV file should exist", wavFileMono.exists())

        assertTrue("Generated WAV size should be valid and >= 100,000 bytes", wavFileMono.length() >= 100000L)

        val bytes = wavFileMono.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes.sliceArray(0..3)))
        assertEquals("totalDataLen", bytes.size - 8, buf.getInt(4))
        assertEquals("WAVE", String(bytes.sliceArray(8..11)))
        assertEquals("fmt ", String(bytes.sliceArray(12..15)))
        assertEquals("Subchunk1Size", 16, buf.getInt(16))
        assertEquals("AudioFormat (1=PCM)", 1.toShort(), buf.getShort(20))
        assertEquals("Channels (1=Mono)", 1.toShort(), buf.getShort(22))
        assertEquals("SampleRate", 16000, buf.getInt(24))
        assertEquals("ByteRate", 32000, buf.getInt(28))
        assertEquals("BlockAlign", 2.toShort(), buf.getShort(32))
        assertEquals("BitsPerSample", 16.toShort(), buf.getShort(34))
        assertEquals("data", String(bytes.sliceArray(36..39)))
        assertEquals("Subchunk2Size (PCM len)", bytes.size - 44, buf.getInt(40))

        val pcmData = bytes.sliceArray(44 until bytes.size)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(pcmData)
            .joinToString("") { "%02x".format(it) }
        Log.i(tag, "sample_session.avc (56KB) -> mono PCM ${pcmData.size} bytes, SHA256: $sha256")
    }

    @Test
    fun testAirohaCeltDecoder_5sRecording_exactGroundTruth() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val fiveSecBytes = testContext.assets.open("real_5s.avc").use { it.readBytes() }

        // 40,480 bytes AVC = 506 packets * 80 bytes = 253 decoder frames * 160 bytes.
        // 253 frames * 20ms = 5060ms (5.06s).
        // 253 frames * 320 mono samples * 2 bytes/sample = 161,920 bytes PCM (40,480 * 4).
        assertEquals(40480, fiveSecBytes.size)

        val avcFile = File(appContext.cacheDir, "test_5s.avc")
        avcFile.writeBytes(fiveSecBytes)

        val wavFile = File(appContext.cacheDir, "test_5s.wav")
        if (wavFile.exists()) wavFile.delete()

        val decoder = C1AudioDecoder()
        val success = decoder.decodeAvcFileToWav(avcFile, wavFile)
        assertTrue("5s decoding should succeed", success)
        assertTrue("5s WAV file should exist", wavFile.exists())

        assertEquals("5s WAV file size must be 161920 bytes PCM + 44 bytes header = 161964 bytes", 161964L, wavFile.length())

        val bytes = wavFile.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("Channels", 1.toShort(), buf.getShort(22))
        assertEquals("SampleRate", 16000, buf.getInt(24))
        assertEquals("BitsPerSample", 16.toShort(), buf.getShort(34))
        assertEquals("PCM data size", 161920, buf.getInt(40))

        val pcmData = bytes.sliceArray(44 until bytes.size)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(pcmData)
            .joinToString("") { "%02x".format(it) }
        Log.i(tag, "5s AVC (40,480 bytes) -> mono PCM 161,920 bytes (5.06s), SHA256: $sha256")
    }

    /**
     * Frame-level diagnostic for the "928000 extra PCM bytes" investigation
     * (see chat log / final report). Not a pass/fail test — it writes a full
     * per-call sample-count trace to a file on external storage (logcat is
     * not reliably readable on this test device) for offline analysis.
     *
     * Input: real AVC files pulled from a live BLE download of known-good
     * recordings (byte-identical to the device's own USB copy — confirmed by
     * size match in the same investigation), pushed as .avc files under
     * /sdcard/c1_investigation before running this test.
     */
    @Test
    fun frameLevelAnalysis_realRecordings() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val inputDir = File(appContext.filesDir, "c1_investigation")
        inputDir.mkdirs()
        for (name in listOf("real_5s.avc", "real_63s.avc")) {
            val dest = File(inputDir, name)
            if (!dest.exists()) {
                try {
                    val testContext = InstrumentationRegistry.getInstrumentation().context
                    testContext.assets.open(name).use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Asset $name not found: ${e.message}")
                }
            }
        }
        var avcFiles = inputDir.listFiles { f -> f.extension == "avc" }?.sortedBy { it.name } ?: emptyList()
        if (avcFiles.isEmpty()) {
            // Populate sample from test asset so this test is self-contained
            val testContext = InstrumentationRegistry.getInstrumentation().context
            val sampleAsset = File(inputDir, "asset_sample_7s.avc")
            testContext.assets.open("sample_session.avc").use { input ->
                FileOutputStream(sampleAsset).use { output -> input.copyTo(output) }
            }
            avcFiles = listOf(sampleAsset)
        }

        val report = StringBuilder()

        for (avcFile in avcFiles) {
            val bytes = avcFile.readBytes()
            report.appendLine("=== ${avcFile.name} (${bytes.size} bytes) ===")

            for (channels in intArrayOf(1, 2)) {
                val frameSize = if (channels == 2) 160 else 80
                val frameCount = bytes.size / frameSize
                val remainder = bytes.size % frameSize

                val api = AirohaCeltApi()
                val avcId = api.celtDecodeInit(channels)
                val sampleCounts = IntArray(frameCount)
                var totalSamples = 0
                var offset = 0
                for (i in 0 until frameCount) {
                    val chunk = bytes.copyOfRange(offset, offset + frameSize)
                    val decoded = api.celtDecodeProc(chunk, frameSize, avcId, channels)
                    val n = decoded?.size ?: 0
                    sampleCounts[i] = n
                    totalSamples += n
                    offset += frameSize
                }
                api.celtDecodeUninit(avcId)

                val histogram = sampleCounts.toList().groupingBy { it }.eachCount().toSortedMap()
                val expectedSamplesPerFrame = if (channels == 2) 320 else 160
                val expectedTotal = frameCount * expectedSamplesPerFrame

                report.appendLine("  channels=$channels frameSize=$frameSize frameCount=$frameCount remainderBytes=$remainder")
                report.appendLine("    totalSamples=$totalSamples expectedTotal(@${expectedSamplesPerFrame}/frame)=$expectedTotal diff=${totalSamples - expectedTotal}")
                report.appendLine("    totalPcmBytes=${totalSamples * 2} expectedPcmBytes=${expectedTotal * 2}")
                report.appendLine("    histogram(sampleCount -> frameOccurrences)=$histogram")
                report.appendLine("    first20=${sampleCounts.take(20)}")
                report.appendLine("    last20=${sampleCounts.takeLast(20)}")

                // Positions where the count differs from the modal (most common) value.
                val modal = histogram.maxByOrNull { it.value }?.key
                val anomalyIndices = sampleCounts.indices.filter { sampleCounts[it] != modal }
                report.appendLine("    modalValue=$modal anomalyCount=${anomalyIndices.size}")
                if (anomalyIndices.isNotEmpty()) {
                    report.appendLine("    first30AnomalyIndices=${anomalyIndices.take(30)}")
                    // Gaps between consecutive anomaly indices, to check for a fixed stride (e.g. every 2nd/3rd frame).
                    val gaps = anomalyIndices.zipWithNext { a, b -> b - a }
                    report.appendLine("    anomalyIndexGapHistogram=${gaps.groupingBy { it }.eachCount().toSortedMap()}")
                }
            }
            report.appendLine()
        }

        val reportFile = File(appContext.filesDir, "frame_analysis_report.txt")
        reportFile.writeText(report.toString())
        Log.i(tag, "Wrote frame analysis report to ${reportFile.absolutePath} (${reportFile.length()} bytes)")
        // Deliberately no assertions here — this test's job is to produce the report file.
    }

    /**
     * The actual regression test for the fix: decodeAvcFileToWav() on two
     * real recordings must produce exactly the PCM byte count and duration
     * implied by the AVC:PCM 4x ratio established from ground-truth USB
     * files (RECORD/20260919/{10_44_36,12_04_12}.{WAV,AVC} — see chat log),
     * not the ~1.46x-too-much this decoder used to produce when driven with
     * channels=1.
     */
    @Test
    fun decodeAvcFileToWav_matchesUsbGroundTruthExactly() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val inputDir = File(appContext.filesDir, "c1_investigation")
        inputDir.mkdirs()

        for (name in listOf("real_5s.avc", "real_63s.avc")) {
            val dest = File(inputDir, name)
            if (!dest.exists()) {
                try {
                    testContext.assets.open(name).use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Asset $name not found: ${e.message}")
                }
            }
        }

        data class Case(val avcName: String, val avcBytes: Long, val expectedPcmBytes: Long, val expectedDurationMs: Long)
        val cases = listOf(
            // real_5s.avc: session 2026-09-19 12:04:12. Expected PCM = AVC bytes * 4
            // (established AVC:PCM ratio, mono 16kHz/16bit — see chat log).
            Case("real_5s.avc", 40480L, 161920L, 5060L),
            // real_63s.avc: session 2026-09-19 10:44:36, USB ground truth 505760-byte AVC / 2023040-byte PCM.
            Case("real_63s.avc", 505760L, 2023040L, 63220L),
        )

        for (case in cases) {
            val avcFile = File(inputDir, case.avcName)
            assertTrue("External ground truth file must exist: ${avcFile.absolutePath}", avcFile.exists())
            assertEquals("AVC file size", case.avcBytes, avcFile.length())

            val wavFile = File(appContext.cacheDir, "verify_${case.avcName}.wav")
            if (wavFile.exists()) wavFile.delete()

            val decoder = C1AudioDecoder()
            assertTrue("decode should succeed for ${case.avcName}", decoder.decodeAvcFileToWav(avcFile, wavFile))

            val bytes = wavFile.readBytes()
            val dataSize = java.nio.ByteBuffer.wrap(bytes, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong()
            val channels = java.nio.ByteBuffer.wrap(bytes, 22, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
            val sampleRate = java.nio.ByteBuffer.wrap(bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int

            assertEquals("${case.avcName}: output should be mono", 1, channels)
            assertEquals("${case.avcName}: sample rate", 16000, sampleRate)
            assertEquals("${case.avcName}: PCM data size", case.expectedPcmBytes, dataSize)

            val durationMs = dataSize * 1000 / (sampleRate * 2)
            Log.i(tag, "${case.avcName}: dataSize=$dataSize durationMs=$durationMs (expected ~${case.expectedDurationMs})")
        }
    }

    @Test
    fun populateGroundTruthRecordingsForApp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val dirs = listOfNotNull(
            appContext.getExternalFilesDir("recordings"),
            File(appContext.filesDir, "recordings"),
        )
        for (dir in dirs) {
            dir.mkdirs()
        }

        // 12:04:12 -> 0x6aae09bc = 1789790652
        // 10:44:36 -> 0x6aadf714 = 1789785876
        val map = listOf(
            Pair("real_5s.avc", 1789790652L),
            Pair("real_63s.avc", 1789785876L),
        )
        val decoder = C1AudioDecoder()
        for ((assetName, sessionId) in map) {
            val avcBytes = testContext.assets.open(assetName).use { it.readBytes() }
            val tempAvc = File(appContext.cacheDir, "temp_${sessionId}.avc")
            tempAvc.writeBytes(avcBytes)

            val tempWav = File(appContext.cacheDir, "temp_${sessionId}.wav")
            if (tempWav.exists()) tempWav.delete()

            val success = decoder.decodeAvcFileToWav(tempAvc, tempWav)
            assertTrue("Decoding $assetName must succeed", success)

            val wavBytes = tempWav.readBytes()
            val pcmBytes = wavBytes.sliceArray(44 until wavBytes.size)
            val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(pcmBytes)
                .joinToString("") { "%02x".format(it) }
            Log.i(tag, "GroundTruth $assetName (session $sessionId): ${pcmBytes.size} bytes PCM, SHA256=$sha256")

            for (dir in dirs) {
                val avcDest = File(dir, "session_${sessionId}_1.avc")
                avcDest.writeBytes(avcBytes)
                val wavDest = File(dir, "session_${sessionId}_1.wav")
                wavDest.writeBytes(wavBytes)
                Log.i(tag, "Populated ${wavDest.absolutePath} (${wavDest.length()} bytes)")
            }
        }
    }
}
