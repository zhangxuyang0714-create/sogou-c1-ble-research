#!/usr/bin/env python3
"""Read-only USB baseline snapshot, taken before any BLE state-changing test."""
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

USB_ROOT = "/media/<user>/SOGOU C1"
OUT_PATH = Path(__file__).resolve().parent.parent / "captures" / "usb_baseline.json"


def sha256_of(path, max_bytes=None):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        remaining = max_bytes
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            if remaining is not None:
                chunk = chunk[:remaining]
                remaining -= len(chunk)
            h.update(chunk)
            if remaining is not None and remaining <= 0:
                break
    return h.hexdigest()


def main():
    snapshot = {"time": datetime.now(timezone.utc).isoformat(), "usb_root": USB_ROOT, "recordings": []}

    record_dir = os.path.join(USB_ROOT, "RECORD")
    for date_dir in sorted(os.listdir(record_dir)):
        full_date_dir = os.path.join(record_dir, date_dir)
        if not os.path.isdir(full_date_dir):
            continue
        for fn in sorted(os.listdir(full_date_dir)):
            if fn.upper().endswith(".WAV"):
                wav_path = os.path.join(full_date_dir, fn)
                avc_path = wav_path[:-4] + ".AVC"
                stat = os.stat(wav_path)
                entry = {
                    "path": f"{date_dir}/{fn}",
                    "wav_size": stat.st_size,
                    "wav_mtime": stat.st_mtime,
                    "wav_sha256": sha256_of(wav_path),
                    "avc_exists": os.path.exists(avc_path),
                    "avc_size": os.path.getsize(avc_path) if os.path.exists(avc_path) else None,
                }
                snapshot["recordings"].append(entry)

    snapshot["recording_count"] = len(snapshot["recordings"])

    for extra in ("GUIDE.TXT", "ALBUM_DB"):
        p = os.path.join(USB_ROOT, extra)
        if os.path.exists(p):
            snapshot[extra] = {"size": os.path.getsize(p), "sha256": sha256_of(p)}

    log_path = os.path.join(USB_ROOT, "LOG", "APP1.LOG")
    if os.path.exists(log_path):
        snapshot["LOG/APP1.LOG"] = {"size": os.path.getsize(log_path), "sha256": sha256_of(log_path)}

    stat_dir = os.path.join(USB_ROOT, "STAT")
    snapshot["STAT_files"] = sorted(os.listdir(stat_dir)) if os.path.isdir(stat_dir) else []

    OUT_PATH.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2))
    print(f"Baseline saved to {OUT_PATH}")
    print(f"Recording count: {snapshot['recording_count']}")
    for r in snapshot["recordings"]:
        print(f"  {r['path']}: {r['wav_size']} bytes, sha256={r['wav_sha256'][:16]}...")


if __name__ == "__main__":
    main()
