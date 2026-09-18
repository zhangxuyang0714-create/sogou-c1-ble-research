# C1 / C18D BLE Protocol Research

Sogou Smart Recorder C1 / C18D — Phase 1: Linux + Bluetooth LE + public GitHub reference + real hardware.

Goal: determine whether this Linux machine can establish BLE communication with a real C1 device, and
what information can be read from it (GATT services/characteristics, device status, storage, recording
list, battery, file download) — without any write/destructive operations.

## Layout

- `docs/` — experiment log and protocol notes
- `captures/` — BLE scan/communication captures (btmon, bleak dumps)
- `scripts/` — minimal test scripts we write ourselves
- `logs/` — raw experiment logs
- `reference/` — read-only public reference material (not modified)
- `.venv/` — isolated Python environment (Bleak), not committed

## Status

Phase 1 environment setup complete. No hardware testing performed yet.
