package com.c1recorder.app.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-reference tests: every expected value here is copied from the
 * verified Python scripts in c1_local/ and docs/FINAL-investigation-summary.md,
 * not derived independently. If a Kotlin implementation disagrees with these,
 * the Kotlin code is wrong, not the test.
 */
class C1ProtocolTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    // Test A: capability handshake packet must match the exact captured bytes.
    @Test
    fun capabilityInitFrame_matchesCapturedBytes() {
        val expected = "0e 00 01 01 03 00 00 00 01 01 00 00 00 00 00 00 00 00 00 00"
        assertEquals(expected, hex(C1Protocol.buildCapabilityInitFrame()))
    }

    // Test B: getFreeSize (opcode 28) frame is a 20-byte frame with just the opcode set.
    @Test
    fun getStorageFrame_isOpcode28FrameWithNoParams() {
        val expected = "1c 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
        val frame = C1Protocol.buildGetStorageFrame()
        assertEquals(C1Protocol.FRAME_SIZE, frame.size)
        assertEquals(expected, hex(frame))
    }

    // Test C: getSessions (opcode 6) frame carries the start sessionId as 4-byte LE.
    @Test
    fun getSessionsFrame_encodesStartSessionIdLittleEndian() {
        val frame = C1Protocol.buildGetSessionsFrame(0)
        assertEquals("06 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", hex(frame))

        val frameWithStart = C1Protocol.buildGetSessionsFrame(0x67d1acb0)
        assertEquals("06 00 b0 ac d1 67 00 00 00 00 00 00 00 00 00 00 00 00 00 00", hex(frameWithStart))
    }

    // Test D: 12-byte session entry parser — sessionId(4B) + durationMs(4B) + unknown(4B), NOT 8-byte entries.
    @Test
    fun parseSessions_decodesTwelveByteEntries() {
        // opcode(2B)=9, then one entry: sessionId=0x67d1acb0, durationMs=1740 (1.74s), thirdField=1
        val data = byteArrayOf(
            0x09, 0x00,
            0xb0.toByte(), 0xac.toByte(), 0xd1.toByte(), 0x67,
            0xcc.toByte(), 0x06, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
        )

        val entries = C1Protocol.parseSessions(data)

        assertEquals(1, entries.size)
        assertEquals(0x67d1acb0L, entries[0].sessionId)
        assertEquals(1740L, entries[0].durationMs)
        assertEquals(1L, entries[0].thirdFieldUnknown)
    }

    @Test
    fun parseSessions_stopsAtZeroSessionId() {
        val data = ByteArray(2 + 12) // opcode header + one all-zero entry
        assertEquals(0, C1Protocol.parseSessions(data).size)
    }

    @Test
    fun parseFirmwareVersion_matchesKnownDevice() {
        // 56 7f 00 00 -> prefix 'V' + le_int(0x00007f)=127 -> "V127"
        val data = byteArrayOf(0x56, 0x7f, 0x00, 0x00)
        assertEquals("V127", C1Protocol.parseFirmwareVersion(data))
    }

    @Test
    fun parseFirmwareVersion_returnsNullForWrongLength() {
        assertNull(C1Protocol.parseFirmwareVersion(byteArrayOf(0x56, 0x7f, 0x00)))
    }

    @Test
    fun parseStateRaw_matchesKnownStateValues() {
        assertEquals(C1Protocol.StateValue.IDLE, C1Protocol.parseStateRaw(byteArrayOf(0x01, 0x00)))
        assertEquals(C1Protocol.StateValue.RECORDING, C1Protocol.parseStateRaw(byteArrayOf(0x03, 0x10)))
    }

    @Test
    fun parseStorageResponse_decodesFieldsInDocumentedOrder() {
        // opcode(2B)=25, totalKB=15258988, freeKB=1048576, bytesPerSecond=0, isFull=0
        val data = ByteArray(15)
        C1Protocol.writeLeShort(data, 0, C1Protocol.ResponseOpcode.GET_STORAGE_CONFIRM)
        C1Protocol.writeLeInt(data, 2, 15_258_988L)
        C1Protocol.writeLeInt(data, 6, 1_048_576L)
        C1Protocol.writeLeInt(data, 10, 0L)
        data[14] = 0

        val info = C1Protocol.parseStorageResponse(data)

        assertEquals(15_258_988L, info?.totalKB)
        assertEquals(1_048_576L, info?.freeKB)
        assertEquals(0L, info?.bytesPerSecond)
        assertEquals(false, info?.isFull)
    }

    // --- Hardware Capability Test additions (Tasks 1/2/3/5) ---

    @Test
    fun startRealtimeFrame_encodesOpcode10AndRecordType() {
        val frame = C1Protocol.buildStartRealtimeFrame(C1Protocol.RecordType.COMMON)
        assertEquals("0a 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", hex(frame))
    }

    @Test
    fun pauseAndStopFrames_areBareOpcodesWithNoParams() {
        assertEquals("03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", hex(C1Protocol.buildPauseRecordFrame()))
        assertEquals("02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", hex(C1Protocol.buildStopRecordFrame()))
    }

    @Test
    fun getFilesFrame_encodesSessionIdAndRecordType() {
        val frame = C1Protocol.buildGetFilesFrame(0x67d1acb0, C1Protocol.RecordType.COMMON)
        assertEquals("07 00 b0 ac d1 67 01 00 00 00 00 00 00 00 00 00 00 00 00 00", hex(frame))
    }

    @Test
    fun downloadFrame_encodesAllFiveFieldsInOrder() {
        val frame = C1Protocol.buildDownloadFrame(sessionId = 0x67d1acb0, fileId = 1, start = 0, end = 160, recordType = C1Protocol.RecordType.COMMON)
        assertEquals("08 00 b0 ac d1 67 01 00 00 00 00 00 a0 00 00 00 01 00 00 00", hex(frame))
    }

    @Test
    fun stopDownloadFrame_isBareOpcode9() {
        assertEquals("09 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", hex(C1Protocol.buildStopDownloadFrame()))
    }

    @Test
    fun parseStartConfirm_decodesSessionIdField2AndRecordType() {
        val data = ByteArray(11)
        C1Protocol.writeLeShort(data, 0, C1Protocol.ResponseOpcode.START_CNF)
        C1Protocol.writeLeInt(data, 2, 0x67d1acb0)
        C1Protocol.writeLeInt(data, 6, 42)
        data[10] = C1Protocol.RecordType.COMMON.toByte()

        val confirm = C1Protocol.parseStartConfirm(data)

        assertEquals(0x67d1acb0L, confirm?.sessionId)
        assertEquals(42L, confirm?.field2)
        assertEquals(1, confirm?.recordType)
    }

    @Test
    fun parseStartConfirm_zeroSessionIdMeansNotActuallyStarted() {
        val data = ByteArray(11) // all zero, including sessionId
        assertEquals(0L, C1Protocol.parseStartConfirm(data)?.sessionId)
    }

    @Test
    fun parseRecordStatusEvent_decodesSessionIdAndField2() {
        val data = ByteArray(8)
        C1Protocol.writeLeShort(data, 0, C1Protocol.ResponseOpcode.STOP_IND)
        C1Protocol.writeLeInt(data, 2, 0x67d1acb0)
        C1Protocol.writeLeShort(data, 6, 5)

        val event = C1Protocol.parseRecordStatusEvent(data)

        assertEquals(0x67d1acb0L, event?.sessionId)
        assertEquals(5L, event?.field2)
    }

    @Test
    fun parseFiles_excludesTheNoFileSentinel() {
        val data = ByteArray(14)
        C1Protocol.writeLeShort(data, 0, C1Protocol.ResponseOpcode.GET_FILES_CONFIRM)
        C1Protocol.writeLeShort(data, 2, 0xFFFF)
        C1Protocol.writeLeInt(data, 4, 0)

        assertTrue(C1Protocol.parseFiles(data).isEmpty())
    }

    @Test
    fun parseFiles_decodesARealFileEntry() {
        val data = ByteArray(8)
        C1Protocol.writeLeShort(data, 0, C1Protocol.ResponseOpcode.GET_FILES_CONFIRM)
        C1Protocol.writeLeShort(data, 2, 1)
        C1Protocol.writeLeInt(data, 4, 160)

        val files = C1Protocol.parseFiles(data)

        assertEquals(1, files.size)
        assertEquals(1, files[0].fileId)
        assertEquals(160L, files[0].size)
    }

    @Test
    fun parseFileFrame_handlesStreamingAndSentinel() {
        // Packet 1: 0a00 ffff ... (placeholder only, no sentinel)
        val packet1 = ByteArray(14)
        C1Protocol.writeLeShort(packet1, 0, C1Protocol.ResponseOpcode.GET_FILES_CONFIRM)
        C1Protocol.writeLeShort(packet1, 2, 0xFFFF)
        C1Protocol.writeLeShort(packet1, 8, 0xFFFF)
        val (entries1, sentinel1) = C1Protocol.parseFileFrame(packet1)
        assertTrue(entries1.isEmpty())
        assertEquals(false, sentinel1)

        // Packet 2: 0a00 0100 f4f60000 00000000 ... (fileId=1, duration=63220ms, then fileId=0 sentinel)
        val packet2 = ByteArray(14)
        C1Protocol.writeLeShort(packet2, 0, C1Protocol.ResponseOpcode.GET_FILES_CONFIRM)
        C1Protocol.writeLeShort(packet2, 2, 1)
        C1Protocol.writeLeInt(packet2, 4, 63220L)
        C1Protocol.writeLeShort(packet2, 8, 0) // sentinel
        val (entries2, sentinel2) = C1Protocol.parseFileFrame(packet2)
        assertEquals(1, entries2.size)
        assertEquals(1, entries2[0].fileId)
        assertEquals(63220L, entries2[0].size)
        assertEquals(true, sentinel2)
    }

    @Test
    fun parseFileHeaderOk_readsSingleByteFlag() {
        // Confirmed on real C1 hardware: data[2] == 0 indicates SUCCESS (0b00 00...)
        assertEquals(true, C1Protocol.parseFileHeaderOk(byteArrayOf(0x0b, 0x00, 0x00)))
        assertEquals(false, C1Protocol.parseFileHeaderOk(byteArrayOf(0x0b, 0x00, 0x01)))
        assertNull(C1Protocol.parseFileHeaderOk(byteArrayOf(0x0b, 0x00)))
    }

    @Test
    fun parseFileTail_usesTheDeclaredCrcLengthNotAFixedTwoBytes() {
        // eod=1, crcLen=2, crc16=0xe1f0 (little-endian: f0 e1)
        val data = byteArrayOf(0x0c, 0x00, 0x01, 0x02, 0xf0.toByte(), 0xe1.toByte())
        val tail = C1Protocol.parseFileTail(data)
        assertEquals(0xe1f0, tail?.crc16)
        assertEquals(1, tail?.eod)
    }

    // Crc16: known-answer vectors computed independently in Python from the
    // same transcribed algorithm (com/sogou/crc/CRC16Util.java), not just
    // re-derived from this Kotlin implementation.
    @Test
    fun crc16_matchesIndependentlyComputedVectors() {
        assertEquals(0xFFFF, C1Protocol.Crc16.calc(ByteArray(0)))
        assertEquals(0xE1F0, C1Protocol.Crc16.calc(byteArrayOf(0x00)))
        assertEquals(0xFF00, C1Protocol.Crc16.calc(byteArrayOf(0xFF.toByte())))
        assertEquals(0x29B1, C1Protocol.Crc16.calc("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0xC241, C1Protocol.Crc16.calc(ByteArray(10) { it.toByte() }))
    }

    // parseB001Packet: ground truth is StickWorker.handleData() in the
    // decompiled vendor APK (com/sogou/teemo/translatepen/manager/
    // StickStuff.kt) — [3-byte LE seq][1-byte len][payload]. An earlier
    // version of AndroidC1BleClient treated B001 as headerless raw bytes,
    // which fed the wrong data into the CRC (0x63E9 locally vs. the
    // device's real value) — these tests pin the correct frame shape.

    @Test
    fun parseB001Packet_extractsSeqAndPayload() {
        // seq=1 (LE 01 00 00), len=80, then 80 payload bytes.
        val payload = ByteArray(80) { it.toByte() }
        val packet = byteArrayOf(0x01, 0x00, 0x00, 80.toByte()) + payload
        val parsed = C1Protocol.parseB001Packet(packet)
        assertEquals(1, parsed?.seq)
        assertArrayEquals(payload, parsed?.payload)
    }

    @Test
    fun parseB001Packet_seqZeroIsTheEndOfStreamSentinel() {
        val packet = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x2a)
        val parsed = C1Protocol.parseB001Packet(packet)
        assertEquals(0, parsed?.seq) // caller decides seq==0 means "stop", not this function
    }

    @Test
    fun parseB001Packet_returnsNullForTruncatedOrMalformedPackets() {
        assertNull(C1Protocol.parseB001Packet(byteArrayOf(0x01, 0x00))) // shorter than the 4-byte header itself
        // declares len=80 but only 10 bytes actually follow the header
        assertNull(C1Protocol.parseB001Packet(byteArrayOf(0x01, 0x00, 0x00, 80.toByte()) + ByteArray(10)))
    }

    @Test
    fun parseB001Packet_reconstructsExactBytesAcrossAFullDownload() {
        // Same shape as a real ground-truth recording (RECORD/20260919/
        // 13_30_38.AVC on the device's own USB storage: 538 packets of 80
        // bytes = 43040 bytes, 5.38s mono @ 16kHz/16bit, 4x AVC:PCM ratio).
        // Content here is synthetic (deterministic, not the user's real
        // audio) — this only proves the packet framing/reassembly is
        // lossless, not anything about the audio itself.
        val originalBytes = ByteArray(538 * 80) { (it % 251).toByte() }
        val packets = (1..538).map { i ->
            val seq = i
            val offset = (i - 1) * 80
            byteArrayOf((seq and 0xFF).toByte(), ((seq shr 8) and 0xFF).toByte(), ((seq shr 16) and 0xFF).toByte(), 80.toByte()) +
                originalBytes.copyOfRange(offset, offset + 80)
        }

        val reconstructed = java.io.ByteArrayOutputStream()
        for (packet in packets) {
            val parsed = C1Protocol.parseB001Packet(packet)!!
            assertEquals(80, parsed.payload.size)
            reconstructed.write(parsed.payload)
        }

        assertArrayEquals(originalBytes, reconstructed.toByteArray())
        assertEquals(43040, reconstructed.size())
    }
}
