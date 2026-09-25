#!/usr/bin/env python3
"""Test: send startRealtime (opcode 10) WHILE the device is already
physically recording, to test the hypothesis that opcode10 attaches to an
existing recording rather than originating a new one from idle. Compares
the returned sessionId (if any) against whatever real sessionId the device
reports via spontaneous START_IND/STOP_IND indications during this window.
"""
import os
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient

ADDRESS = os.environ["C1_ADDRESS"]
UUID_D005_STATE = "0000d005-0000-1000-8000-00805f9b34fb"
UUID_CMD_A2S = "00002bb1-0000-1000-8000-00805f9b34fb"
UUID_CMD_S2A = "00002bb0-0000-1000-8000-00805f9b34fb"
UUID_FILE_S2A = "0000b001-0000-1000-8000-00805f9b34fb"

OP_REALTIME_START = 10
RSP_START_IND = 1
RSP_STOP_IND = 3
RSP_START_CNF = 4

STATE_NAMES = {
    0x0000: "STATE_INIT", 0x0001: "STATE_POWERED_ON", 0x0002: "STATE_BLE_CONNECTED",
    0x1003: "STATE_RECORDING", 0x1111: "STATE_PAUSED", 0x2222: "STATE_STOPPED",
}

BASE = Path(__file__).resolve().parent.parent
CAPTURE_DIR = BASE / "captures" / "e2e_test"
CAPTURE_DIR.mkdir(parents=True, exist_ok=True)
RUN_TS = datetime.now().strftime("%Y%m%dT%H%M%S")
JSONL_PATH = CAPTURE_DIR / f"start_while_recording_{RUN_TS}.jsonl"
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
    result = {"real_session_ids_seen": [], "opcode10_response": None}
    async with BleakClient(ADDRESS) as client:
        log({"msg": f"Connected: {client.is_connected}"})
        cmd_q = asyncio.Queue()

        def cmd_handler(sender, data):
            data = bytes(data)
            opcode = le_int(data[0:2]) if len(data) >= 2 else None
            log({"msg": f"RX indicate opcode={opcode} hex={data.hex()}"})
            if opcode in (RSP_START_IND, RSP_STOP_IND) and len(data) >= 6:
                sid = le_int(data[2:6])
                log({"msg": f"  -> real device sessionId = {hex(sid)}"})
                result["real_session_ids_seen"].append({"opcode": opcode, "sessionId": sid})
            cmd_q.put_nowait((opcode, data))

        await client.start_notify(UUID_CMD_S2A, cmd_handler)
        await client.start_notify(UUID_FILE_S2A, lambda s, d: log({"msg": f"RX file-notify len={len(d)}"}))

        # capabilities handshake (proven necessary)
        pkt = bytearray(20)
        pkt[0:2] = bytes([14, 0])
        pkt[2], pkt[3], pkt[4], pkt[5], pkt[8], pkt[9] = 1, 1, 3, 0, 1, 1
        await client.write_gatt_char(UUID_CMD_A2S, bytes(pkt), response=True)
        log({"msg": "Sent capabilities handshake"})
        await asyncio.sleep(2)
        while not cmd_q.empty():
            cmd_q.get_nowait()

        # check current state immediately
        raw = await client.read_gatt_char(UUID_D005_STATE)
        val = le_int(raw[0:2]) if len(raw) >= 2 else None
        name = STATE_NAMES.get(val, f"UNKNOWN(0x{val:04x})" if val is not None else "?")
        log({"msg": f"Current state: 0x{val:04x} ({name})"})
        result["state_at_send_time"] = {"value": val, "name": name}

        # send opcode10 regardless (recording or not) - this is the test
        log({"msg": "=== Sending startRealtime (opcode 10, recordType=1) while device is (expected) recording ==="})
        sess_pkt = build_cmd(OP_REALTIME_START, le_bytes(1, 1))
        log({"msg": f"TX 2bb1 hex={sess_pkt.hex()}"})
        await client.write_gatt_char(UUID_CMD_A2S, sess_pkt, response=True)

        try:
            opcode, data = await asyncio.wait_for(cmd_q.get(), timeout=8)
            sid = le_int(data[2:6]) if len(data) >= 6 else None
            log({"msg": f"RESPONSE opcode={opcode} hex={data.hex()} decoded_sessionId={hex(sid) if sid else sid}"})
            result["opcode10_response"] = {"opcode": opcode, "hex": data.hex(), "sessionId": sid}
        except asyncio.TimeoutError:
            log({"msg": "TIMEOUT — no response to opcode10"})

        # keep watching for a bit more to catch any follow-up START/STOP_IND
        log({"msg": "Watching for 10 more seconds for any further indicate..."})
        deadline = asyncio.get_event_loop().time() + 10
        while asyncio.get_event_loop().time() < deadline:
            remaining = deadline - asyncio.get_event_loop().time()
            if remaining <= 0:
                break
            try:
                opcode, data = await asyncio.wait_for(cmd_q.get(), timeout=remaining)
            except asyncio.TimeoutError:
                break

        raw2 = await client.read_gatt_char(UUID_D005_STATE)
        val2 = le_int(raw2[0:2]) if len(raw2) >= 2 else None
        name2 = STATE_NAMES.get(val2, f"UNKNOWN(0x{val2:04x})" if val2 is not None else "?")
        log({"msg": f"Final state: 0x{val2:04x} ({name2})"})
        result["state_at_end"] = {"value": val2, "name": name2}

        await client.stop_notify(UUID_CMD_S2A)
        await client.stop_notify(UUID_FILE_S2A)

    result_path = CAPTURE_DIR / f"start_while_recording_result_{RUN_TS}.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    log({"msg": f"Saved to {result_path}"})
    print("\n=== SUMMARY ===")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
