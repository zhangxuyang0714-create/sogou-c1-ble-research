#!/usr/bin/env python3
"""Passive indicate subscription on 1910/2bb0. Same low-risk category as the
earlier b001 notify subscription: enabling a standard CCCD to listen, no
writes to any vendor command characteristic."""
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path
from bleak import BleakClient

CAPTURES_DIR = Path(__file__).resolve().parent.parent / "captures"
CAPTURES_DIR.mkdir(exist_ok=True)

ADDRESS = "AA:BB:CC:DD:EE:FF"
CHAR_2BB0 = "00002bb0-0000-1000-8000-00805f9b34fb"
D005 = "0000d005-0000-1000-8000-00805f9b34fb"


async def main(duration=30):
    ts = datetime.now().strftime("%Y%m%dT%H%M%S")
    out_path = CAPTURES_DIR / f"indicate_2bb0_{ts}.jsonl"
    count = 0

    def handler(sender, data: bytearray):
        nonlocal count
        count += 1
        rec = {"time": datetime.now(timezone.utc).isoformat(),
               "characteristic": str(sender), "hex": data.hex()}
        with out_path.open("a") as f:
            f.write(json.dumps(rec) + "\n")
        print(f"  [INDICATE #{count}] {rec['time']} hex={rec['hex']}")

    async with BleakClient(ADDRESS) as client:
        print(f"Connected: {client.is_connected}")
        print(f"Subscribing to 2bb0 indicate for {duration}s (passive)...")
        await client.start_notify(CHAR_2BB0, handler)
        # Do a normal read during the window, like a real session would.
        await asyncio.sleep(3)
        val = await client.read_gatt_char(D005)
        print(f"  (d005 during window = {val.hex()})")
        await asyncio.sleep(duration - 3)
        await client.stop_notify(CHAR_2BB0)

    print(f"Done. {count} indication(s) received. Log: {out_path}")


if __name__ == "__main__":
    asyncio.run(main(30))
