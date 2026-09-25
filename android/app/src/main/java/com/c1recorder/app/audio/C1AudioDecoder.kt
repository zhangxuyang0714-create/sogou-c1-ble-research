package com.c1recorder.app.audio

import android.util.Log
import com.airoha.celt2chapi.AirohaCeltApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance audio decoder for Sogou C1 / C18D voice recorders.
 *
 * Audio stream architecture and frame parameters:
 * - Single B001 BLE packet carries 80 compressed bytes = 10ms of audio
 *   (PenTransform.getFrame() returns 10ms for SN prefix "520").
 * - Two consecutive 80B packets (odd + even seq) form one complete
 *   160-byte CELT decoder frame = 20ms (matching StickWorker.handleData()).
 * - PenTransform.getDecodeType() selects celtapi2 (channels = 2).
 * - Each 160B frame decoded with celtDecodeProc(160, channels=2) produces
 *   exactly 640 interleaved stereo samples (320 stereo sample pairs).
 * - Downmixing each stereo pair ((L + R) / 2) produces exactly 320 mono
 *   samples @ 16kHz = 20ms (640 bytes PCM per 160B frame).
 * - Mathematical ratio: 160B AVC -> 640B PCM (exact 4.0x multiplier).
 *   Example: 505,760 bytes AVC = 3,161 frames = 63.22s = 2,023,040 bytes mono PCM.
 *
 * Standard 44-byte RIFF/WAVE headers (16kHz, mono, 16-bit, blockAlign=2,
 * byteRate=32000) are appended to produce standard playable .wav files.
 */
class C1AudioDecoder {

    companion object {
        private const val TAG = "C1AudioDecoder"
        const val SAMPLE_RATE = 16000
        const val STEREO_FRAME_SIZE = 160
        private const val NATIVE_CHANNELS = 2

        @Volatile
        private var isNativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("airoha-celtapi")
                isNativeLibraryLoaded = true
                Log.i(TAG, "libairoha-celtapi.so loaded successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load libairoha-celtapi.so", t)
            }
        }

        fun isAvailable(): Boolean = isNativeLibraryLoaded
    }

    /**
     * Decodes an AVC file to a 16kHz 16-bit WAV file — mono by default,
     * matching the device's own on-device WAV format (see class doc for
     * why the native decode itself is always stereo regardless of this).
     *
     * @param outputStereo write the raw, un-downmixed stereo PCM instead —
     *   diagnostic use only; the normal pipeline always wants mono.
     */
    fun decodeAvcFileToWav(avcFile: File, wavFile: File, outputStereo: Boolean = false): Boolean {
        if (!isAvailable()) {
            Log.e(TAG, "Native CELT decoder library is not available")
            return false
        }
        if (!avcFile.exists() || avcFile.length() == 0L) {
            Log.e(TAG, "AVC input file does not exist or is empty: ${avcFile.absolutePath}")
            return false
        }

        val api = try {
            AirohaCeltApi()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to instantiate AirohaCeltApi", t)
            return false
        }

        val avcId = api.celtDecodeInit(NATIVE_CHANNELS)
        if (avcId < 0) {
            Log.e(TAG, "celtDecodeInit($NATIVE_CHANNELS) failed, returned $avcId")
            return false
        }

        try {
            val pcmStream = ByteArrayOutputStream()
            val buffer = ByteArray(STEREO_FRAME_SIZE)

            FileInputStream(avcFile).use { fis ->
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } > 0) {
                    if (bytesRead < STEREO_FRAME_SIZE) {
                        // Pad a trailing odd packet with zero rather than drop it.
                        buffer.fill(0, bytesRead, STEREO_FRAME_SIZE)
                    }
                    val decodedShorts = api.celtDecodeProc(buffer, STEREO_FRAME_SIZE, avcId, NATIVE_CHANNELS)
                    if (decodedShorts == null || decodedShorts.isEmpty()) continue

                    val samplesToWrite = if (outputStereo) decodedShorts else downmixToMono(decodedShorts)
                    val pcmBytes = ByteArray(samplesToWrite.size * 2)
                    ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samplesToWrite)
                    pcmStream.write(pcmBytes)
                }
            }

            val pcmData = pcmStream.toByteArray()
            if (pcmData.isEmpty()) {
                Log.e(TAG, "Decoded PCM data is empty")
                return false
            }

            val outputChannels = if (outputStereo) 2 else 1
            writeWavFile(pcmData, wavFile, outputChannels, SAMPLE_RATE)
            Log.i(TAG, "Successfully decoded ${avcFile.name} (${avcFile.length()} bytes) -> ${wavFile.name} (${wavFile.length()} bytes, ${pcmData.size / (outputChannels * 2 * SAMPLE_RATE / 1000)} ms)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error during AVC decoding", e)
            return false
        } finally {
            try {
                api.celtDecodeUninit(avcId)
            } catch (t: Throwable) {
                Log.w(TAG, "celtDecodeUninit error", t)
            }
        }
    }

    /** Averages each interleaved (L, R) sample pair down to one mono sample. Odd trailing sample (shouldn't happen — the native call always returns an even count) is dropped. */
    private fun downmixToMono(stereo: ShortArray): ShortArray {
        val pairCount = stereo.size / 2
        val mono = ShortArray(pairCount)
        for (i in 0 until pairCount) {
            val l = stereo[i * 2].toInt()
            val r = stereo[i * 2 + 1].toInt()
            mono[i] = ((l + r) / 2).toShort()
        }
        return mono
    }

    /**
     * Writes raw 16-bit PCM bytes wrapped in a standard 44-byte RIFF/WAVE header.
     */
    fun writeWavFile(pcmData: ByteArray, outputFile: File, channels: Int = 1, sampleRate: Int = 16000) {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2

        val header = ByteArray(44)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalDataLen.toInt())
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))

        // 'fmt ' subchunk
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // Subchunk1Size (16 for PCM)
        buf.putShort(1.toShort()) // AudioFormat (1 for PCM)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(16.toShort()) // BitsPerSample

        // 'data' subchunk
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalAudioLen.toInt())

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            fos.write(header)
            fos.write(pcmData)
            fos.flush()
        }
    }
}
