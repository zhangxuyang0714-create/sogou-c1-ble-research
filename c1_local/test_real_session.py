#!/usr/bin/env python3
"""Test getSessions/getFiles/startSyncRecord now that a real, physically-
created recording (sessionId 0x6aabb161, 2026-09-18's 17_22_41.WAV) exists
on the device. All opcodes/packet layouts from decompiled APK code.
"""
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient

ADDRESS = "AA:BB:CC:DD:EE:FF"
UUID_D005_STATE = "0000d005-0000-1000-8000-00805f9b34fb"
UUID_CMD_A2S = "00002bb1-0000-1000-8000-00805f9b34fb"
UUID_CMD_S2A = "00002bb0-0000-1000-8000-00805f9b34fb"
UUID_FILE_S2A = "0000b001-0000-1000-8000-00805f9b34fb"

OP_GET_SESSIONS = 6
OP_GET_FILES = 7
OP_DOWNLOAD_FILE = 8
OP_DOWNLOAD_STOP = 9

RSP_GET_SESSIONS_CONFIRM = 9
RSP_GET_FILES_CONFIRM = 10
RSP_FILE_HEADER = 11
RSP_FILE_TAIL = 12

REAL_SESSION_ID = 0x6aabb161  # 17_22_41.WAV, confirmed via live indicate

BASE = Path(__file__).resolve().parent.parent
CAPTURE_DIR = BASE / "captures" / "e2e_test"
DOWNLOAD_DIR = BASE / "captures" / "download_test"
for d in (CAPTURE_DIR, DOWNLOAD_DIR):
    d.mkdir(parents=True, exist_ok=True)
RUN_TS = datetime.now().strftime("%Y%m%dT%H%M%S")
JSONL_PATH = CAPTURE_DIR / f"real_session_{RUN_TS}.jsonl"
_f = open(JSONL_PATH, "a")


def le_bytes(value, length):
    return bytes([(value >> (8 * i)) & 0xFF for i in range(length)])


def le_int(data):
    v = 0
    for i, b in enumerate(data):
        v += (b & 0xFF) << (8 * i)
    return v


def build_cmd(opcode, params=b"", total_len=20):
    buf = bytearray(total_len)
    buf[0:2] = le_bytes(opcode, 2)
    buf[2:2 + len(params)] = params
    return bytes(buf)


def log(event):
    event = {"time": datetime.now(timezone.utc).isoformat(), **event}
    _f.write(json.dumps(event, ensure_ascii=False) + "\n")
    _f.flush()
    print(f"[{event['time']}] {event.get('msg','')}")


class Sess:
    def __init__(self, client):
        self.client = client
        self.cmd_q = asyncio.Queue()
        self.file_q = asyncio.Queue()

    def cmd_handler(self, sender, data):
        data = bytes(data)
        opcode = le_int(data[0:2]) if len(data) >= 2 else None
        log({"src": "RX-CMD", "opcode": opcode, "hex": data.hex(), "msg": f"indicate opcode={opcode} hex={data.hex()}"})
        self.cmd_q.put_nowait((opcode, data))

    def file_handler(self, sender, data):
        data = bytes(data)
        log({"src": "RX-FILE", "msg": f"notify len={len(data)} hex={data.hex()[:60]}"})
        self.file_q.put_nowait(data)

    async def send(self, opcode, params=b"", label=""):
        pkt = build_cmd(opcode, params)
        log({"src": "TX-CMD", "hex": pkt.hex(), "msg": f"{label} opcode={opcode} params={params.hex()}"})
        await self.client.write_gatt_char(UUID_CMD_A2S, pkt, response=True)

    async def wait(self, expected, timeout=8):
        try:
            while True:
                opcode, data = await asyncio.wait_for(self.cmd_q.get(), timeout=timeout)
                if opcode in expected:
                    return opcode, data
                log({"msg": f"ignoring opcode {opcode}"})
        except asyncio.TimeoutError:
            log({"msg": f"TIMEOUT waiting for {expected}"})
            return None, None


async def try_sessions(sess, start_session, label):
    log({"msg": f"=== getSessions(start={start_session}) [{label}] ==="})
    await sess.send(OP_GET_SESSIONS, le_bytes(start_session, 4), label="getSessions")
    opcode, data = await sess.wait({RSP_GET_SESSIONS_CONFIRM}, timeout=8)
    sessions = []
    if data:
        offset = 2
        while offset <= 74 and offset + 8 <= len(data):
            sid = le_int(data[offset:offset + 4])
            dur = le_int(data[offset + 4:offset + 8])
            if sid == 0:
                break
            sessions.append({"sessionId": sid, "sessionId_hex": hex(sid), "duration_raw": dur})
            offset += 8
        log({"msg": f"[{label}] sessions: {sessions}"})
    return sessions


