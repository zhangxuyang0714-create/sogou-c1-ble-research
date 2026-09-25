#!/usr/bin/env python3
"""getSessions just worked and returned 6 real sessions matching USB ground
truth exactly. Entry format corrected: 12 bytes each (sessionId 4B LE +
duration_ms 4B LE + constant=1 4B), not 8 bytes as previously assumed.
Paginate for the rest, then try getFiles on a confirmed-real sessionId.
"""
import os
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient

ADDRESS = os.environ["C1_ADDRESS"]
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

BASE = Path(__file__).resolve().parent.parent
CAPTURE_DIR = BASE / "captures" / "e2e_test"
DOWNLOAD_DIR = BASE / "captures" / "download_test"
for d in (CAPTURE_DIR, DOWNLOAD_DIR):
    d.mkdir(parents=True, exist_ok=True)
RUN_TS = datetime.now().strftime("%Y%m%dT%H%M%S")
JSONL_PATH = CAPTURE_DIR / f"getfiles_real_{RUN_TS}.jsonl"
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
        log({"msg": f"RX indicate opcode={opcode} hex={data.hex()}"})
        self.cmd_q.put_nowait((opcode, data))

    def file_handler(self, sender, data):
        data = bytes(data)
        log({"msg": f"RX file-notify len={len(data)} hex={data.hex()[:60]}"})
        self.file_q.put_nowait(data)

    async def send(self, opcode, params=b"", label=""):
        pkt = build_cmd(opcode, params)
        log({"msg": f"TX {label} opcode={opcode} hex={pkt.hex()}"})
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


def parse_sessions(data):
    """CORRECTED format: 12-byte entries: sessionId(4B LE) + duration_ms(4B LE) + const(4B, =1)."""
    entries = []
    offset = 2
    while offset + 12 <= len(data):
        sid = le_int(data[offset:offset + 4])
        dur_ms = le_int(data[offset + 4:offset + 8])
        const_field = le_int(data[offset + 8:offset + 12])
        if sid == 0:
            break
        entries.append({"sessionId": sid, "sessionId_hex": hex(sid), "duration_ms": dur_ms,
                         "duration_s": round(dur_ms / 1000, 2), "const_field": const_field})
        offset += 12
    return entries


async def main():
    result = {"all_sessions": []}
    async with BleakClient(ADDRESS) as client:
        log({"msg": f"Connected: {client.is_connected}"})
        sess = Sess(client)
        await client.start_notify(UUID_CMD_S2A, sess.cmd_handler)
        await client.start_notify(UUID_FILE_S2A, sess.file_handler)

        pkt = bytearray(20)
        pkt[0:2] = bytes([14, 0])
        pkt[2], pkt[3], pkt[4], pkt[5], pkt[8], pkt[9] = 1, 1, 3, 0, 1, 1
        await client.write_gatt_char(UUID_CMD_A2S, bytes(pkt), response=True)
        log({"msg": "Sent capabilities handshake"})
        await asyncio.sleep(3)
        while not sess.cmd_q.empty():
            sess.cmd_q.get_nowait()

        # Paginate getSessions with CORRECTED parsing
        all_sessions = []
        start_session = 0
        for page in range(10):
            await sess.send(OP_GET_SESSIONS, le_bytes(start_session, 4), label=f"getSessions(start={start_session})")
            opcode, data = await sess.wait({RSP_GET_SESSIONS_CONFIRM}, timeout=8)
            if data is None:
                break
            entries = parse_sessions(data)
            log({"msg": f"page {page}: {len(entries)} sessions: {[(e['sessionId_hex'], e['duration_s']) for e in entries]}"})
            if not entries:
                break
            new_ones = [e for e in entries if e["sessionId"] not in [s["sessionId"] for s in all_sessions]]
            all_sessions.extend(new_ones)
            last_sid = entries[-1]["sessionId"]
            if last_sid == start_session or not new_ones:
                break
            start_session = last_sid
            if len(all_sessions) >= 20:
                break

        log({"msg": f"TOTAL sessions collected: {len(all_sessions)}"})
        for s in all_sessions:
            log({"msg": f"  {s['sessionId_hex']}  duration={s['duration_s']}s"})
        result["all_sessions"] = all_sessions

        # Try getFiles on the smallest confirmed-real session (fast test)
        if all_sessions:
            target = min(all_sessions, key=lambda s: s["duration_ms"])
            log({"msg": f"=== getFiles on confirmed-real session {target['sessionId_hex']} (duration {target['duration_s']}s) ==="})
            await sess.send(OP_GET_FILES, le_bytes(target["sessionId"], 4) + le_bytes(1, 1), label="getFiles")
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
            result["getFiles_target"] = target["sessionId_hex"]
            result["files"] = files

            if files:
                file_id = files[0]["fileId"]
                log({"msg": f"=== startSyncRecord session={target['sessionId_hex']} fileId={file_id} start=0 end=160 ==="})
                await sess.send(OP_DOWNLOAD_FILE,
                                 le_bytes(target["sessionId"], 4) + le_bytes(file_id, 2) + le_bytes(0, 4) + le_bytes(160, 4) + le_bytes(1, 1),
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
                result["partial_download"] = {"bytes": len(collected), "hex_preview": collected[:32].hex(),
                                               "tail_hex": tail_data.hex() if tail_data else None}
                if collected:
                    out = DOWNLOAD_DIR / f"realsession_{target['sessionId']}_file_{file_id}_partial.bin"
                    out.write_bytes(bytes(collected))
                    result["partial_download"]["saved_path"] = str(out)
                await sess.send(OP_DOWNLOAD_STOP, label="stopDownload")
                await asyncio.sleep(1)

        await client.stop_notify(UUID_CMD_S2A)
        await client.stop_notify(UUID_FILE_S2A)

    result_path = CAPTURE_DIR / f"getfiles_real_result_{RUN_TS}.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    log({"msg": f"Saved to {result_path}"})
    print("\n=== SUMMARY ===")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
