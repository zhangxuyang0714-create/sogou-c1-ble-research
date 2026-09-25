#!/usr/bin/env python3
"""Correlate d005's value with USB filesystem activity. Read-only throughout:
only reads d005 over BLE, and only reads (ls/cat) the mounted USB volume."""
import os
import asyncio
import subprocess
from datetime import datetime, timezone
from bleak import BleakClient

ADDRESS = os.environ["C1_ADDRESS"]
D005 = "0000d005-0000-1000-8000-00805f9b34fb"
USB_PATH = os.environ["C1_USB_ROOT"]


async def read_d005(client, label):
    value = await client.read_gatt_char(D005)
    t = datetime.now(timezone.utc).isoformat()
    print(f"  [{label}] {t}  d005={value.hex()}")
    return value


async def main():
    async with BleakClient(ADDRESS) as client:
        print(f"Connected: {client.is_connected}")

        await read_d005(client, "baseline (no USB activity)")
        await asyncio.sleep(1)

        print("  -- doing a read-only 'find' on the USB volume now --")
        subprocess.run(["find", USB_PATH, "-maxdepth", "3"],
                        capture_output=True)
        await read_d005(client, "immediately after USB fs read")

        for i in range(5):
            await asyncio.sleep(3)
            await read_d005(client, f"cooldown +{(i+1)*3}s")


if __name__ == "__main__":
    asyncio.run(main())
