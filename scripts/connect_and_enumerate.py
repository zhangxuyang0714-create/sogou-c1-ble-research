#!/usr/bin/env python3
"""Connect to a BLE device and enumerate GATT services/characteristics.

Read-only: only calls read_gatt_char() on characteristics that advertise the
'read' property. Never writes, never subscribes to notify, never pairs.
"""
import os
import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakClient

CAPTURES_DIR = Path(__file__).resolve().parent.parent / "captures"
CAPTURES_DIR.mkdir(exist_ok=True)


async def main(address):
    ts = datetime.now().strftime("%Y%m%dT%H%M%S")
    out_path = CAPTURES_DIR / f"gatt_enum_{address.replace(':', '')}_{ts}.json"
    result = {
        "time": datetime.now(timezone.utc).isoformat(),
        "address": address,
        "services": [],
    }

    print(f"Connecting to {address} (read-only GATT enumeration, no writes)...")
    async with BleakClient(address) as client:
        print(f"Connected: {client.is_connected}")
        for service in client.services:
            svc_rec = {
                "uuid": service.uuid,
                "handle": service.handle,
                "description": service.description,
                "characteristics": [],
            }
            print(f"\nService {service.uuid}  handle=0x{service.handle:04x}  ({service.description})")
            for char in service.characteristics:
                char_rec = {
                    "uuid": char.uuid,
                    "handle": char.handle,
                    "description": char.description,
                    "properties": char.properties,
                    "value_hex": None,
                    "value_text": None,
                    "read_error": None,
                }
                props = ",".join(char.properties)
                print(f"  Char {char.uuid}  handle=0x{char.handle:04x}  props=[{props}]  ({char.description})")

                if "read" in char.properties:
                    try:
                        value = await client.read_gatt_char(char.uuid)
                        char_rec["value_hex"] = value.hex()
                        try:
                            char_rec["value_text"] = value.decode("utf-8")
                        except UnicodeDecodeError:
                            char_rec["value_text"] = None
                        print(f"    -> read: {value.hex()}  text={char_rec['value_text']!r}")
                    except Exception as e:
                        char_rec["read_error"] = str(e)
                        print(f"    -> read FAILED: {e}")

                svc_rec["characteristics"].append(char_rec)
            result["services"].append(svc_rec)

    with out_path.open("w") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"\nSaved full enumeration to {out_path}")


if __name__ == "__main__":
    addr = sys.argv[1] if len(sys.argv) > 1 else os.environ["C1_ADDRESS"]
    asyncio.run(main(addr))
