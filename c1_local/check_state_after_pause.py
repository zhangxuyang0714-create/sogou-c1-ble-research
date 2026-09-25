#!/usr/bin/env python3
"""Quick check: read current state and passively watch for a few seconds
after the user paused the physical recording."""
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

STATE_NAMES = {
    0x0000: "STATE_INIT", 0x0001: "STATE_POWERED_ON", 0x0002: "STATE_BLE_CONNECTED",
    0x1003: "STATE_RECORDING", 0x1111: "STATE_PAUSED", 0x2222: "STATE_STOPPED",
}


def le_int(data):
    v = 0
    for i, b in enumerate(data):
        v += (b & 0xFF) << (8 * i)
    return v


def log(msg):
    print(f"[{datetime.now(timezone.utc).isoformat()}] {msg}")


async def main():
    async with BleakClient(ADDRESS) as client:
        log(f"Connected: {client.is_connected}")

        def cmd_handler(sender, data):
            data = bytes(data)
            opcode = le_int(data[0:2]) if len(data) >= 2 else None
            log(f"*** INDICATE *** opcode={opcode} hex={data.hex()}")
            if opcode in (1, 2, 3) and len(data) >= 6:
                sid = le_int(data[2:6])
                log(f"    -> sessionId = {hex(sid)}")

        await client.start_notify(UUID_CMD_S2A, cmd_handler)
        await client.start_notify(UUID_FILE_S2A, lambda s, d: log(f"file-notify len={len(d)}"))

        pkt = bytearray(20)
        pkt[0:2] = bytes([14, 0])
        pkt[2], pkt[3], pkt[4], pkt[5], pkt[8], pkt[9] = 1, 1, 3, 0, 1, 1
        await client.write_gatt_char(UUID_CMD_A2S, bytes(pkt), response=True)
        log("Sent capabilities handshake")
        await asyncio.sleep(2)

        raw = await client.read_gatt_char(UUID_D005_STATE)
        val = le_int(raw[0:2]) if len(raw) >= 2 else None
        name = STATE_NAMES.get(val, f"UNKNOWN(0x{val:04x})" if val is not None else "?")
        log(f"Current state: raw={raw.hex()} decoded=0x{val:04x} ({name})")

        log("Watching passively for 8s for any indicate...")
        await asyncio.sleep(8)

        await client.stop_notify(UUID_CMD_S2A)
        await client.stop_notify(UUID_FILE_S2A)


if __name__ == "__main__":
    asyncio.run(main())
