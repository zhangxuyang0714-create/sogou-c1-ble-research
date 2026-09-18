package com.c1recorder.app.protocol

import java.util.UUID

/**
 * Single source of truth for the Sogou C1/C18D BLE protocol.
 *
 * Every UUID, opcode, and byte offset here is transcribed from
 * docs/FINAL-investigation-summary.md and the decompiled APK sources under
 * research/apk/jadx/output/sources/com/sogou/teemo/bluetooth/compatible/
 * (C1ActionCreator, C1ActionParser) — both already verified against the real
 * device. No other file in this app may hardcode a UUID, opcode, or offset;
 * everything must go through here.
 */
object C1Protocol {

    private fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    object Service {
        val BATTERY: UUID = uuid16("180f")
        val VENDOR_CMD: UUID = uuid16("1910")
        val VENDOR_INFO: UUID = uuid16("dd68")

        /** Never wired up in this app — see Characteristic.FILE_NOTIFY / FILE_TRIGGER. */
        val VENDOR_FILE: UUID = uuid16("cc68")
    }

    object Characteristic {
        val BATTERY_LEVEL: UUID = uuid16("2a19")

        /** App -> Stick. Every opcode frame is written here. */
        val CMD_WRITE: UUID = uuid16("2bb1")

        /** Stick -> App, indicate. Must be subscribed before writing CMD_WRITE. */
        val CMD_INDICATE: UUID = uuid16("2bb0")

        val FIRMWARE_VERSION: UUID = uuid16("d001")
        val SERIAL_NUMBER: UUID = uuid16("d003")
        val STATE: UUID = uuid16("d005")

        /** Write-only; ack observed but effect on the device unverified. Not used in v1. */
        val SYNC_TIME: UUID = uuid16("d007")

        /** Always reads constant 0x02; meaning unknown. Not used in v1. */
        val UNKNOWN_D00A: UUID = uuid16("d00a")

        /** Never observed to emit data in testing. Not used in v1. */
        val FILE_NOTIFY: UUID = uuid16("b001")

        /**
         * Real code role is an OTA firmware-upload channel, not a getFiles
         * trigger (a low-confidence probe writing here had no effect on
         * getFiles' response). Not used in v1 — do not treat as a file API.
         */
        val FILE_TRIGGER: UUID = uuid16("b002")
    }

    /** Every command frame, in both directions, is exactly this many bytes. */
    const val FRAME_SIZE = 20

    /** App -> Stick request opcodes (C1ActionCreator / C1ActionApp). */
    object Opcode {
        const val CAPABILITY_INIT = 14
        const val GET_STORAGE = 28
        const val GET_SESSIONS = 6

        // Documented in the APK but intentionally not wired into any
        // Repository/BleClient call in this codebase. Kept here only so a
        // future change never needs to re-derive them from the APK — read
        // docs/FINAL-investigation-summary.md before ever using these.
        const val GET_FILES = 7 // stuck: every tested session returns the "no file" sentinel (fileId=65535)
        const val START_REALTIME = 10 // only returns a real sessionId while already physically recording
        const val FINISH_FILE = 12 // DESTRUCTIVE: deletes a single recording file
        const val FINISH_SESSION = 13 // DESTRUCTIVE: deletes an entire session
        const val RESTORE_FACTORY_SETTINGS = 25 // DESTRUCTIVE: factory reset (App->Stick namespace; distinct from ResponseOpcode.GET_STORAGE_CONFIRM below)
    }

    /** Stick -> App response opcodes (C1ActionParser / C1ActionStick). Separate numbering space from Opcode above. */
    object ResponseOpcode {
        const val GET_STORAGE_CONFIRM = 25
        const val GET_SESSIONS_CONFIRM = 9
    }

    /** Raw values of DD68/D005 (state). Anything else is genuinely unknown, not a bug. */
    object StateValue {
        const val IDLE = 0x0001
        const val RECORDING = 0x1003
    }

    // ---- Frame builders ----

    /**
     * Fixed capability-handshake frame for an account-less device (no bindId,
     * no sgUnionId). Must be sent first, before any other read/write, or
     * every subsequent command gets no response. Byte layout decompiled from
     * C1ActionCreator.ackInfo(null, null), verified byte-for-byte against a
     * real device capture: 0e 00 01 01 03 00 00 00 01 01 00 00 00 00 00 00 00 00 00 00
     */
    fun buildCapabilityInitFrame(): ByteArray {
        val buf = ByteArray(FRAME_SIZE)
        writeLeShort(buf, 0, Opcode.CAPABILITY_INIT)
        buf[2] = 1
        buf[3] = 1
        buf[4] = 3
        buf[5] = 0
        buf[8] = 1
        buf[9] = 1
        return buf
    }

