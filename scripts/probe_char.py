#!/usr/bin/env python3
"""Generalized single controlled write probe — same spec as the earlier
d007/b002 probes, extended to watch BOTH b001 notify and 2bb0 indicate
simultaneously (comprehensive monitoring), and to target any write
characteristic by UUID.

ONE-SHOT per invocation. No retries, no payload sweeping within a run.
"""
import os
import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient
from bleak.exc import BleakError

CAPTURES_DIR = Path(__file__).resolve().parent.parent / "captures"
CAPTURES_DIR.mkdir(exist_ok=True)

ADDRESS = os.environ["C1_ADDRESS"]
D005 = "0000d005-0000-1000-8000-00805f9b34fb"
D001 = "0000d001-0000-1000-8000-00805f9b34fb"
D00A = "0000d00a-0000-1000-8000-00805f9b34fb"
BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"
B001 = "0000b001-0000-1000-8000-00805f9b34fb"
CHAR_2BB0 = "00002bb0-0000-1000-8000-00805f9b34fb"


async def main(target_uuid, payload_hex, label):
    ts = datetime.now().strftime("%Y%m%dT%H%M%S")
    out_path = CAPTURES_DIR / f"probe_{label}_{ts}.json"
    payload = bytes.fromhex(payload_hex)
    result = {
        "time": datetime.now(timezone.utc).isoformat(),
        "address": ADDRESS,
        "target": target_uuid,
        "payload_hex": payload.hex(),
        "notifications": [],
        "baseline": {},
        "after": {},
        "write_result": None,
        "write_error": None,
    }

    def notify_handler(sender, data: bytearray):
        rec = {"time": datetime.now(timezone.utc).isoformat(),
               "characteristic": str(sender), "hex": data.hex()}
        result["notifications"].append(rec)
        print(f"  [EVENT] {rec['time']} {rec['characteristic']} hex={rec['hex']}")

    print(f"[{label}] Connecting to {ADDRESS}...")
    async with BleakClient(ADDRESS) as client:
        print(f"  Connected: {client.is_connected}")
        await client.start_notify(B001, notify_handler)
        await client.start_notify(CHAR_2BB0, notify_handler)
        print("  Subscribed to b001 notify + 2bb0 indicate (both channels watched)")

        state_before = await client.read_gatt_char(D005)
        d001_before = await client.read_gatt_char(D001)
        d00a_before = await client.read_gatt_char(D00A)
        batt_before = await client.read_gatt_char(BATTERY)
        result["baseline"] = {
            "d005_hex": state_before.hex(), "d001_hex": d001_before.hex(),
            "d00a_hex": d00a_before.hex(),
            "battery_pct": int.from_bytes(batt_before, "little"),
        }
        print(f"  baseline: d005={state_before.hex()} d001={d001_before.hex()} d00a={d00a_before.hex()} battery={result['baseline']['battery_pct']}%")

        print(f"  Writing {payload.hex()} to {target_uuid} (Write Request, response=True)...")
        try:
            await client.write_gatt_char(target_uuid, payload, response=True)
            result["write_result"] = "ack (Write Response received, no error)"
            print(f"  -> {result['write_result']}")
        except BleakError as e:
            result["write_error"] = str(e)
            print(f"  -> ATT ERROR: {e}")

        await asyncio.sleep(5)

        state_after = await client.read_gatt_char(D005)
        d001_after = await client.read_gatt_char(D001)
        d00a_after = await client.read_gatt_char(D00A)
        batt_after = await client.read_gatt_char(BATTERY)
        result["after"] = {
            "d005_hex": state_after.hex(), "d001_hex": d001_after.hex(),
            "d00a_hex": d00a_after.hex(),
            "battery_pct": int.from_bytes(batt_after, "little"),
        }
        print(f"  after: d005={state_after.hex()} d001={d001_after.hex()} d00a={d00a_after.hex()} battery={result['after']['battery_pct']}%")

        await client.stop_notify(B001)
        await client.stop_notify(CHAR_2BB0)

        changed = (state_before != state_after or d001_before != d001_after
                   or d00a_before != d00a_after)
        result["any_changed"] = changed
        print(f"  Any characteristic changed: {changed}")
        if changed or result["notifications"]:
            print("  *** SIGNAL DETECTED ***")

    with out_path.open("w") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"  Saved: {out_path}  (notifications: {len(result['notifications'])})")
    return result


if __name__ == "__main__":
    target = sys.argv[1]
    payload = sys.argv[2]
    label = sys.argv[3]
    asyncio.run(main(target, payload, label))
