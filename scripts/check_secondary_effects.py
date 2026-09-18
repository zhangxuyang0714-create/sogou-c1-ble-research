#!/usr/bin/env python3
"""Read-only follow-up: check d00a/d001/d003/d005/battery for any delayed
effect from the d007 probe write. Pure reads, no writes, no notify."""
import asyncio
from bleak import BleakClient

ADDRESS = "AA:BB:CC:DD:EE:FF"
CHARS = {
    "d001": "0000d001-0000-1000-8000-00805f9b34fb",
    "d003": "0000d003-0000-1000-8000-00805f9b34fb",
    "d005": "0000d005-0000-1000-8000-00805f9b34fb",
    "d00a": "0000d00a-0000-1000-8000-00805f9b34fb",
    "battery": "00002a19-0000-1000-8000-00805f9b34fb",
}


async def main():
    async with BleakClient(ADDRESS) as client:
        print(f"Connected: {client.is_connected}")
        for name, uuid in CHARS.items():
            value = await client.read_gatt_char(uuid)
            print(f"  {name}: {value.hex()}")


if __name__ == "__main__":
    asyncio.run(main())
