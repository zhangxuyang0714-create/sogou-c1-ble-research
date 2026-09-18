package com.c1recorder.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
