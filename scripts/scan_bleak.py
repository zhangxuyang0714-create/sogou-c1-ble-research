#!/usr/bin/env python3
"""Passive BLE advertisement scanner/logger. Read-only: never connects, never writes.

Logs every advertisement seen to a JSONL file (captures/) and prints a live
line for each new or RSSI/data-changed device so a human can watch for the
C1/C18D showing up.
"""
import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from bleak import BleakScanner

CAPTURES_DIR = Path(__file__).resolve().parent.parent / "captures"
CAPTURES_DIR.mkdir(exist_ok=True)

ts = datetime.now().strftime("%Y%m%dT%H%M%S")
out_path = CAPTURES_DIR / f"bleak_scan_{ts}.jsonl"

last_seen = {}  # address -> fingerprint string, to only print on change


def fingerprint(adv):
    return (
        adv.local_name,
        adv.rssi,
        tuple(sorted(adv.service_uuids or [])),
        tuple(sorted((adv.manufacturer_data or {}).keys())),
        tuple(sorted((adv.service_data or {}).keys())),
    )


def record_to_dict(device, adv):
    return {
        "time": datetime.now(timezone.utc).isoformat(),
        "address": device.address,
        "name": adv.local_name or device.name,
        "rssi": adv.rssi,
        "tx_power": adv.tx_power,
        "service_uuids": adv.service_uuids or [],
        "manufacturer_data": {
            str(k): v.hex() for k, v in (adv.manufacturer_data or {}).items()
        },
        "service_data": {
            str(k): v.hex() for k, v in (adv.service_data or {}).items()
        },
    }


def detection_callback(device, adv):
    fp = fingerprint(adv)
    if last_seen.get(device.address) == fp:
        return  # unchanged since last time, skip noisy re-print
    last_seen[device.address] = fp

    rec = record_to_dict(device, adv)
    with out_path.open("a") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    name = rec["name"] or "(no name)"
    print(
        f"[{rec['time']}] {device.address}  name={name!r}  rssi={rec['rssi']}  "
        f"svc_uuids={rec['service_uuids']}  "
        f"mfg_data_keys={list(rec['manufacturer_data'].keys())}  "
        f"svc_data_keys={list(rec['service_data'].keys())}",
        flush=True,
    )


async def main(duration):
    print(f"Writing raw records to {out_path}")
    print(f"Scanning for {duration} seconds (Ctrl+C to stop early)...")
    scanner = BleakScanner(detection_callback=detection_callback)
    await scanner.start()
    try:
        await asyncio.sleep(duration)
    finally:
        await scanner.stop()
    print("Scan finished.")


if __name__ == "__main__":
    duration = int(sys.argv[1]) if len(sys.argv) > 1 else 1800
    asyncio.run(main(duration))
