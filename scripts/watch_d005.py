#!/usr/bin/env python3
"""Connect once, then read d005 repeatedly within the SAME connection to see
if its value is connection-transient or actually stable/changing over time."""
import os
import asyncio
from datetime import datetime, timezone
from bleak import BleakClient

ADDRESS = os.environ["C1_ADDRESS"]
D005 = "0000d005-0000-1000-8000-00805f9b34fb"


async def main():
    async with BleakClient(ADDRESS) as client:
        print(f"Connected: {client.is_connected}")
        for i in range(10):
            value = await client.read_gatt_char(D005)
            t = datetime.now(timezone.utc).isoformat()
            print(f"  [{i}] {t}  d005={value.hex()}")
            await asyncio.sleep(1.5)


if __name__ == "__main__":
    asyncio.run(main())
