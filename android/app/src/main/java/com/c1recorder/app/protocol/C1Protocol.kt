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

    /** Fixed identity facts about this specific device, confirmed during investigation. */
    object Identity {
        const val DEVICE_NAME = "搜狗AI录音笔"
    }

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

        /**
         * Raw file-download bytes, notify — NOT opcode-framed like CMD_INDICATE.
         * C1GattCallbackHandler.onCharacteristicChanged passes its payload
         * straight to onFileReceive() with no header at all. Used by
         * C1BleClient.attemptDownload (see docs/ANDROID-HARDWARE-CAPABILITY-TEST.md).
         */
        val FILE_NOTIFY: UUID = uuid16("b001")

        /**
         * Real code role is an OTA firmware-upload channel, not a getFiles
         * trigger (a low-confidence probe writing here had no effect on
         * getFiles' response). Not used in v1 — do not treat as a file API.
         */
        val FILE_TRIGGER: UUID = uuid16("b002")
    }

    object Descriptor {
        /** Standard BLE Client Characteristic Configuration Descriptor — not C1-specific, but kept here so no UUID is hardcoded elsewhere. */
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = uuid16("2902")
    }

    /** Every command frame, in both directions, is exactly this many bytes. */
    const val FRAME_SIZE = 20

    /** App -> Stick request opcodes (C1ActionCreator / C1ActionApp). */
    object Opcode {
        const val CAPABILITY_INIT = 14
        const val GET_STORAGE = 28
        const val GET_SESSIONS = 6
        const val STOP_RECORD = 2 // C1ActionCreator.stopRecord() / .stop() — same opcode, no params
        const val PAUSE_RECORD = 3 // C1ActionCreator.pauseRecord() — no params. No separate resume opcode exists: C1TaskCreator.resumeRealtime() just calls startRealtime() again.
        const val GET_FILES = 7
        const val DOWNLOAD_FILE = 8 // C1ActionCreator.download(sessionId, fileId, start, end, recordType)
        const val DOWNLOAD_STOP = 9 // C1ActionCreator.stopDownload() — no params (App-request namespace; distinct from ResponseOpcode.GET_SESSIONS_CONFIRM below, which is Stick-response namespace)
        const val START_REALTIME = 10

        // Documented in the APK but intentionally not wired into any
        // Repository/BleClient call in this codebase. Kept here only so a
        // future change never needs to re-derive them from the APK — read
        // docs/FINAL-investigation-summary.md before ever using these.
        const val FINISH_FILE = 12 // DESTRUCTIVE: deletes a single recording file
        const val FINISH_SESSION = 13 // DESTRUCTIVE: deletes an entire session
        const val RESTORE_FACTORY_SETTINGS = 25 // DESTRUCTIVE: factory reset (App->Stick namespace; distinct from ResponseOpcode.GET_STORAGE_CONFIRM below)
    }

    /** Stick -> App response opcodes (C1ActionParser / C1ActionStick). Separate numbering space from Opcode above. */
    object ResponseOpcode {
        const val START_IND = 1 // device announces recording started (button or app-triggered)
        const val PAUSE_IND = 2 // confirms pauseRecord()
        const val STOP_IND = 3 // confirms stopRecord()
        const val START_CNF = 4 // the actual ack for startRealtime() — check sessionId != 0 here, not just "got a response"
        const val GET_SESSIONS_CONFIRM = 9
        const val GET_FILES_CONFIRM = 10
        const val FILE_HEADER = 11
        const val FILE_TAIL = 12
        const val GET_STORAGE_CONFIRM = 25
    }

    /** Raw values of DD68/D005 (state). Anything else is genuinely unknown, not a bug. */
    object StateValue {
        const val IDLE = 0x0001
        const val RECORDING = 0x1003
    }

    /** RecordType.INSTANCE.toInt() values from com/sogou/teemo/translatepen/room/RecordType.java. Common.toOtgDir() == "RECORD", matching the known USB folder name. */
    object RecordType {
        const val COMMON = 1
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

    fun buildStartRealtimeFrame(recordType: Int = RecordType.COMMON): ByteArray {
        val buf = buildFrame(Opcode.START_REALTIME)
        buf[2] = recordType.toByte()
        return buf
    }

    fun buildPauseRecordFrame(): ByteArray = buildFrame(Opcode.PAUSE_RECORD)

    fun buildStopRecordFrame(): ByteArray = buildFrame(Opcode.STOP_RECORD)

    fun buildGetFilesFrame(sessionId: Long, recordType: Int = RecordType.COMMON): ByteArray {
        val buf = buildFrame(Opcode.GET_FILES)
        writeLeInt(buf, 2, sessionId)
        buf[6] = recordType.toByte()
        return buf
    }

    /** Non-TR2 layout (this device isn't a TR2): sessionId(4B)+fileId(2B)+start(4B)+end(4B)+recordType(1B). */
    fun buildDownloadFrame(sessionId: Long, fileId: Int, start: Long, end: Long, recordType: Int = RecordType.COMMON): ByteArray {
        val buf = buildFrame(Opcode.DOWNLOAD_FILE)
        writeLeInt(buf, 2, sessionId)
        writeLeShort(buf, 6, fileId)
        writeLeInt(buf, 8, start)
        writeLeInt(buf, 12, end)
        buf[16] = recordType.toByte()
        return buf
    }

    fun buildStopDownloadFrame(): ByteArray = buildFrame(Opcode.DOWNLOAD_STOP)

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

    /**
     * Response to Opcode.START_REALTIME (ResponseOpcode.START_CNF). field2's
     * meaning is not established by any decompiled code — logged for the
     * capability-test record, not otherwise interpreted. Per the official
     * app's own success check, the operation only really started a new
     * recording when sessionId != 0.
     */
    data class StartConfirm(val sessionId: Long, val field2: Long, val recordType: Int)

    fun parseStartConfirm(data: ByteArray): StartConfirm? {
        if (data.size < 11) return null
        return StartConfirm(
            sessionId = leLong(data, 2, 4),
            field2 = leLong(data, 6, 4),
            recordType = data[10].toInt() and 0xFF,
        )
    }

    /**
     * Shared layout of START_IND/PAUSE_IND/STOP_IND (ResponseOpcode.START_IND
     * /PAUSE_IND/STOP_IND): sessionId(4B)@[2,6) + field2(2B)@[6,8). Which
     * opcode arrived (read separately via parseResponseOpcode) says which
     * state transition this is — this parser only decodes the shared fields.
     */
    data class RecordStatusEvent(val sessionId: Long, val field2: Long)

    fun parseRecordStatusEvent(data: ByteArray): RecordStatusEvent? {
        if (data.size < 8) return null
        return RecordStatusEvent(sessionId = leLong(data, 2, 4), field2 = leLong(data, 6, 2))
    }

    data class FileEntry(val fileId: Int, val size: Long)

    /**
     * Response to Opcode.GET_FILES: opcode header at [0,2), then up to two
     * 6-byte entries (fileId 2B LE + size 4B LE) at [2,8) and [8,14).
     * fileId == 65535 is the device's own documented "no file" sentinel and
     * is excluded, not reported as an entry.
     */
    fun parseFiles(data: ByteArray): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        var offset = 2
        while (offset + 6 <= data.size && offset <= 8) {
            val fileId = leInt(data, offset, 2)
            val size = leLong(data, offset + 2, 4)
            if (fileId == 0) break
            if (fileId != 0xFFFF) entries.add(FileEntry(fileId, size))
            offset += 6
        }
        return entries
    }

    /** Response to Opcode.DOWNLOAD_FILE's HEADER (ResponseOpcode.FILE_HEADER): a single meaningful byte at [2] — true means proceed. */
    fun parseFileHeaderOk(data: ByteArray): Boolean? =
        if (data.size > 2) data[2] == 1.toByte() else null

    data class FileTail(val crc16: Int, val eod: Int)

    /**
     * Response TAIL (ResponseOpcode.FILE_TAIL): eod flag at [2], then the CRC
     * field's OWN LENGTH (not fixed at 2 bytes) at [3], then the CRC value at
     * [4, 4+crcLen).
     */
    fun parseFileTail(data: ByteArray): FileTail? {
        if (data.size < 4) return null
        val crcLen = data[3].toInt() and 0xFF
        if (data.size < 4 + crcLen) return null
        val crc = leInt(data, 4, crcLen)
        val eod = data[2].toInt() and 0xFF
        return FileTail(crc16 = crc, eod = eod)
    }

    /**
     * Byte-swap-XOR CRC16, init 0xFFFF, ported line-for-line from
     * com/sogou/crc/CRC16Util.java (calcCRC). Known-answer test vectors are
     * in C1ProtocolTest — computed independently in Python from the same
     * transcribed algorithm, not just re-derived from this Kotlin code.
     */
    object Crc16 {
        fun calc(data: ByteArray, length: Int = data.size, init: Int = 0xFFFF): Int {
            var crc = init
            for (i in 0 until length) {
                val b = data[i].toInt() and 0xFF
                val step1 = ((crc shl 8) and 0xFFFF) or ((crc shr 8) and 0xFF)
                val i4 = step1 xor b
                val i5 = i4 xor ((i4 and 0xFF) shr 4)
                val i6 = i5 xor ((i5 shl 8) shl 4)
                crc = (i6 xor (((i6 and 0xFF) shl 4) shl 1)) and 0xFFFF
            }
            return crc
        }
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