    fun buildGetStorageFrame(): ByteArray = buildFrame(Opcode.GET_STORAGE)

    fun buildGetSessionsFrame(startSessionId: Long): ByteArray {
        val buf = buildFrame(Opcode.GET_SESSIONS)
        writeLeInt(buf, 2, startSessionId)
        return buf
    }

    private fun buildFrame(opcode: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteArray(FRAME_SIZE)
        writeLeShort(buf, 0, opcode)
        params.copyInto(buf, destinationOffset = 2)
        return buf
    }

    // ---- Response parsers ----

    fun parseResponseOpcode(data: ByteArray): Int? =
        if (data.size >= 2) leInt(data, 0, 2) else null

    /** DD68/D003 read: plain ASCII serial number, no framing. */
    fun parseSerialNumber(data: ByteArray): String = data.toString(Charsets.US_ASCII)

    /**
     * DD68/D001 read: 1-byte ASCII prefix + 3-byte little-endian int, e.g.
     * 56 7f 00 00 = "V127" (device's own USB log calls this "V0127").
     */
    fun parseFirmwareVersion(data: ByteArray): String? {
        if (data.size != 4) return null
        val prefix = data[0].toInt().toChar()
        val number = leInt(data, 1, 3)
        return "$prefix$number"
    }

    /** 180F/2A19 read: standard SIG Battery Level, single byte 0-100. */
    fun parseBatteryLevel(data: ByteArray): Int? = data.firstOrNull()?.toInt()?.and(0xFF)

    /** DD68/D005 read: first 2 bytes little-endian. Compare against StateValue.*. */
    fun parseStateRaw(data: ByteArray): Int? =
        if (data.size >= 2) leInt(data, 0, 2) else null

    data class StorageInfo(
        val totalKB: Long,
        val freeKB: Long,
        val bytesPerSecond: Long,
        val isFull: Boolean?,
    )

    /** Response to Opcode.GET_STORAGE, opcode header at [0,2), fields follow at [2,15). */
    fun parseStorageResponse(data: ByteArray): StorageInfo? {
        if (data.size < 14) return null
        val totalKB = leLong(data, 2, 4)
        val freeKB = leLong(data, 6, 4)
        val bytesPerSecond = leLong(data, 10, 4)
        val isFull = if (data.size > 14) data[14] == 1.toByte() else null
        return StorageInfo(totalKB, freeKB, bytesPerSecond, isFull)
    }

    /**
     * One entry of the GET_SESSIONS_CONFIRM response. The third 4-byte field
     * is always observed as 1; its real meaning is not established by any
     * decompiled code or documentation, so it is deliberately NOT named
     * "recordType" or similar here — see docs/FINAL-investigation-summary.md.
     */
    data class SessionEntry(
        val sessionId: Long,
        val durationMs: Long,
        val thirdFieldUnknown: Long,
    )

    /**
     * Response to Opcode.GET_SESSIONS: opcode header at [0,2), then 12-byte
     * entries (sessionId 4B LE + durationMs 4B LE + unknown 4B LE) until a
     * sessionId of 0 or the buffer runs out. Matches
     * c1_local/test_getfiles_real_confirmed.py's parse_sessions, verified
     * against 12 real USB WAV files' durations.
     */
    fun parseSessions(data: ByteArray): List<SessionEntry> {
        val entries = mutableListOf<SessionEntry>()
        var offset = 2
        while (offset + 12 <= data.size) {
            val sessionId = leLong(data, offset, 4)
            val durationMs = leLong(data, offset + 4, 4)
            val thirdField = leLong(data, offset + 8, 4)
            if (sessionId == 0L) break
            entries.add(SessionEntry(sessionId, durationMs, thirdField))
            offset += 12
        }
        return entries
    }

    // ---- Little-endian helpers ----

    fun leInt(data: ByteArray, offset: Int, length: Int): Int = leLong(data, offset, length).toInt()

    fun leLong(data: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in 0 until length) {
            value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    fun writeLeShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    fun writeLeInt(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) {
            buf[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }
}
