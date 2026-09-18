#!/usr/bin/env python3
"""Full, systematic real-device verification of the C1 BLE protocol recovered
from static analysis of Sogou AI Recorder APK v1.2.2.

Every opcode/packet layout used here comes from decompiled
com.sogou.teemo.bluetooth.compatible.{C1ActionCreator,C1ActionParser,
C1TaskCreator,StickProtocol} — see docs/apk-protocol-recovery.md. Nothing
in this script guesses at protocol structure.

Read-only / non-destructive operations only:
  - device info (SN, version, battery, state) — plain GATT reads
  - getFreeSize (storage query)
  - getSessions (recording list, with pagination)
  - getFiles (file list for a session)
  - startSyncRecord (download) — small partial test, then full file if that
    succeeds — targets an EXISTING recording, never creates/deletes one
  - stopSync

Explicitly NOT executed: startRecord/stopRecord/pauseRecord/resumeRecord,
delRecord, depair, OTA, restore factory settings. See the final report for
why.

One BLE connection for the whole run, per the task's own instruction.
"""
import asyncio
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient

ADDRESS = "AA:BB:CC:DD:EE:FF"

# --- GATT UUIDs (confirmed on real device + APK cross-reference) ---
UUID_BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"
UUID_D001_VERSION = "0000d001-0000-1000-8000-00805f9b34fb"
UUID_D003_SN = "0000d003-0000-1000-8000-00805f9b34fb"
UUID_D005_STATE = "0000d005-0000-1000-8000-00805f9b34fb"
UUID_D00A = "0000d00a-0000-1000-8000-00805f9b34fb"
UUID_CMD_A2S = "00002bb1-0000-1000-8000-00805f9b34fb"  # write (App -> Stick)
UUID_CMD_S2A = "00002bb0-0000-1000-8000-00805f9b34fb"  # indicate (Stick -> App)
UUID_FILE_S2A = "0000b001-0000-1000-8000-00805f9b34fb"  # notify (file data)
UUID_FILE_A2S = "0000b002-0000-1000-8000-00805f9b34fb"  # write (unused this run)

# --- App -> Stick opcodes (C1ActionCreator.C1ActionApp) ---
OP_SEND_APP_CAPACITIES_CNF = 14
OP_GET_SESSIONS = 6
OP_GET_FILES = 7
OP_DOWNLOAD_FILE = 8
OP_DOWNLOAD_STOP = 9
OP_GET_FREE_SIZE = 28

# --- Stick -> App opcodes (C1ActionParser.C1ActionStick) ---
RSP_GET_SESSIONS_CONFIRM = 9
RSP_GET_FILES_CONFIRM = 10
RSP_FILE_HEADER = 11
RSP_FILE_TAIL = 12
RSP_GET_FREE_SIZE_CNF = 25

# Documented state enum (SDK README / C1 app)
STATE_NAMES = {
    0x0000: "STATE_INIT",
    0x0001: "STATE_POWERED_ON",
    0x0002: "STATE_BLE_CONNECTED",
    0x1003: "STATE_RECORDING",
    0x1111: "STATE_PAUSED",
    0x2222: "STATE_STOPPED",
}

# USB ground truth (from docs/usb-findings.md / docs/protocol-recordings.md)
USB_RECORDINGS = [
    {"path": "20250307/19_00_32", "wav_bytes": 7397804, "avc_bytes": 1849440, "duration_s": 231.2},
    {"path": "20250307/19_10_21", "wav_bytes": 113785644, "avc_bytes": 28446400, "duration_s": 3555.8},
    {"path": "20250312/23_48_00", "wav_bytes": 55724, "avc_bytes": 13920, "duration_s": 1.7},
    {"path": "20250312/23_52_52", "wav_bytes": 5717164, "avc_bytes": 1429280, "duration_s": 178.7},
    {"path": "20250312/23_59_15", "wav_bytes": 154924, "avc_bytes": 38720, "duration_s": 4.8},
    {"path": "20250316/19_08_20", "wav_bytes": 293804, "avc_bytes": 73440, "duration_s": 9.2},
    {"path": "20250520/06_11_50", "wav_bytes": 64684, "avc_bytes": 16160, "duration_s": 2.0},
    {"path": "20250531/10_33_13", "wav_bytes": 3284524, "avc_bytes": 821120, "duration_s": 102.6},
    {"path": "20250924/00_47_45", "wav_bytes": 663724, "avc_bytes": 165920, "duration_s": 20.7},
    {"path": "20250926/15_06_25", "wav_bytes": 1169324, "avc_bytes": 292320, "duration_s": 36.5},
    {"path": "20260203/19_12_34", "wav_bytes": 2065964, "avc_bytes": 516480, "duration_s": 64.6},
]
USB_TOTAL_BYTES = 15 * 1024 * 1024 * 1024  # 14.5 GiB reported by lsblk, rounded

