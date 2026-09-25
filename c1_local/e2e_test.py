#!/usr/bin/env python3
"""End-to-end functional test of the real C1 over BLE: device info, storage,
a real remote start/stop recording (creates one new, kept, real recording),
then re-attempts getSessions/getFiles/download using whatever session that
recording produces.

Every opcode/packet layout comes from decompiled
com.sogou.teemo.bluetooth.compatible.{C1ActionCreator,C1ActionParser,
C1TaskCreator} — see docs/apk-protocol-recovery.md. Nothing here guesses.

Explicitly NOT sent: delRecord, restore factory settings, depair, OTA,
any unknown opcode.
"""
import os
import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient

ADDRESS = os.environ["C1_ADDRESS"]

UUID_BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"
UUID_D001_VERSION = "0000d001-0000-1000-8000-00805f9b34fb"
UUID_D003_SN = "0000d003-0000-1000-8000-00805f9b34fb"
UUID_D005_STATE = "0000d005-0000-1000-8000-00805f9b34fb"
UUID_CMD_A2S = "00002bb1-0000-1000-8000-00805f9b34fb"
UUID_CMD_S2A = "00002bb0-0000-1000-8000-00805f9b34fb"
UUID_FILE_S2A = "0000b001-0000-1000-8000-00805f9b34fb"

OP_APP_CONFIG_INFO = 14
OP_STOP_RECORD = 2
OP_GET_SESSIONS = 6
OP_GET_FILES = 7
OP_DOWNLOAD_FILE = 8
OP_DOWNLOAD_STOP = 9
OP_REALTIME_START = 10
OP_GET_FREE_SIZE = 28

RSP_START_IND = 1
RSP_STOP_IND = 3
RSP_START_CNF = 4
RSP_GET_SESSIONS_CONFIRM = 9
RSP_GET_FILES_CONFIRM = 10
RSP_FILE_HEADER = 11
RSP_FILE_TAIL = 12
RSP_GET_FREE_SIZE_CNF = 25

STATE_NAMES = {
    0x0000: "STATE_INIT", 0x0001: "STATE_POWERED_ON", 0x0002: "STATE_BLE_CONNECTED",
    0x1003: "STATE_RECORDING", 0x1111: "STATE_PAUSED", 0x2222: "STATE_STOPPED",
}

BASE = Path(__file__).resolve().parent.parent
LOG_PATH = BASE / "logs" / "e2e_test.log"
CAPTURE_DIR = BASE / "captures" / "e2e_test"
DOWNLOAD_DIR = BASE / "captures" / "download_test"
for d in (LOG_PATH.parent, CAPTURE_DIR, DOWNLOAD_DIR):
    d.mkdir(parents=True, exist_ok=True)

RUN_TS = datetime.now().strftime("%Y%m%dT%H%M%S")
JSONL_PATH = CAPTURE_DIR / f"run_{RUN_TS}.jsonl"
_log_f = open(LOG_PATH, "a")
_jsonl_f = open(JSONL_PATH, "a")


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
        self.all_indications = []

    def cmd_indicate_handler(self, sender, data: bytearray):
        data = bytes(data)
        opcode = le_int(data[0:2]) if len(data) >= 2 else None
        self.all_indications.append({"time": datetime.now(timezone.utc).isoformat(), "opcode": opcode, "hex": data.hex()})
        log({"phase": "RX-CMD", "raw_hex": data.hex(), "decoded_opcode": opcode, "msg": f"indicate opcode={opcode} len={len(data)}"})
        self.cmd_queue.put_nowait((opcode, data))

    def file_notify_handler(self, sender, data: bytearray):
        data = bytes(data)
        log({"phase": "RX-FILE", "raw_hex": data.hex()[:80], "msg": f"notify len={len(data)}"})
        self.file_queue.put_nowait(data)

    async def send_cmd(self, opcode: int, params: bytes = b"", label=""):
        pkt = build_cmd(opcode, params)
        log({"phase": "TX-CMD", "raw_hex": pkt.hex(), "decoded_opcode": opcode, "msg": f"{label} opcode={opcode} params={params.hex()}"})
        await self.client.write_gatt_char(UUID_CMD_A2S, pkt, response=True)

    async def wait_cmd_response(self, expected_opcodes, timeout=8):
        try:
            while True:
                opcode, data = await asyncio.wait_for(self.cmd_queue.get(), timeout=timeout)
                if opcode in expected_opcodes:
                    return opcode, data
                log({"phase": "WAIT", "msg": f"ignoring opcode {opcode} while waiting for {expected_opcodes}"})
        except asyncio.TimeoutError:
            log({"phase": "WAIT", "result": "TIMEOUT", "msg": f"no response with opcode in {expected_opcodes} within {timeout}s"})
            return None, None

    async def read_state(self):
        raw = await self.client.read_gatt_char(UUID_D005_STATE)
        val = le_int(raw[0:2]) if len(raw) >= 2 else None
        name = STATE_NAMES.get(val, f"UNKNOWN(0x{val:04x})" if val is not None else "?")
        log({"phase": "STATE", "raw_hex": raw.hex(), "decoded": val, "msg": f"d005 = 0x{val:04x} ({name})"})
        return val, name


