# USB Investigation Findings

## Identity (confirmed)

- `lsusb`: `Bus 001 Device 005: ID 0e8d:0002` (static USB-ID database
  mislabels this "MediaTek Inc. phone [Doro Primo 413]" — that's just
  another product sharing the same generic MediaTek VID:PID, not this
  device)
- **`dmesg` proves the real identity directly**: `Product: Sogou C1`,
  `Manufacturer: MediaTek Inc`, `bcdDevice=1.00`
- `udevadm info -a` descriptor attributes: `idVendor=0e8d`,
  `idProduct=0002`, `bDeviceClass=00` (class defined per-interface),
  `bcdDevice=0100`. No USB-level serial number string descriptor exposed.
- The device's own serial number (`5200000000000000`) is available two
  other ways instead: `GUIDE.TXT` on the mass-storage volume, and BLE GATT
  characteristic `dd68/d003` — **all three independent sources agree
  exactly**, strong cross-confirmation this is the same physical unit as
  the BLE device studied so far.

## USB class: Mass Storage (BOT/SCSI), not charging-only

- `lsusb -t`: `If 0, Class=Mass Storage, Driver=usb-storage`
- `dmesg`: `USB Mass Storage device detected` → SCSI device
  `MEDIATEK FLASH DISK` → block device `sda`, 30,310,400 × 512-byte
  blocks = 14.5 GiB, one partition `sda1`.
- **This is a real, full data interface — not charging-only.** No serial
  port (no `/dev/ttyACM*`/`/dev/ttyUSB*` appeared), no HID, no MTP/PTP, no
  CDC-ACM, no other USB interface at all — `lsusb -t` shows exactly one
  interface, Mass Storage. So there is no vendor-specific USB interface
  either; this is the plain, standard USB Mass Storage class.
- Already auto-mounted by the desktop (udisks2) at `/media/<user>/SOGOU C1`,
  `vfat`, read-write, owned by the current user — no root needed for any
  of this.

## Filesystem contents (read-only browsing, nothing modified)

```text
/media/<user>/SOGOU C1/
├── GUIDE.TXT           (GBK-encoded text, see below)
├── ALBUM_DB             (2 bytes: 00 00)
├── LOG/APP1.LOG         (plaintext firmware event log)
├── STAT/*.DAT            (24 files, binary, one per usage day — not decoded)
├── RECORD/<YYYYMMDD>/<HH_MM_SS>.WAV + .AVC   (11 recordings, 162MB total)
├── SPEECH/               (empty)
├── NEWS/                 (empty)
└── .Trashes/501/         (3 recently-deleted recordings, still recoverable —
                            standard FAT "not securely erased" behavior, not
                            anything we did)
```

**`GUIDE.TXT`** (GBK-encoded; garbled if read as UTF-8/latin1 — decode with
`iconv -f GBK -t UTF-8`), translated:

> SN: 5200000000000000, for after-sales service use.
> Files with the `.WAV` suffix are preview recordings — can be exported to
> a computer for playback.
> Files with the `.AVC` suffix are the raw recordings — can be uploaded to
> the web version for transcription.

This **directly confirms** the two-file-per-recording design guessed at
from the SDK docs, and explains their roles precisely: `.WAV` = ready to
play, `.AVC` = raw, meant for server-side transcription upload (not needed
for local playback).

**`.WAV` files**: confirmed via `file`: `RIFF (little-endian) data, WAVE
audio, Microsoft PCM, 16 bit, mono 16000 Hz`. Fully standard, playable
anywhere immediately, no processing needed.

**`.AVC` files**: `file` reports generic `data` (no recognized header).
Roughly 4x smaller than the paired `.WAV` (e.g. one pair: WAV=2,065,964
bytes vs AVC=516,480 bytes for the same recording) — consistent with a
compressed source, not further identified. Not needed for basic
playback/download since `.WAV` already covers that use case.

**`LOG/APP1.LOG`**: plaintext firmware event log, format
`T[ticks] M[module] L[level] F[function] L[line]: message`. Contains:
- **Firmware version: `V0127`** (previously unknown — BLE has no
  Device-Information-style version characteristic; this is the first
  confirmed firmware version string in the whole investigation)
- Historical battery percentages at boot/init events (`bat=100`, `bat=97`,
  `bat=74`, `bat=71`, `bat=80`, etc.) spanning 2019-10-11 through
  2025-05-20 in this log