BASE = Path(__file__).resolve().parent.parent
LOG_PATH = BASE / "logs" / "full_device_test.log"
CAPTURE_DIR = BASE / "captures" / "full_device_test"
DOWNLOAD_DIR = BASE / "captures" / "download_test"
for d in (LOG_PATH.parent, CAPTURE_DIR, DOWNLOAD_DIR):
    d.mkdir(parents=True, exist_ok=True)

RUN_TS = datetime.now().strftime("%Y%m%dT%H%M%S")
JSONL_PATH = CAPTURE_DIR / f"run_{RUN_TS}.jsonl"

_log_f = open(LOG_PATH, "a")
_jsonl_f = open(JSONL_PATH, "a")


def crc16(data: bytes, length=None, crc_init=0xFFFF) -> int:
    """Port of com.sogou.crc.CRC16Util.calcCRC (decompiled). Only the final
    16-bit mask matters (matches Java's 32-bit int wraparound semantics for
    this particular bit-twiddling sequence), so intermediate values are left
    as unbounded Python ints and masked only where the Java did."""
    if length is None:
        length = len(data)
    crc = crc_init
    for i in range(length):
        x = (((crc << 8) & 0xFF00) | ((crc >> 8) & 0xFF)) ^ (data[i] & 0xFF)
        y = x ^ ((x & 0xFF) >> 4)
        z = y ^ ((y << 8) << 4)
        crc = (z ^ (((z & 0xFF) << 4) << 1)) & 0xFFFF
    return crc


def le_bytes(value: int, length: int) -> bytes:
    return bytes([(value >> (8 * i)) & 0xFF for i in range(length)])


def le_int(data: bytes) -> int:
    v = 0
    for i, b in enumerate(data):
        v += (b & 0xFF) << (8 * i)
    return v


def build_cmd(opcode: int, params: bytes = b"", total_len=20) -> bytes:
    buf = bytearray(total_len)
    buf[0:2] = le_bytes(opcode, 2)
    buf[2:2 + len(params)] = params
    return bytes(buf)


def log(event: dict):
    event = {"time": datetime.now(timezone.utc).isoformat(), **event}
    line = json.dumps(event, ensure_ascii=False)
    _jsonl_f.write(line + "\n")
    _jsonl_f.flush()
    text = f"[{event['time']}] {event.get('phase','?'):<12} {event.get('msg','')}"
    print(text)
    _log_f.write(text + "\n")
    _log_f.flush()


class DeviceSession:
    def __init__(self, client: BleakClient):
        self.client = client
        self.cmd_queue: asyncio.Queue = asyncio.Queue()
        self.file_queue: asyncio.Queue = asyncio.Queue()

    def cmd_indicate_handler(self, sender, data: bytearray):
        data = bytes(data)
        opcode = le_int(data[0:2]) if len(data) >= 2 else None
        log({"phase": "RX-CMD", "direction": "device->app", "characteristic": "2bb0",
             "raw_hex": data.hex(), "decoded_opcode": opcode, "msg": f"indicate opcode={opcode} len={len(data)}"})
        self.cmd_queue.put_nowait((opcode, data))

    def file_notify_handler(self, sender, data: bytearray):
        data = bytes(data)
        log({"phase": "RX-FILE", "direction": "device->app", "characteristic": "b001",
             "raw_hex": data.hex()[:80] + ("..." if len(data) > 40 else ""),
             "msg": f"notify len={len(data)}"})
        self.file_queue.put_nowait(data)

    async def send_cmd(self, opcode: int, params: bytes = b"", label=""):
        pkt = build_cmd(opcode, params)
        log({"phase": "TX-CMD", "direction": "app->device", "characteristic": "2bb1",
             "raw_hex": pkt.hex(), "decoded_opcode": opcode, "msg": f"{label} opcode={opcode} params={params.hex()}"})
        await self.client.write_gatt_char(UUID_CMD_A2S, pkt, response=True)

    async def wait_cmd_response(self, expected_opcodes, timeout=8):
        try:
            while True:
                opcode, data = await asyncio.wait_for(self.cmd_queue.get(), timeout=timeout)
                if opcode in expected_opcodes:
                    return opcode, data
                log({"phase": "WAIT", "msg": f"ignoring unexpected opcode {opcode} while waiting for {expected_opcodes}"})
        except asyncio.TimeoutError:
            log({"phase": "WAIT", "result": "TIMEOUT", "msg": f"no response with opcode in {expected_opcodes} within {timeout}s"})
            return None, None


