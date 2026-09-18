#!/usr/bin/env python3
"""Test whether syncing time (writing the current Unix timestamp directly
to the candidate charSyncTime characteristic, d007) changes getSessions'
behavior. Per decompiled StickTask.java, setTime() does NOT go through the
opcode/CMD packet system — it's a raw 4-byte little-endian Unix timestamp
written directly to charSyncTime, with WRITE_TYPE_DEFAULT (response=True).
d007 is our best-evidenced candidate for charSyncTime (only other
write-capable characteristic in the dd68/CONFIG service besides d007
itself... i.e. it's the one candidate, since d009 which might be the other
CONFIG write field is absent on this hardware).
"""
import asyncio
import time
import json
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient

ADDRESS = "AA:BB:CC:DD:EE:FF"
UUID_D005_STATE = "0000d005-0000-1000-8000-00805f9b34fb"
UUID_D007_SYNCTIME_CANDIDATE = "0000d007-0000-1000-8000-00805f9b34fb"
UUID_CMD_A2S = "00002bb1-0000-1000-8000-00805f9b34fb"
UUID_CMD_S2A = "00002bb0-0000-1000-8000-00805f9b34fb"
UUID_FILE_S2A = "0000b001-0000-1000-8000-00805f9b34fb"

OP_GET_SESSIONS = 6
RSP_GET_SESSIONS_CONFIRM = 9

BASE = Path(__file__).resolve().parent.parent
CAPTURE_DIR = BASE / "captures" / "e2e_test"
CAPTURE_DIR.mkdir(parents=True, exist_ok=True)
RUN_TS = datetime.now().strftime("%Y%m%dT%H%M%S")
JSONL_PATH = CAPTURE_DIR / f"synctime_test_{RUN_TS}.jsonl"
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


async def main():
    result = {}
    async with BleakClient(ADDRESS) as client:
        log({"msg": f"Connected: {client.is_connected}"})
        cmd_q = asyncio.Queue()

        def cmd_handler(sender, data):
            data = bytes(data)
            opcode = le_int(data[0:2]) if len(data) >= 2 else None
            log({"msg": f"RX indicate opcode={opcode} hex={data.hex()}"})
            cmd_q.put_nowait((opcode, data))

        await client.start_notify(UUID_CMD_S2A, cmd_handler)
        await client.start_notify(UUID_FILE_S2A, lambda s, d: log({"msg": f"RX file-notify len={len(d)}"}))

        # capabilities handshake (proven necessary)
        pkt = bytearray(20)
        pkt[0:2] = bytes([14, 0])
        pkt[2], pkt[3], pkt[4], pkt[5], pkt[8], pkt[9] = 1, 1, 3, 0, 1, 1
        await client.write_gatt_char(UUID_CMD_A2S, bytes(pkt), response=True)
        log({"msg": "Sent capabilities handshake"})
        await asyncio.sleep(3)
        drained = 0
        while not cmd_q.empty():
            cmd_q.get_nowait()
            drained += 1
        log({"msg": f"Drained {drained} spontaneous indications before proceeding"})

        # baseline d007 read (should still be static per all prior sessions)
        d007_before = None
        try:
            d007_before = await client.read_gatt_char(UUID_D007_SYNCTIME_CANDIDATE)
        except Exception as e:
            log({"msg": f"d007 not readable (expected, write-only): {e}"})

        # setTime: raw 4-byte LE current unix timestamp, WRITE_TYPE_DEFAULT (response=True)
        now_ts = int(time.time())
        ts_bytes = le_bytes(now_ts, 4)
        log({"msg": f"=== setTime: writing current unix time {now_ts} ({datetime.fromtimestamp(now_ts, tz=timezone.utc).isoformat()}) as raw 4 bytes to d007 (charSyncTime candidate) ==="})
        log({"msg": f"TX d007 raw_hex={ts_bytes.hex()}"})
        await client.write_gatt_char(UUID_D007_SYNCTIME_CANDIDATE, ts_bytes, response=True)
        log({"msg": "setTime write ack (Write Response, no error)"})
        result["synctime_write"] = {"unix_time": now_ts, "hex": ts_bytes.hex()}

        await asyncio.sleep(1)

        # now retry getSessions
        log({"msg": "=== Retrying getSessions(start=0) after setTime ==="})
        sess_pkt = build_cmd(OP_GET_SESSIONS, le_bytes(0, 4))
        log({"msg": f"TX 2bb1 hex={sess_pkt.hex()}"})
        await client.write_gatt_char(UUID_CMD_A2S, sess_pkt, response=True)
        try:
            opcode, data = await asyncio.wait_for(cmd_q.get(), timeout=8)
            log({"msg": f"RESPONSE opcode={opcode} hex={data.hex()}"})
            result["getSessions_after_synctime"] = {"opcode": opcode, "hex": data.hex()}
        except asyncio.TimeoutError:
            log({"msg": "TIMEOUT — still no response after setTime"})
            result["getSessions_after_synctime"] = None

        await client.stop_notify(UUID_CMD_S2A)
        await client.stop_notify(UUID_FILE_S2A)

    result_path = CAPTURE_DIR / f"synctime_result_{RUN_TS}.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    log({"msg": f"Saved to {result_path}"})
    print("\n=== SUMMARY ===")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