async def try_get_sessions(sess: DeviceSession, label: str, timeout=8):
    log({"phase": "SESSIONS", "msg": f"=== getSessions attempt: {label} ==="})
    sessions = []
    start_session = 0
    for page in range(10):
        await sess.send_cmd(OP_GET_SESSIONS, le_bytes(start_session, 4), label=f"getSessions(start={start_session})")
        opcode, data = await sess.wait_cmd_response({RSP_GET_SESSIONS_CONFIRM}, timeout=timeout)
        if data is None:
            break
        entries = []
        offset = 2
        while offset <= 74 and offset + 8 <= len(data):
            sid = le_int(data[offset:offset + 4])
            dur = le_int(data[offset + 4:offset + 8])
            if sid == 0:
                break
            entries.append({"sessionId": sid, "duration_raw": dur})
            offset += 8
        log({"phase": "SESSIONS", "raw_hex": data.hex(), "msg": f"page {page}: {entries}"})
        if not entries:
            break
        sessions.extend(entries)
        last_sid = entries[-1]["sessionId"]
        if last_sid == start_session:
            break
        start_session = last_sid
    log({"phase": "SESSIONS", "msg": f"[{label}] total sessions: {len(sessions)} -> {sessions}"})
    return sessions


async def main():
    result = {"run_ts": RUN_TS, "address": ADDRESS}
    log({"phase": "START", "msg": f"Connecting to {ADDRESS}..."})
    async with BleakClient(ADDRESS) as client:
        log({"phase": "START", "msg": f"Connected: {client.is_connected}"})
        sess = DeviceSession(client)
        await client.start_notify(UUID_CMD_S2A, sess.cmd_indicate_handler)
        await client.start_notify(UUID_FILE_S2A, sess.file_notify_handler)
        log({"phase": "START", "msg": "Subscribed 2bb0 indicate + b001 notify"})

        # --- device info ---
        sn = (await client.read_gatt_char(UUID_D003_SN)).decode("ascii", "replace")
        battery = (await client.read_gatt_char(UUID_BATTERY))[0]
        log({"phase": "INFO", "msg": f"SN={sn} battery={battery}%"})
        result["sn"] = sn
        result["battery_before"] = battery
        state_before, state_name_before = await sess.read_state()
        result["state_before"] = {"value": state_before, "name": state_name_before}

        # --- capabilities handshake (proven necessary) ---
        pkt = bytearray(20)
        pkt[0:2] = le_bytes(OP_APP_CONFIG_INFO, 2)
        pkt[2], pkt[3], pkt[4], pkt[5], pkt[8], pkt[9] = 1, 1, 3, 0, 1, 1
        await client.write_gatt_char(UUID_CMD_A2S, bytes(pkt), response=True)
        opcode, data = await sess.wait_cmd_response({14, 26}, timeout=5)
        log({"phase": "HANDSHAKE", "msg": f"ackInfo response opcode={opcode}"})

        # --- storage (proven working) ---
        await sess.send_cmd(OP_GET_FREE_SIZE, label="getFreeSize")
        opcode, data = await sess.wait_cmd_response({RSP_GET_FREE_SIZE_CNF}, timeout=8)
        if data:
            result["storage_before"] = {
                "totalKB": le_int(data[2:6]), "freeKB": le_int(data[6:10]),
                "bytesPerSecond": le_int(data[10:14]), "isFull": data[14] == 1,
            }
            log({"phase": "STORAGE", "msg": f"before recording: {result['storage_before']}"})

        # --- sessions BEFORE recording (baseline, expected empty per prior finding) ---
        result["sessions_before"] = await try_get_sessions(sess, "before recording", timeout=6)

        # --- REAL remote start recording ---
        log({"phase": "RECORD", "msg": "=== Sending startRecord (opcode 10, recordType=1 Common) ==="})
        await sess.send_cmd(OP_REALTIME_START, le_bytes(1, 1), label="startRealtime(Common)")
        opcode, data = await sess.wait_cmd_response({RSP_START_IND, RSP_START_CNF}, timeout=8)
        new_session_id = None
        if data:
            new_session_id = le_int(data[2:6])
            log({"phase": "RECORD", "raw_hex": data.hex(), "msg": f"start response opcode={opcode} sessionId={new_session_id}"})
        result["start_response"] = {"opcode": opcode, "raw_hex": data.hex() if data else None, "sessionId": new_session_id}

        state_recording, state_recording_name = await sess.read_state()
        result["state_during_recording"] = {"value": state_recording, "name": state_recording_name}

        log({"phase": "RECORD", "msg": "Recording for 5 seconds..."})
        await asyncio.sleep(5)

        # --- REAL remote stop recording ---
        log({"phase": "RECORD", "msg": "=== Sending stopRecord (opcode 2) ==="})
        await sess.send_cmd(OP_STOP_RECORD, label="stopRecord")
        opcode, data = await sess.wait_cmd_response({RSP_STOP_IND}, timeout=8)
        if data:
            log({"phase": "RECORD", "raw_hex": data.hex(), "msg": f"stop response opcode={opcode}"})
        result["stop_response"] = {"opcode": opcode, "raw_hex": data.hex() if data else None}

        await asyncio.sleep(2)  # let firmware finalize the file
        state_after, state_after_name = await sess.read_state()
        result["state_after_stop"] = {"value": state_after, "name": state_after_name}

        # --- sessions AFTER recording ---
        result["sessions_after"] = await try_get_sessions(sess, "after recording", timeout=8)

        # --- if we have a session (from start response OR sessions_after), try download ---
        target_session = new_session_id
        if not target_session and result["sessions_after"]:
            target_session = result["sessions_after"][0]["sessionId"]

        if target_session:
            log({"phase": "FILES", "msg": f"=== getFiles for sessionId={target_session} ==="})
            await sess.send_cmd(OP_GET_FILES, le_bytes(target_session, 4) + le_bytes(1, 1), label="getFiles")
            opcode, data = await sess.wait_cmd_response({RSP_GET_FILES_CONFIRM}, timeout=8)
            files = []
            if data:
                offset = 2
                while offset + 6 <= len(data) and offset <= 14:
                    fid = le_int(data[offset:offset + 2])
                    size = le_int(data[offset + 2:offset + 6])
                    if fid in (0, 65535):
                        break
                    files.append({"fileId": fid, "size": size})
                    offset += 6
                log({"phase": "FILES", "raw_hex": data.hex(), "msg": f"files: {files}"})
            result["files_for_new_session"] = files

            if files:
                file_id = files[0]["fileId"]
                log({"phase": "DOWNLOAD", "msg": f"=== startSyncRecord sessionId={target_session} fileId={file_id} start=0 end=160 ==="})
                await sess.send_cmd(OP_DOWNLOAD_FILE,
                                     le_bytes(target_session, 4) + le_bytes(file_id, 2) + le_bytes(0, 4) + le_bytes(160, 4) + le_bytes(1, 1),
                                     label="startSyncRecord(partial)")
                header_opcode, header_data = await sess.wait_cmd_response({RSP_FILE_HEADER}, timeout=8)
                collected = bytearray()
                tail_data = None
                deadline = asyncio.get_event_loop().time() + 10
                while asyncio.get_event_loop().time() < deadline:
                    remaining = deadline - asyncio.get_event_loop().time()
                    if remaining <= 0:
                        break
                    done, pending = await asyncio.wait(
                        [asyncio.ensure_future(sess.file_queue.get()), asyncio.ensure_future(sess.cmd_queue.get())],
                        timeout=remaining, return_when=asyncio.FIRST_COMPLETED)
                    for t in pending:
                        t.cancel()
                    progressed = False
                    for t in done:
                        r = t.result()
                        if isinstance(r, bytes):
                            collected.extend(r)
                            progressed = True
                        else:
                            op, d = r
                            if op == RSP_FILE_TAIL:
                                tail_data = d
                                progressed = True
                    if tail_data is not None or not progressed:
                        break
                log({"phase": "DOWNLOAD", "msg": f"Collected {len(collected)} bytes, tail={tail_data is not None}"})
                result["partial_download"] = {"bytes": len(collected), "tail_hex": tail_data.hex() if tail_data else None}
                if collected:
                    out = DOWNLOAD_DIR / f"e2e_session_{target_session}_file_{file_id}_partial.bin"
                    out.write_bytes(bytes(collected))
                    result["partial_download"]["saved_path"] = str(out)

                await sess.send_cmd(OP_DOWNLOAD_STOP, label="stopDownload")
                await asyncio.sleep(1)
        else:
            log({"phase": "FILES", "msg": "SKIPPED: no session id available from start response or getSessions"})
            result["files_for_new_session"] = []

        await client.stop_notify(UUID_CMD_S2A)
        await client.stop_notify(UUID_FILE_S2A)

    result["all_indications_seen"] = sess.all_indications
    result_path = CAPTURE_DIR / f"result_{RUN_TS}.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    log({"phase": "DONE", "msg": f"Saved to {result_path}"})
    print("\n=== SUMMARY ===")
    print(json.dumps({k: v for k, v in result.items() if k != "all_indications_seen"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