- Boot ("TTR_INIT") and manual-shutdown ("USER MANAUL SHUT DOWN" — their
  typo, not ours) event timestamps

**`STAT/*.DAT`**: 24 files (one per usage day from 2019 through 2026),
binary format, first bytes e.g. `6b 25 35 01 01 00 01 00 00 00 00 00 01 00
00 00 00 00 00 00` — not decoded, likely daily usage counters. Low
priority; the recording list is already fully available from the
`RECORD/` directory structure directly, so decoding this isn't necessary
to answer "what recordings exist."

**`ALBUM_DB`**: 2 bytes, `00 00`. Meaning unclear (possibly an
"album"/playlist selection index for the on-device UI, unrelated to actual
recording count since `RECORD/` clearly holds 11 files).

## Recording inventory (from directory listing — this answers "get recording list" directly, no protocol needed)

11 active recordings, ~162MB total, spanning 2025-03-07 through
2026-02-03:

```
20250307/19_00_32, 20250307/19_10_21
20250312/23_48_00, 20250312/23_52_52, 20250312/23_59_15
20250316/19_08_20
20250520/06_11_50
20250531/10_33_13
20250924/00_47_45
20250926/15_06_25
20260203/19_12_34
```

Plus 3 recently-deleted-but-still-present recordings in `.Trashes/501/`
(`17_17_53`, `17_18_03`, `17_18_59` — no date folder, so their original
date is unknown from the path alone).

**This means: the recording list and recording download, the two hardest
open questions from the BLE side of this investigation, are already fully
answered — via USB, not BLE.** No `getRecSessionWithUid`/`startSyncRecord`
BLE reverse-engineering is needed to list or download recordings.

## USB ↔ BLE correlation (directly relevant to the SDK's `isUDisk` field)

With USB connected and mounted, BLE `dd68/d005` (the state candidate,
previously constant at `01000000` across the *entire* rest of this
investigation) was observed to **change to `0a100000`** — the first value
change ever seen on this characteristic.

Follow-up controlled test (`scripts/correlate_usb_d005.py`): read `d005`,
then perform a read-only `find` on the mounted USB volume, then poll
`d005` every 3s for 15s.

- `d005` was `0a100000` ("hot") immediately before and immediately after
  the USB filesystem read.
- It decayed back to `01000000` ("idle") within roughly 3-13 seconds of no
  further USB filesystem activity, despite continued BLE polling every 3s
  during that window (so it's not simply "any BLE read keeps it hot").
- Repeated in a separate single-connection test
  (`scripts/watch_d005.py`): stayed `0a100000` for ~13.5s then reverted to
  `01000000` — consistent decay window.

**Confirmed**: `d005` is not static — it does encode live device state,
and that state visibly reacts to USB mass-storage read activity.
**Hypothesis, not confirmed**: which specific bit(s) correspond to the
SDK's documented `isUDisk` field vs. some more generic "flash/USB busy"
indicator — byte-level: idle=`01 00 00 00`, active=`0a 10 00 00` (byte0
0x01→0x0a, byte1 0x00→0x10, bytes 2-3 unchanged). Not enough data points
yet to assign exact bit meanings (would need e.g. a read the instant USB
is first plugged in vs. unplugged, and during actual recording, neither of
which was tested — recording is explicitly out of scope, and testing
plug/unplug precisely would need the user to physically act).

## What this means for the project's stated goal (avoid Sogou cloud)

**All three of the originally hardest questions are now answered — via
USB, not BLE:**
1. Can device state be read locally? Partially via BLE (`d005`), and now
   we also have firmware version and historical battery via the USB log.
2. Can the recording list be read locally? **Yes — directly, via the
   `RECORD/` directory structure over USB. No BLE protocol needed.**
3. Can a recording be downloaded locally, bypassing Sogou's cloud
   entirely? **Yes — it already is a local file. `cp` from the mounted
   volume is enough. No BLE protocol, no cloud, nothing proprietary.**

This substantially changes the shape of any future Android app: file
access to recordings does not require reverse-engineering the BLE
`startSyncRecord` protocol at all if the app can use USB
(Android's Storage Access Framework / USB Mass Storage host support) —
though note Android's own file-picker/MTP conventions differ from a
desktop Linux `vfat` automount, and Android would need either USB OTG
mass-storage host support or the SDK's official method; this is a design
question for later, not resolved here.
