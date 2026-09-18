#!/usr/bin/env python3
"""Single controlled write probe against dd68/d007.

This is a ONE-SHOT, user-authorized experiment. It:
  1. Connects.
  2. Subscribes to cc68/b001 notify (to catch any response).
  3. Reads baseline state (dd68/d005) and battery (180f/2a19).
  4. Writes exactly one byte (0x00) to dd68/d007 via ATT Write Request
     (response=True), so we get an explicit ATT-level ack/error.
  5. Waits a short window for any notification.
  6. Re-reads state and battery, compares to baseline.
  7. Reports everything raw. Does NOT retry or try other payloads.
"""
import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient
from bleak.exc import BleakError

CAPTURES_DIR = Path(__file__).resolve().parent.parent / "captures"
CAPTURES_DIR.mkdir(exist_ok=True)

ADDRESS = "AA:BB:CC:DD:EE:FF"
D007 = "0000d007-0000-1000-8000-00805f9b34fb"
D005 = "0000d005-0000-1000-8000-00805f9b34fb"
D001 = "0000d001-0000-1000-8000-00805f9b34fb"
D00A = "0000d00a-0000-1000-8000-00805f9b34fb"
BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"
B001 = "0000b001-0000-1000-8000-00805f9b34fb"

PAYLOAD = bytes([int(sys.argv[1], 16)]) if len(sys.argv) > 1 else bytes([0x00])


async def main():
    ts = datetime.now().strftime("%Y%m%dT%H%M%S")
    out_path = CAPTURES_DIR / f"probe_d007_{ts}.json"
    result = {
        "time": datetime.now(timezone.utc).isoformat(),
        "address": ADDRESS,
        "payload_hex": PAYLOAD.hex(),
        "notifications": [],
        "baseline": {},
        "after": {},
        "write_result": None,
        "write_error": None,
    }

    def notify_handler(sender, data: bytearray):
        rec = {
            "time": datetime.now(timezone.utc).isoformat(),
            "characteristic": str(sender),
            "hex": data.hex(),
        }
        result["notifications"].append(rec)
        print(f"  [NOTIFY] {rec['time']} {rec['characteristic']} hex={rec['hex']}")

    print(f"Connecting to {ADDRESS}...")
    async with BleakClient(ADDRESS) as client:
        print(f"Connected: {client.is_connected}")

        print("Subscribing to b001 notify...")
        await client.start_notify(B001, notify_handler)

        print("Reading baseline state (d005, d001, d00a) and battery...")
        state_before = await client.read_gatt_char(D005)
        d001_before = await client.read_gatt_char(D001)
        d00a_before = await client.read_gatt_char(D00A)
        batt_before = await client.read_gatt_char(BATTERY)
        result["baseline"] = {
            "d005_hex": state_before.hex(),
            "d001_hex": d001_before.hex(),
            "d00a_hex": d00a_before.hex(),
            "battery_pct": int.from_bytes(batt_before, "little"),
        }
        print(f"  baseline: d005={state_before.hex()} d001={d001_before.hex()} d00a={d00a_before.hex()} battery={result['baseline']['battery_pct']}%")

        print(f"Writing {PAYLOAD.hex()} to d007 (Write Request, response=True)...")
        try:
            await client.write_gatt_char(D007, PAYLOAD, response=True)
            result["write_result"] = "ack (Write Response received, no error)"
            print(f"  -> {result['write_result']}")
        except BleakError as e:
            result["write_error"] = str(e)
            print(f"  -> ATT ERROR: {e}")

        print("Waiting 5s for any notification...")
        await asyncio.sleep(5)

        print("Re-reading state (d005, d001, d00a) and battery...")
        state_after = await client.read_gatt_char(D005)
        d001_after = await client.read_gatt_char(D001)
        d00a_after = await client.read_gatt_char(D00A)
        batt_after = await client.read_gatt_char(BATTERY)
        result["after"] = {
            "d005_hex": state_after.hex(),
            "d001_hex": d001_after.hex(),
            "d00a_hex": d00a_after.hex(),
            "battery_pct": int.from_bytes(batt_after, "little"),
        }
        print(f"  after: d005={state_after.hex()} d001={d001_after.hex()} d00a={d00a_after.hex()} battery={result['after']['battery_pct']}%")

        await client.stop_notify(B001)

        changed = (state_before != state_after or d001_before != d001_after
                   or d00a_before != d00a_after)
        print(f"\nAny characteristic changed: {changed}")
        result["any_changed"] = changed
        if changed:
            print("  *** VALUE CHANGED — flagging for immediate review, not retrying ***")

    with out_path.open("w") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"\nFull result saved to {out_path}")
    print(f"Notifications received: {len(result['notifications'])}")


if __name__ == "__main__":
    asyncio.run(main())