async def phase1_device_info(sess: DeviceSession, result: dict):
    log({"phase": "PHASE1", "msg": "=== Device info (plain GATT reads) ==="})

    sn_raw = await sess.client.read_gatt_char(UUID_D003_SN)
    sn = sn_raw.decode("ascii", errors="replace")
    log({"phase": "PHASE1", "characteristic": "d003", "raw_hex": sn_raw.hex(), "decoded": sn, "msg": f"SN = {sn}"})
    result["sn"] = sn
    result["sn_matches_usb"] = (sn == "5200000000000000")

    ver_raw = await sess.client.read_gatt_char(UUID_D001_VERSION)
    if len(ver_raw) == 4:
        prefix = chr(ver_raw[0])
        num = le_int(ver_raw[1:4])
        version = f"{prefix}{num}"
    else:
        version = None
    log({"phase": "PHASE1", "characteristic": "d001", "raw_hex": ver_raw.hex(), "decoded": version, "msg": f"firmware version = {version}"})
    result["firmware_version"] = version
    result["firmware_matches_usb_log"] = (version == "V127")  # USB log said V0127

    batt_raw = await sess.client.read_gatt_char(UUID_BATTERY)
    batt = batt_raw[0] if batt_raw else None
    log({"phase": "PHASE1", "characteristic": "2a19", "raw_hex": batt_raw.hex(), "decoded": batt, "msg": f"battery = {batt}%"})
    result["battery_pct"] = batt

    state_raw = await sess.client.read_gatt_char(UUID_D005_STATE)
    state_val = le_int(state_raw[0:2]) if len(state_raw) >= 2 else None
    state_name = STATE_NAMES.get(state_val, f"UNKNOWN(0x{state_val:04x})" if state_val is not None else "?")
    log({"phase": "PHASE1", "characteristic": "d005", "raw_hex": state_raw.hex(), "decoded": state_val,
         "msg": f"state = 0x{state_val:04x} ({state_name})" if state_val is not None else "state read failed"})
    result["state_raw"] = state_raw.hex()
    result["state_value"] = state_val
    result["state_name"] = state_name

    d00a_raw = await sess.client.read_gatt_char(UUID_D00A)
    log({"phase": "PHASE1", "characteristic": "d00a", "raw_hex": d00a_raw.hex(), "msg": f"d00a = {d00a_raw.hex()}"})
    result["d00a_raw"] = d00a_raw.hex()


async def phase1b_app_config_info(sess: DeviceSession, result: dict):
    """Replicates StickManager.init4C1Type()'s sendAppConfigInfo(true, null)
    call — the real app's first command after subscribing notifications,
    BEFORE any query. For an account-less device (our case: no bindId, no
    sgUnionId), C1ActionCreator.ackInfo(null, null) produces a fixed,
    fully-determined 20-byte packet (decompiled, not guessed)."""
    log({"phase": "PHASE1B", "msg": "=== sendAppConfigInfo / ackInfo(null, null) — opcode 14, real init step this run's first attempt skipped ==="})
    pkt = bytearray(20)
    pkt[0:2] = le_bytes(OP_SEND_APP_CAPACITIES_CNF, 2)
    pkt[2] = 1
    pkt[3] = 1
    pkt[4] = 3
    pkt[5] = 0
    pkt[8] = 1
    pkt[9] = 1
    pkt = bytes(pkt)
    log({"phase": "PHASE1B", "direction": "app->device", "characteristic": "2bb1", "raw_hex": pkt.hex(),
         "msg": f"TX ackInfo(null,null) = {pkt.hex()}"})
    await sess.client.write_gatt_char(UUID_CMD_A2S, pkt, response=True)
    opcode, data = await sess.wait_cmd_response({14, 26}, timeout=5)
    result["app_config_info_response"] = {"opcode": opcode, "raw_hex": data.hex() if data else None}


