#!/usr/bin/env python3
"""Subscribe to a notify characteristic and passively log whatever the device
sends on its own. Never writes to any other characteristic."""
import os
import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient

CAPTURES_DIR = Path(__file__).resolve().parent.parent / "captures"
CAPTURES_DIR.mkdir(exist_ok=True)


async def main(address, char_uuid, duration):
    ts = datetime.now().strftime("%Y%m%dT%H%M%S")
    out_path = CAPTURES_DIR / f"notify_{char_uuid.split('-')[0]}_{ts}.jsonl"
    count = 0

    def handler(sender, data: bytearray):
        nonlocal count
        count += 1
        rec = {
            "time": datetime.now(timezone.utc).isoformat(),
            "characteristic": str(sender),
            "hex": data.hex(),
            "len": len(data),
        }
        with out_path.open("a") as f:
            f.write(json.dumps(rec) + "\n")
        print(f"[{rec['time']}] notify #{count} len={rec['len']} hex={rec['hex']}")

    async with BleakClient(address) as client:
        print(f"Connected: {client.is_connected}")
        print(f"Subscribing to notify on {char_uuid} for {duration}s (passive, no writes)...")
        await client.start_notify(char_uuid, handler)
        await asyncio.sleep(duration)
        await client.stop_notify(char_uuid)

    print(f"Done. {count} notification(s) received. Log: {out_path}")
    return count


if __name__ == "__main__":
    addr = sys.argv[1] if len(sys.argv) > 1 else os.environ["C1_ADDRESS"]
    char = sys.argv[2] if len(sys.argv) > 2 else "0000b001-0000-1000-8000-00805f9b34fb"
    dur = int(sys.argv[3]) if len(sys.argv) > 3 else 45
    asyncio.run(main(addr, char, dur))
