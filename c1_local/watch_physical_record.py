#!/usr/bin/env python3
"""Passive monitor while the user physically triggers a recording via the
device's own button. Connects, does the capabilities handshake (needed for
the device to talk to us at all, proven necessary), subscribes to both
indicate (2bb0, command responses / spontaneous IND messages) and notify
(b001, file data), then just polls d005 state repeatedly and logs any
indicate/notify traffic that shows up on its own. No commands sent besides
the read-only state polling and the one proven-necessary handshake.
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

STATE_NAMES = {
    0x0000: "STATE_INIT", 0x0001: "STATE_POWERED_ON", 0x0002: "STATE_BLE_CONNECTED",
    0x1003: "STATE_RECORDING", 0x1111: "STATE_PAUSED", 0x2222: "STATE_STOPPED",
}
# C1ActionParser.C1ActionStick (stick->app opcodes) for readable logging
STICK_OPCODE_NAMES = {
    1: "STICK_RECORD_START_IND", 2: "STICK_RECORD_PAUSE_IND", 3: "STICK_RECORD_STOP_IND",
    4: "STICK_RECORD_START_CNF", 5: "STICK_RECORD_A_START_IND", 6: "STICK_RECORD_A_STOP_IND",
    7: "STICK_RECORD_B_START_IND", 8: "STICK_RECORD_B_STOP_IND",
    9: "STICK_RECORD_GET_SESSIONS_CONFIRM", 10: "STICK_RECORD_GET_FILES_CONFIRM",
    11: "STICK_RECORD_FILE_HEADER", 12: "STICK_RECORD_FILE_TAIL",
    14: "STICK_GET_APP_CAPACITES_REQ", 25: "STICK_GET_FREE_SIZE_CNF", 26: "STICK_GET_STAT_CNF",
}

BASE = Path(__file__).resolve().parent.parent
CAPTURE_DIR = BASE / "captures" / "e2e_test"
CAPTURE_DIR.mkdir(parents=True, exist_ok=True)
RUN_TS = datetime.now().strftime("%Y%m%dT%H%M%S")
JSONL_PATH = CAPTURE_DIR / f"watch_physical_{RUN_TS}.jsonl"
_f = open(JSONL_PATH, "a")


def le_int(data: bytes) -> int:
    v = 0
    for i, b in enumerate(data):
        v += (b & 0xFF) << (8 * i)
    return v


def log(event: dict):
    event = {"time": datetime.now(timezone.utc).isoformat(), **event}
    _f.write(json.dumps(event, ensure_ascii=False) + "\n")
    _f.flush()
    print(f"[{event['time']}] {event.get('msg','')}")


def indicate_handler(sender, data: bytearray):
    data = bytes(data)
    opcode = le_int(data[0:2]) if len(data) >= 2 else None
    name = STICK_OPCODE_NAMES.get(opcode, f"UNKNOWN({opcode})")
    log({"src": "2bb0-indicate", "opcode": opcode, "opcode_name": name, "hex": data.hex(),
         "msg": f"*** INDICATE *** opcode={opcode} ({name}) hex={data.hex()}"})


def file_notify_handler(sender, data: bytearray):
    data = bytes(data)
    log({"src": "b001-notify", "hex": data.hex()[:100],
         "msg": f"*** FILE NOTIFY *** len={len(data)} hex={data.hex()[:60]}..."})


async def main(duration=90):
    log({"msg": f"Connecting to {ADDRESS}..."})
    async with BleakClient(ADDRESS) as client:
        log({"msg": f"Connected: {client.is_connected}"})
        await client.start_notify(UUID_CMD_S2A, indicate_handler)
        await client.start_notify(UUID_FILE_S2A, file_notify_handler)
        log({"msg": "Subscribed 2bb0 indicate + b001 notify"})

        # capabilities handshake (proven necessary for device to respond to anything)
        pkt = bytearray(20)
        pkt[0:2] = bytes([14, 0])
        pkt[2], pkt[3], pkt[4], pkt[5], pkt[8], pkt[9] = 1, 1, 3, 0, 1, 1
        await client.write_gatt_char(UUID_CMD_A2S, bytes(pkt), response=True)
        log({"msg": "Sent capabilities handshake (ackInfo null,null)"})
        await asyncio.sleep(1)

        last_state = None
        end_time = asyncio.get_event_loop().time() + duration
        log({"msg": f"=== Watching for {duration}s. Please physically start a recording now (press the button). ==="})
        while asyncio.get_event_loop().time() < end_time:
            raw = await client.read_gatt_char(UUID_D005_STATE)
            val = le_int(raw[0:2]) if len(raw) >= 2 else None
            name = STATE_NAMES.get(val, f"UNKNOWN(0x{val:04x})" if val is not None else "?")
            if val != last_state:
                log({"src": "d005-poll", "raw_hex": raw.hex(), "decoded": val,
                     "msg": f"STATE CHANGED: 0x{val:04x} ({name})"})
                last_state = val
            await asyncio.sleep(1.5)

        await client.stop_notify(UUID_CMD_S2A)
        await client.stop_notify(UUID_FILE_S2A)
    log({"msg": f"Done. Log saved to {JSONL_PATH}"})


if __name__ == "__main__":
    asyncio.run(main(90))