async def phase2_storage(sess: DeviceSession, result: dict):
    log({"phase": "PHASE2", "msg": "=== getFreeSize (opcode 28) ==="})
    await sess.send_cmd(OP_GET_FREE_SIZE, label="getFreeSize")
    opcode, data = await sess.wait_cmd_response({RSP_GET_FREE_SIZE_CNF})
    if data is None:
        result["storage"] = {"status": "TIMEOUT"}
        return
    total_kb = le_int(data[2:6])
    free_kb = le_int(data[6:10])
    bytes_per_sec = le_int(data[10:14])
    is_full = data[14] == 1 if len(data) > 14 else None
    total_bytes = total_kb * 1024
    free_bytes = free_kb * 1024
    log({"phase": "PHASE2", "raw_hex": data.hex(), "decoded_opcode": opcode,
         "totalKB": total_kb, "freeKB": free_kb, "bytesPerSecond": bytes_per_sec, "isFull": is_full,
         "msg": f"totalKB={total_kb} freeKB={free_kb} bytesPerSecond={bytes_per_sec} isFull={is_full}"})
    result["storage"] = {
        "status": "OK", "totalKB": total_kb, "freeKB": free_kb,
        "bytesPerSecond": bytes_per_sec, "isFull": is_full,
        "total_bytes": total_bytes, "free_bytes": free_bytes,
        "usb_total_bytes_reference": USB_TOTAL_BYTES,
        "plausible_vs_usb": abs(total_bytes - USB_TOTAL_BYTES) < USB_TOTAL_BYTES * 0.15,
    }


async def phase3_sessions(sess: DeviceSession, result: dict):
    log({"phase": "PHASE3", "msg": "=== getSessions (opcode 6), with pagination ==="})
    sessions = []
    start_session = 0
    for page in range(20):
        await sess.send_cmd(OP_GET_SESSIONS, le_bytes(start_session, 4), label=f"getSessions(start={start_session})")
        opcode, data = await sess.wait_cmd_response({RSP_GET_SESSIONS_CONFIRM})
        if data is None:
            break
        entries_this_page = []
        offset = 2
        while offset <= 74 and offset + 8 <= len(data):
            sid = le_int(data[offset:offset + 4])
            dur = le_int(data[offset + 4:offset + 8])
            if sid == 0:
                break
            entries_this_page.append({"sessionId": sid, "duration_raw": dur})
            offset += 8
        log({"phase": "PHASE3", "raw_hex": data.hex(), "page": page,
             "msg": f"page {page}: {len(entries_this_page)} session(s): {entries_this_page}"})
        if not entries_this_page:
            break
        sessions.extend(entries_this_page)
        last_sid = entries_this_page[-1]["sessionId"]
        if last_sid == start_session:
            break
        start_session = last_sid
        if len(sessions) >= 50:
            break

    log({"phase": "PHASE3", "msg": f"Total sessions collected: {len(sessions)}"})
    result["sessions"] = sessions
    result["session_count"] = len(sessions)
    result["session_count_matches_usb"] = (len(sessions) == len(USB_RECORDINGS))


async def phase3b_files(sess: DeviceSession, session_id: int, result: dict):
    log({"phase": "PHASE3B", "msg": f"=== getFiles for sessionId={session_id} (opcode 7) ==="})
    await sess.send_cmd(OP_GET_FILES, le_bytes(session_id, 4) + le_bytes(1, 1), label=f"getFiles({session_id})")
    opcode, data = await sess.wait_cmd_response({RSP_GET_FILES_CONFIRM})
    files = []
    if data is not None:
        offset = 2
        while offset + 6 <= len(data) and offset <= 14:
            file_id = le_int(data[offset:offset + 2])
            size = le_int(data[offset + 2:offset + 6])
            if file_id in (0, 65535):
                break
            files.append({"fileId": file_id, "size": size})
            offset += 6
        log({"phase": "PHASE3B", "raw_hex": data.hex(), "msg": f"files: {files}"})
    else:
        log({"phase": "PHASE3B", "msg": "getFiles: no response"})
    result["files_for_first_session"] = files
    return files