async def main():
    result = {}
    async with BleakClient(ADDRESS) as client:
        log({"msg": f"Connected: {client.is_connected}"})
        sess = Sess(client)
        await client.start_notify(UUID_CMD_S2A, sess.cmd_handler)
        await client.start_notify(UUID_FILE_S2A, sess.file_handler)

        # capabilities handshake (proven necessary)
        pkt = bytearray(20)
        pkt[0:2] = bytes([14, 0])
        pkt[2], pkt[3], pkt[4], pkt[5], pkt[8], pkt[9] = 1, 1, 3, 0, 1, 1
        await client.write_gatt_char(UUID_CMD_A2S, bytes(pkt), response=True)
        log({"msg": "Sent capabilities handshake"})
        # drain the spontaneous STAT stream so it doesn't confuse our session wait
        await asyncio.sleep(3)
        drained = 0
        while not sess.cmd_q.empty():
            sess.cmd_q.get_nowait()
            drained += 1
        log({"msg": f"Drained {drained} spontaneous indications (STAT stream etc.) before proceeding"})

        # Attempt 1: getSessions(start=0) as before
        sessions0 = await try_sessions(sess, 0, "start=0")
        result["sessions_start0"] = sessions0

        # Attempt 2: getSessions using the real known sessionId as the start param
        sessions_real = await try_sessions(sess, REAL_SESSION_ID, "start=real_sessionId")
        result["sessions_start_real"] = sessions_real

        target_session = None
        if sessions0:
            target_session = sessions0[0]["sessionId"]
        elif sessions_real:
            target_session = sessions_real[0]["sessionId"]
        else:
            # fall back to the known-real session id directly for getFiles even if getSessions never confirmed it
            target_session = REAL_SESSION_ID
            log({"msg": f"getSessions still empty both ways; trying getFiles directly with known real sessionId {hex(REAL_SESSION_ID)} anyway"})

        result["target_session"] = target_session
        result["target_session_hex"] = hex(target_session)

        log({"msg": f"=== getFiles sessionId={hex(target_session)} ==="})
        await sess.send(OP_GET_FILES, le_bytes(target_session, 4) + le_bytes(1, 1), label="getFiles")
        opcode, data = await sess.wait({RSP_GET_FILES_CONFIRM}, timeout=8)
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
            log({"msg": f"files: {files}"})
        result["files"] = files

        if files:
            file_id = files[0]["fileId"]
            log({"msg": f"=== startSyncRecord sessionId={hex(target_session)} fileId={file_id} start=0 end=160 ==="})
            await sess.send(OP_DOWNLOAD_FILE,
                             le_bytes(target_session, 4) + le_bytes(file_id, 2) + le_bytes(0, 4) + le_bytes(160, 4) + le_bytes(1, 1),
                             label="startSyncRecord(partial)")
            header_opcode, header_data = await sess.wait({RSP_FILE_HEADER}, timeout=8)
            if header_data:
                log({"msg": f"FILE_HEADER: {header_data.hex()}"})
            collected = bytearray()
            tail_data = None
            deadline = asyncio.get_event_loop().time() + 10
            while asyncio.get_event_loop().time() < deadline:
                remaining = deadline - asyncio.get_event_loop().time()
                if remaining <= 0:
                    break
                done, pending = await asyncio.wait(
                    [asyncio.ensure_future(sess.file_q.get()), asyncio.ensure_future(sess.cmd_q.get())],
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
            log({"msg": f"Partial download: {len(collected)} bytes, tail={'yes' if tail_data else 'no'}"})
            result["partial_download"] = {"bytes": len(collected), "hex": collected.hex(), "tail_hex": tail_data.hex() if tail_data else None}
            if collected:
                out = DOWNLOAD_DIR / f"real_session_{target_session}_file_{file_id}_partial.bin"
                out.write_bytes(bytes(collected))
                result["partial_download"]["saved_path"] = str(out)
            await sess.send(OP_DOWNLOAD_STOP, label="stopDownload")
            await asyncio.sleep(1)
        else:
            log({"msg": "SKIPPED download: no files returned"})

        await client.stop_notify(UUID_CMD_S2A)
        await client.stop_notify(UUID_FILE_S2A)

    result_path = CAPTURE_DIR / f"real_session_result_{RUN_TS}.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    log({"msg": f"Saved to {result_path}"})
    print("\n=== SUMMARY ===")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