async def phase4_download(sess: DeviceSession, session_id: int, file_id: int, result: dict):
    log({"phase": "PHASE4", "msg": f"=== Partial download test: sessionId={session_id} fileId={file_id} start=0 end=160 ==="})
    await sess.send_cmd(
        OP_DOWNLOAD_FILE,
        le_bytes(session_id, 4) + le_bytes(file_id, 2) + le_bytes(0, 4) + le_bytes(160, 4) + le_bytes(1, 1),
        label="startSyncRecord(partial)",
    )

    header_opcode, header_data = await sess.wait_cmd_response({RSP_FILE_HEADER}, timeout=8)
    if header_data is not None:
        log({"phase": "PHASE4", "raw_hex": header_data.hex(), "msg": f"FILE_HEADER received: {header_data.hex()}"})

    collected = bytearray()
    deadline = asyncio.get_event_loop().time() + 8
    tail_data = None
    while asyncio.get_event_loop().time() < deadline:
        remaining = deadline - asyncio.get_event_loop().time()
        if remaining <= 0:
            break
        done, pending = await asyncio.wait(
            [asyncio.ensure_future(sess.file_queue.get()), asyncio.ensure_future(sess.cmd_queue.get())],
            timeout=remaining, return_when=asyncio.FIRST_COMPLETED,
        )
        for task in pending:
            task.cancel()
        got_something = False
        for task in done:
            result_val = task.result()
            if isinstance(result_val, bytes):
                collected.extend(result_val)
                got_something = True
            else:
                opcode, data = result_val
                if opcode == RSP_FILE_TAIL:
                    tail_data = data
                    got_something = True
        if tail_data is not None:
            break
        if not got_something:
            break

    log({"phase": "PHASE4", "msg": f"Collected {len(collected)} bytes of file data. Tail received: {tail_data is not None}"})
    result["partial_download"] = {
        "bytes_collected": len(collected), "tail_received": tail_data is not None,
        "tail_hex": tail_data.hex() if tail_data else None,
        "data_hex_preview": collected[:32].hex(),
    }
    if collected:
        out_path = DOWNLOAD_DIR / f"partial_session_{session_id}_file_{file_id}.bin"
        out_path.write_bytes(bytes(collected))
        log({"phase": "PHASE4", "msg": f"Saved partial data to {out_path}"})
        result["partial_download"]["saved_path"] = str(out_path)
    return len(collected) > 0


async def phase5_stop_sync(sess: DeviceSession, result: dict):
    log({"phase": "PHASE5", "msg": "=== stopSync (opcode 9) ==="})
    await sess.send_cmd(OP_DOWNLOAD_STOP, label="stopDownloadFile/stopSync")
    await asyncio.sleep(2)
    log({"phase": "PHASE5", "msg": "stopSync sent, no specific response opcode documented for it; proceeding"})
    result["stop_sync_sent"] = True


async def main():
    result = {"run_ts": RUN_TS, "address": ADDRESS}
    log({"phase": "START", "msg": f"Connecting to {ADDRESS}..."})
    async with BleakClient(ADDRESS) as client:
        log({"phase": "START", "msg": f"Connected: {client.is_connected}"})
        sess = DeviceSession(client)
        await client.start_notify(UUID_CMD_S2A, sess.cmd_indicate_handler)
        await client.start_notify(UUID_FILE_S2A, sess.file_notify_handler)
        log({"phase": "START", "msg": "Subscribed to 2bb0 (indicate, cmd responses) and b001 (notify, file data)"})

        await phase1_device_info(sess, result)
        await phase1b_app_config_info(sess, result)
        await phase2_storage(sess, result)
        await phase3_sessions(sess, result)

        if result.get("sessions"):
            first_session = result["sessions"][0]["sessionId"]
            files = await phase3b_files(sess, first_session, result)
            if files:
                file_id = files[0]["fileId"]
                ok = await phase4_download(sess, first_session, file_id, result)
                result["partial_download_succeeded"] = ok
            else:
                log({"phase": "PHASE4", "msg": "SKIPPED: no fileId discovered from getFiles"})
                result["partial_download_succeeded"] = False
        else:
            log({"phase": "PHASE3B", "msg": "SKIPPED: no sessions returned, cannot test getFiles/download"})
            result["partial_download_succeeded"] = False

        await phase5_stop_sync(sess, result)

        await client.stop_notify(UUID_CMD_S2A)
        await client.stop_notify(UUID_FILE_S2A)

    result_path = CAPTURE_DIR / f"result_{RUN_TS}.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    log({"phase": "DONE", "msg": f"Full result saved to {result_path}"})
    print("\n=== SUMMARY ===")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
