# Experiment Log

Record every hands-on test against the real C1 here. Do not just write "connected successfully" —
capture actual data (addresses, UUIDs, payloads, RSSI, error codes).

## Log entry template

```
### YYYY-MM-DD HH:MM

- Linux kernel:
- BlueZ version:
- Bluetooth adapter (address / chipset):
- C1 firmware/version (if readable):
- C1 MAC address:
- RSSI:
- Test command(s):
- Result / raw output:
- Notes:
```

---

## 2026-09-18 — Environment baseline (no device present)

- Linux kernel: 7.0.0-31-generic
- BlueZ version: 5.72 (bluetoothctl)
- Bluetooth service: active (systemd)
- Bluetooth adapter: `11:22:33:44:55:66` (public), name `<hostname>`, powered: yes,
  roles: central + peripheral, advertising: 5 instances supported
- rfkill: hci0 not soft/hard blocked
- Python: 3.12.3
- Bleak: 3.0.2 (installed in `.venv`, not system Python)
- C1 device: not yet present/tested
- Result: environment confirmed ready for BLE scanning; no device-side data yet.

---

## 2026-09-18 15:46–15:48 — First real C1 discovery + read-only GATT enumeration

- Linux kernel: 7.0.0-31-generic
- BlueZ version: 5.72
- Bluetooth adapter: `11:22:33:44:55:66` (hci0), powered, not rfkill-blocked
- C1 firmware/version: not exposed via any readable characteristic (no Device
  Information Service 0x180A present on the device)
- C1 MAC address: `AA:BB:CC:DD:EE:FF` (random address type)
- C1 advertised name: `搜狗AI录音笔`
- RSSI: -41 to -50 dBm across repeated adverts (device close/strong signal)
- Advertising manufacturer data (company id `0x0059`, i.e. key `89` as bleak
  reports it decimal): `02080204567f000010353230303030303030303030303030300100`
  — contains an embedded ASCII serial number `5200000000000000` and the byte
  sequence `567f0000` that also shows up verbatim as characteristic
  `0000d001`'s value.
- Test commands:
  - `scripts/scan_bleak.py` (passive scan via BlueZ D-Bus, no root needed)
  - `scripts/connect_and_enumerate.py AA:BB:CC:DD:EE:FF` (BleakClient connect
    + enumerate services/characteristics + read only `read`-flagged chars)
  - `bluetoothctl info AA:BB:CC:DD:EE:FF` (post-connect pairing/bond check)
- Result:
  - Device reliably discoverable via passive scan.
  - GATT connect succeeded with a plain (unauthenticated, unencrypted) link —
    no pairing/bonding was triggered. `bluetoothctl info` afterward showed
    `Paired: no`, `Bonded: no`, `Trusted: no`, `Connected: no` (clean
    disconnect after the script exited).
  - Full GATT tree (see `captures/gatt_enum_AABBCCDDEEFF_20260918T154806.json`):
    - `00001910-...` (vendor-specific, BlueZ mislabels as SIG "Constant Tone
      Extension" — coincidental 16-bit UUID reuse, not the real function):
      - `00002bb1` — props `write` (unread, not touched)
      - `00002bb0` — props `indicate` (not subscribed)
    - `0000dd68-...` (vendor-specific, main info/state service):
      - `0000d005` — read `01000000` → looks like a state field (`getState`
        candidate; `1` matches the SDK's "开机状态" enum value)
      - `0000d003` — read `35323030303030303030303030303030` → ASCII
        `5200000000000000`, the device serial number (matches the SN
        embedded in the advertisement's manufacturer data)
      - `0000d00a` — read `02` → unknown single byte
      - `0000d007` — props `write-without-response,write` (command channel
        candidate; NOT written to)
      - `0000d001` — read `567f0000` → matches bytes in the advertisement
        payload; purpose unclear
    - `0000cc68-...` (vendor-specific, likely data/session channel):
      - `0000b001` — props `notify` only (not subscribed yet)
      - `0000b002` — props `write-without-response,write` (NOT written to)
    - `0000180f-...` **Battery Service** (standard SIG service):
      - `00002a19` **Battery Level** — read `0x55` = **85%**, props
        `read,notify`
    - `00001801-...` Generic Attribute Profile — no characteristics returned
  - No writes were performed to any characteristic. No pairing/bonding
    requested. No record/delete/start/stop/firmware operations attempted.

---

## 2026-09-18 15:54–15:55 — Consistency re-check + passive notify listen

- Second connect/enumerate cycle (`gatt_enum_AABBCCDDEEFF_20260918T155403.json`):
  `d001`, `d003`, `d005`, `d00a` all identical to the first read (stable).
  Battery level changed `0x55` (85%) → `0x48` (72%) in ~8 minutes — flagged
  as an unexplained anomaly, not yet investigated further.
- `scripts/notify_listen.py AA:BB:CC:DD:EE:FF 0000b001-... 45` — subscribed
  to `cc68/b001` notify only (no writes to any other characteristic), listened
  45s. **Result: 0 notifications received.** Device does not spontaneously
  push storage/recording/state data over BLE without being queried.
  Log: `captures/notify_0000b001_20260918T155426.jsonl` (empty).
- Conclusion carried into `docs/protocol-state.md` and
  `docs/protocol-recordings.md`: `getState()` partially mapped to `d005`
  (read-only, no write needed); `getStorage()` and `getRecSessionWithUid()`
  are blocked — no read characteristic matches their expected payload shape,
  and passive notify listening confirms the device won't answer without a
  write to a command channel (`d007` or `b002`), which was not attempted
  per the no-guessed-write rule.

---

## 2026-09-18 16:02–16:03 — btmon capture attempt (not found) + third passive reconnect with ATT handles

- User reported starting `sudo btmon -w ~/c1-ble-research/captures/c1-session.snoop`
  in their own terminal. **Checked on this machine: no `btmon` process running**
  (`pgrep -a btmon` → empty) and **`~/c1-ble-research/` does not exist** on
  this machine (only `` does, the
  project directory created earlier this session). Flagged to the user
  rather than assuming capture data exists.
- Rescanned first (`scripts/scan_bleak.py`) to confirm the device was still
  advertising (RSSI -39 to -51, consistent) before reconnecting.
- Third read-only reconnect via `scripts/connect_and_enumerate.py`, now
  also recording real ATT handle numbers (added `service.handle` /
  `char.handle` output, sourced from BlueZ's GATT discovery):
  `captures/gatt_enum_AABBCCDDEEFF_20260918T160300.json`.
  - All previously-read values unchanged (`d001`, `d003`, `d005`, `d00a`).
  - Battery: `0x3c` = 60% (third data point; 85%→72%→60% across three
    ~8-9 min-apart sessions — anomalous drain rate, flagged, unexplained).
  - No notify subscription this round (fully passive pass, per instruction).
  - `bluetoothctl info` after disconnect: `Paired: no`, `Bonded: no`,
    `Trusted: no`, `Connected: no` — third confirmation of no pairing.
- Handle map written to `docs/gatt-handles.md`. Explicitly labeled as
  sourced from BlueZ D-Bus GATT discovery, not raw HCI/ATT PDU capture,
  since no btmon trace was available.
- No writes performed. No notify subscribed. No destructive operations.

---

## 2026-09-18 16:06–16:07 — First real HCI/ATT capture (btmon)

- User started `sudo btmon -w captures/c1-session.snoop`
  on this machine, in their own terminal (root). Confirmed running via
  `pgrep -a btmon` (PID 9303) and by checking the capture file existed and
  was growing (root-owned, world-readable `rw-r--r--`).
- Ran `scripts/connect_and_enumerate.py AA:BB:CC:DD:EE:FF` (same read-only
  script as before — reads only, no writes, no notify subscribe) while the
  capture was live. Values consistent with prior sessions; battery now
  `0x39` = 57% (fourth data point, continuing to drop: 85%→72%→60%→57%).
  Output: `captures/gatt_enum_AABBCCDDEEFF_20260918T160638.json`.
- Dumped the capture with `btmon -r c1-session.snoop` (1190 lines) and
  analyzed the full ATT PDU sequence by hand (grep + manual read of the
  decoded text, no hand-rolled btsnoop parser — avoided guessing at the
  binary format to prevent mis-parsed "confirmed" data).
- Findings (all wire-confirmed, see `docs/gatt-handles.md` and
  `docs/protocol-write-observations.md` for full detail):
  - LE Create Connection → Enhanced Connection Complete, 15ms interval.
  - ATT Exchange MTU: negotiated effective MTU = **89 bytes** (min of our
    517 request and the device's 89) — new information, relevant to
    `startSyncRecord`'s 160-byte alignment (a 160-byte unit will need
    multiple ATT packets at this MTU).
  - Zero SMP/pairing/encryption packets anywhere in the capture — stronger,
    wire-level confirmation of the no-pairing finding (previously only
    checked via `bluetoothctl info`).
  - Full service/characteristic/descriptor discovery matched the
    previously BlueZ-D-Bus-derived handle table exactly, plus revealed a
    **Generic Access Profile service (`0x1800`, handles `0x0001-0x0009`)
    that BlueZ hides from its D-Bus GATT API** — invisible to Bleak, only
    visible via raw HCI capture.
  - Exactly **one** `ATT: Write Request` in the whole capture: a CCCD write
    enabling Battery Level notifications (handle `0x002e`). Confirmed this
    was issued automatically by BlueZ's own battery-monitoring behavior,
    not by our script and not related to the vendor protocol. Zero writes
    touched `d007`, `b002`, or `2bb1`.
- Conclusion: this round produced real, wire-level evidence for everything
  already hypothesized from the D-Bus-only pass (all confirmed correct),
  plus new facts (MTU, hidden GAP service, zero-pairing at the wire level)
  that weren't previously visible. It did **not** produce any new evidence
  toward `getStorage()`/`getRecSessionWithUid()`/`startSyncRecord()` — no
  real SDK client connected during this capture, so there was no command
  traffic to observe. That gap remains open, see `docs/protocol-write-observations.md`.

---

## 2026-09-18 16:13 — Authorized single write probe: dd68/d007 = 0x00

- User explicitly authorized, after reviewing a specific plan (payload,
  monitoring, abort criteria) presented in chat, a single one-shot write of
  `0x00` (1 byte) to `dd68/d007`, via `scripts/probe_d007.py`.
- `btmon` was still capturing (`c1-session.snoop`, same file, still
  growing), so this write is wire-confirmed independently of the script's
  own report.
- Result: ATT Write Response, no error (device accepted the write
  structurally). Zero notifications on `b001` in a 5s window (confirmed at
  both app layer and wire layer — literally zero packets during that
  window). `d005` (state) and battery unchanged immediately after.
  Follow-up read-only pass ~1 min later (`scripts/check_secondary_effects.py`)
  checked `d001`/`d003`/`d005`/`d00a`/battery — all identical to every prior
  reading. **No observable effect anywhere from this write.**
- Full detail and interpretation (explicitly hedged, not overclaimed):
  `docs/protocol-write-observations.md`.
- No further opcodes attempted — this was a single-shot authorized
  experiment, not a green light to keep guessing. Stopped after one
  attempt as agreed.

---

## 2026-09-18 16:17 — Authorized single write probe #2: dd68/d007 = 0x01

- User authorized a second one-shot write, payload `0x01`, after reviewing
  the `0x00` result. Same script (`scripts/probe_d007.py 01`, extended to
  also check `d001`/`d00a` before/after this round).
- Wire-confirmed (`captures/c1-session.snoop`, packets #631/#633): `Data: 01`
  written to handle `0x001e`, clean `Write Response`, 5.007s of total wire
  silence afterward (no notify, no indicate, nothing).
- Result: identical to the `0x00` probe — `d005`, `d001`, `d00a`, battery
  all unchanged; 0 notifications on `b001`.
- Two data points now, same null result both times. Documented interpretation
  (not fact) in `docs/protocol-write-observations.md`: a bare single byte
  may not be the right command shape at all — real commands might need a
  longer/structured payload, or the response doesn't go through `b001`.
- Stopped again after this one attempt, per the same single-shot agreement.

---

## 2026-09-18 16:27–16:36 — USB mass-storage audit (new capability, not BLE)

- User connected C1 via USB. Read-only enumeration only, no root used or
  needed anywhere in this round.
- `lsusb`/`lsusb -t`: `0e8d:0002`, one interface, `Class=Mass Storage,
  Driver=usb-storage`. No serial port, HID, MTP/PTP, CDC-ACM, or any other
  interface exists — exactly one plain USB Mass Storage interface.
- `dmesg` (no root needed, readable by this user) confirms real identity
  directly: `Product: Sogou C1`, `Manufacturer: MediaTek Inc`. Block device
  `sda` (14.5 GiB), partition `sda1`, already auto-mounted by udisks2 at
  `/media/<user>/SOGOU C1` (vfat, rw, current user owns it).
- Browsed the filesystem read-only (`ls`, `find`, `cat`, `xxd`, `file`,
  `iconv`, `du`): `GUIDE.TXT` (GBK-encoded, decoded — SN matches BLE
  exactly), `ALBUM_DB` (2 bytes), `LOG/APP1.LOG` (plaintext firmware log,
  **reveals firmware version `V0127`**, historical battery % at boot
  events, boot/shutdown timestamps back to 2019), `STAT/*.DAT` (24 binary
  daily files, not decoded — not needed), `RECORD/<date>/<time>.WAV+.AVC`
  (**11 real recordings, 162MB total** — `.WAV` = standard 16-bit/16kHz
  mono PCM, confirmed via `file`; `.AVC` = raw/compressed, unidentified
  header, per `GUIDE.TXT` meant for server-side transcription upload, not
  needed for local playback), `.Trashes/501/` (3 more recently-deleted
  recordings still present — standard FAT non-erasure, not anything we
  did), `SPEECH/`/`NEWS/` (empty).
- Full detail: `docs/usb-findings.md`.
- **This directly and fully answers the "recording list" and "recording
  download" questions that BLE could not reach** — no protocol
  reverse-engineering needed, it's a standard filesystem.

## 2026-09-18 16:34–16:36 — USB/BLE correlation + 2BB0 indicate (new BLE findings)

- `scripts/notify_2bb0.py`: subscribed to `1910/2bb0` indicate (passive,
  same low-risk category as the earlier `b001` notify subscription — no
  writes to any command characteristic). 30s window, read `d005` mid-window.
  **Result: 0 indications received.** Same null result as `b001`.
- While USB was connected and mounted, re-read all known characteristics
  (`scripts/check_secondary_effects.py`): **`d005` changed for the first
  time ever in this entire investigation**, from the constant `01000000`
  to `0a100000`. `d001`/`d003`/`d00a` unchanged.
- Verified this was not a one-off glitch or connection-transient artifact:
  - `scripts/watch_d005.py` (10 reads, 1.5s apart, single connection):
    stayed `0a100000` for the first 9 reads (~13.5s) then reverted to
    `01000000` on the 10th — so it's not tied to the moment of connecting.
  - `scripts/correlate_usb_d005.py` (controlled test): read `d005`
    (baseline), then a read-only `find` on the mounted USB volume, then
    read `d005` immediately (still `0a100000`), then polled every 3s for
    15s (reverted to `01000000` within that window, despite continued BLE
    polling — ruling out "any BLE activity keeps it hot").
  - Confirmed via `/proc/uptime` vs. the `dmesg` USB-attach timestamp that
    USB had already been connected for ~6.7 minutes when this was first
    observed — ruling out "N seconds after USB plug-in" as the trigger.
- **Conclusion**: `d005` reacts to actual USB filesystem I/O activity
  (reads on the mounted volume), going "hot" during/shortly after such
  activity and decaying back to idle within roughly 3-15s of quiet. This
  is the first confirmed dynamic behavior observed on any characteristic
  in the whole investigation. Byte-level: idle=`01 00 00 00`,
  active=`0a 10 00 00`. Exact bit-to-field mapping (e.g. which bit is the
  SDK's documented `isUDisk`) is **not confirmed** — flagged as hypothesis
  only, see `docs/usb-findings.md` and `docs/cloud-api-independence.md`.

---

## 2026-09-18 (later) — Pure analysis pass, zero new device interaction

No new BLE connections, no new writes, no repeated experiments. All of the
following is computation on already-collected USB files plus reasoning
over already-documented GATT structure:

- Computed exact duration for all 11 USB recordings from WAV file size
  (`(bytes-44)/32000`, since format is confirmed 16-bit/16kHz/mono PCM).
- **Found the WAV/AVC file-size ratio is exactly 4.00x for every single
  recording, no exceptions.** Strong hypothesis: `.AVC` is raw 4-bit ADPCM
  (a classic 4:1-compression embedded speech codec) - consistent with
  `file` reporting no recognized header and with GUIDE.TXT's description
  of AVC as upload-for-transcription data. Not proven via byte-level
  reverse engineering of the AVC format itself.
- Compiled the full GATT capability tree (all services/characteristics,
  properties, current values, inferred function) into `docs/gatt-handles.md`
  - pure compilation from prior sessions' data, no new reads performed.
- Re-examined the `d005`/USB correlation data already collected and found
  an important nuance: "USB connected but idle" reads identically to "USB
  never connected" (`01000000` both), and only "USB actively being read"
  differs (`0a100000`) - this weakens the simple `isUDisk`-boolean
  hypothesis in favor of a more general "flash/storage activity" flag
  hypothesis. Documented in `docs/gatt-handles.md`. A clean physical
  unplug test (4th data point) was identified as still missing but not
  performed (would need the user to physically act).
- Built a reasoned (not tested) chunked file-transfer protocol structure
  from `startSyncRecord`'s 160-byte alignment + the confirmed 89-byte ATT
  MTU + the `cc68` (`b002` write / `b001` notify) architecture - written
  to `docs/protocol-recordings.md`. Identified `cc68/b002` as the
  top-recommended next write experiment (never attempted), replacing
  `d007` as the leading command-channel candidate based on architectural
  reasoning (clean 2-member write+notify service pair vs. `d007` sitting
  among 4 read-only "info register" characteristics).

---

## 2026-09-18 16:50 — Authorized single write probe #3: cc68/b002 = 0x00

- User authorized a single write to `cc68/b002` (not `d007`), same spec as
  the earlier two `d007` probes, after this investigation's architectural
  reasoning identified `b002`/`b001` as the strongest command-channel
  candidate. `scripts/probe_b002.py 00`, btmon still capturing.
- Wire-confirmed (`captures/c1-session.snoop`, packets #2691/#2693):
  `Data: 00` written to handle `0x0029`, clean `Write Response`, 5.006s of
  total wire silence afterward.
- Result: identical to both `d007` probes — `d005`/`d001`/`d00a`/battery
  all unchanged, 0 notifications on `b001`.
- Three data points now (d007=0x00, d007=0x01, b002=0x00), all null. This
  strengthens the "bare single byte isn't a well-formed command regardless
  of channel" interpretation over "wrong channel." Documented in
  `docs/protocol-write-observations.md`. No further payloads attempted
  without fresh authorization.

---

## 2026-09-18 16:54-16:57 — Batch write probes (user pre-authorized): 2bb1=0x00, b002=0x01 x2

- User authorized testing all remaining not-yet-tried write points as a
  batch, only interrupting for a fault or valuable finding (not per-step
  confirmation). Two gaps existed: `1910/2bb1` (never touched) and
  `b002=0x01` (only `0x00` tried). New script `scripts/probe_char.py`
  watches both `b001` notify and `2bb0` indicate simultaneously.
- `2bb1=0x00`: clean ack, zero change anywhere, zero events. Null result.
- `b002=0x01` (first try): clean ack, and `d005` changed `01000000` ->
  `0a100000` 5s after the write - looked like a possible first-ever
  write-correlated signal.
- Reproducibility check: confirmed `d005` cold via a standalone read
  immediately before, then repeated the identical `b002=0x01` write. This
  time `d005` was ALREADY hot in the very first baseline read of that new
  connection - before the write happened. **This falsifies causation.**
- Conclusion: the apparent signal was coincidental with background gvfs/
  udisks2 volume-monitor polling of the mounted USB volume (confirmed
  those processes are running via `ps aux`), not caused by any BLE write.
  Reinforces (doesn't overturn) the earlier USB-activity/`d005` finding -
  just clarifies the activity source is broader than our own deliberate
  commands. Full writeup: `docs/protocol-write-observations.md`.
- Net result across all 5 write attempts this investigation (d007x2,
  b002x2, 2bb1x1): none produced an effect attributable to the write
  itself. Stopped here per the user's "notify only on fault or valuable
  finding" instruction - this qualified as worth reporting (a promising
  signal that didn't survive reproducibility testing).

---

## 2026-09-18 17:08-17:20 — APK static analysis (major breakthrough, no device interaction)

- Downloaded `SogouAIRecorder.apk` v1.2.2 (76,188,239 bytes, sha256
  `09b799a95200ece9cc6ed64159242cf7f9776735445d941fd8f65fe56e8e2e40`) from
  the official historical URL. Verified genuine via `file`/`unzip -l`.
- Installed a portable JRE (Temurin 21, no root, extracted to
  `research/apk/tools/`) and jadx 1.5.1 (portable) to decompile.
- Raw `strings` pass across all 3 dex files found every GATT UUID this
  investigation has ever seen on the real device, verbatim, plus two never
  seen on the real device (`0000b003`, `0000d009`) - consistent with a
  firmware/hardware revision gap.
- Full jadx decompile (4795 classes, ~10 min, background) surfaced the
  exact `com.sogou.teemo.bluetooth.compatible.C1*` protocol classes:
  `CharacteristicHolder`, `C1GattCallbackHandler`, `C1ActionCreator`,
  `C1ActionParser`, `C1TaskCreator`, `StickProtocol`, `CRC16Util`.
- Recovered: full opcode tables both directions, 20-byte fixed CMD packet
  format (2-byte LE opcode + zero-padded payload), exact response field
  layouts for storage/sessions/files, CRC16 algorithm (used only for OTA
  and file-download validation, not every packet), and proof (via
  `ENABLE_INDICATION_VALUE` on `charCMDS2A`) that the general command
  channel is `1910/2bb1` (write) + `1910/2bb0` (indicate) - not `d007` or
  `b002` as earlier architecturally hypothesized.
- Cross-validated `d001` = firmware version characteristic: decoded value
  "V127" from `56 7f 00 00` matches the USB log's "V0127" exactly.
  Cross-validated `d005` = state (already known) and `d003` = SN (already
  known) against the decompiled parsing logic.
- Confirmed getSn/getStatus/getBattery/getVersion/requestMTU are plain
  GATT reads in the official app too - not opcode commands - matching
  this investigation's own experimental findings from months of BLE-only
  work, now explained rather than just observed.
- Answered the long-standing token/userId question: C1's own protocol
  code path has no handshake/token requirement; that machinery exists
  only for a different product (translate pen) in the same shared app.
- Native `.so` check (symbols only, no disassembler installed):
  `libnative-crc16.so` is a JNI mirror of the same CRC16 algorithm;
  `libencrypt_sogou_v00.so` is generic OpenSSL AES, no BLE-related
  symbols, not implicated in the command protocol.
- Full writeup: `docs/apk-protocol-recovery.md`.
- **No new device interaction performed this round** - this was 100%
  static analysis. A specific, evidence-based verification experiment
  (writing a real `getFreeSize()` packet to `2bb1`) is proposed to the
  user, not yet executed.

---

## 2026-09-18 17:27-17:31 — Full real-device verification (c1_local/full_device_test.py)

Built `c1_local/full_device_test.py` per the user's detailed spec: single
BLE session, full logging to `logs/full_device_test.log` and
`captures/full_device_test/`, only protocol recovered from the APK, no
blind guessing.

**Run 1 (17:27, no capabilities handshake)**:
- Device info reads all succeeded: SN matches USB exactly, firmware
  "V127" matches USB log's "V0127", battery 93%, state=0x100a (the
  known "hot"/active value, unrelated to this test).
- `getFreeSize` (opcode 28) sent to `2bb1` — wire-confirmed clean write,
  **zero response** within 8s.
- `getSessions` (opcode 6, startSession=0) sent — same, **zero response**.
- `stopSync` sent (no response expected/documented for it).
- Wire capture confirmed both writes were cleanly ATT-acked (no error),
  but literally zero indication packets arrived on `2bb0` for either.

**Root-cause investigation** (pure static analysis, no device touched):
found `StickManager.init4C1Type()` in the decompiled APK — the real app's
actual post-connect sequence is `setCmdNotify() -> setFileNotify() ->
setBatteryNotify() -> sendAppConfigInfo(true, null) -> getSN -> ... ->
getFreeSize() -> requestMTU() -> getBattery() -> getVersion() -> getSSN()
-> getStatus() -> setTime() -> getConfigComplete()`. The very first
protocol-level action is `sendAppConfigInfo()`, which builds
`C1ActionCreator.ackInfo(token, newToken)` — a capabilities-ack packet
(opcode 14) — which our first run skipped entirely.

For an account-less device (this one: never logged into any Sogou
account, confirmed by `UserManager.getBindId()`/`sgUnionId` both being
null in that code path), `ackInfo(null, null)` decompiles to a fully
determined packet: `0e 00 01 01 03 00 00 00 01 01 00 00 00 00 00 00 00 00
00 00`.

**Run 2 (17:31, with the capabilities handshake added)**:
- `ackInfo(null,null)` sent first — **got a real 82-byte indicate response,
  opcode 14 echoed back.**
- `getFreeSize` sent — **got a real response, opcode 25**:
  `totalKB=15153280, freeKB=14981408, bytesPerSecond=40000, isFull=false`.
  `totalKB*1024 = 15,516,958,720 bytes ≈ 14.45 GiB`, matching the USB
  block device's reported 14.5 GiB/15.5 GB almost exactly. **First
  successful, cross-validated BLE command/response cycle in this entire
  investigation.**
- `getSessions` (opcode 6) still got **zero response**, wire-confirmed
  (clean write ack, then silence). Not yet explained with certainty —
  leading hypothesis: the device's internal pending-sync queue is empty
  (all real recordings may already be marked "finished/synced" from past
  use with the official app before the service shut down), and firmware
  doesn't send anything when there's nothing to report.
- `stopSync` sent at the end as planned.
- Because no session ID was ever obtained, `getFiles`/`startSyncRecord`
  (file download) could not be attempted this round — they need a real
  `sessionId`, which `getSessions` was supposed to supply.
- Full raw JSON: `captures/full_device_test/result_20260918T173103.json`.
  Full JSONL event log: `captures/full_device_test/run_20260918T173103.jsonl`.
  Human log: `logs/full_device_test.log`.

**No destructive operations attempted.** `startRecord`/`stopRecord`/
`pauseRecord`/`resumeRecord`/`delRecord`/`depair`/OTA/restore-factory were
all deliberately not executed this round, per the task's own conditional
guidance — reasoning given in the final report rather than executed.

---

## 2026-09-18 17:39-17:42 — End-to-end test: USB baseline, manual copy test, remote start/stop attempt

- USB baseline snapshot built (`c1_local/usb_baseline.py`), full SHA256 for
  all 11 recordings, saved to `captures/usb_baseline.json`.
- Manual USB copy test: copied `20250312/23_48_00.WAV` (55,724 bytes) to
  `RECORD/TEST_E2E_manualcopy.WAV` directly via `cp`. Mount stayed healthy,
  all 8 original date folders and 11 original files untouched. Whether the
  device's own internal index "sees" this manually-copied file could not
  be determined this round (would need a working `getSessions`, which
  doesn't respond reliably — see below).
- Static APK check: **no BLE rename capability exists.** Searched
  `com/sogou/teemo/bluetooth/` (the actual C1 protocol package) for
  rename/setName/modifyName — zero hits. The only "rename" strings in the
  whole APK are cloud API endpoints (`/parrot/apis/record/v1/rename_record`)
  — renaming is a server-side/database display-name operation, not
  something sent to the device over BLE. This directly answers one of the
  task's open questions with real evidence, not speculation.
- Static APK check: **AMR audio streaming capability exists** (`APP_AMR_PLAY_REQ`/
  `APP_AMR_STOP_IND` opcodes 22/23, `STICK_AMR_DATA_REQ`/`STICK_AMR_STOP_IND`,
  dedicated `charFileAMR` characteristic — real, in `C1ActionCreator`/
  `C1ActionParser`). But `amrPlayRequest(uid, fileSize, frameSize)` takes a
  `fileSize` parameter, meaning it streams an **existing stored file**
  chunk-by-chunk for playback — not a live microphone passthrough. No
  evidence found of true live-monitoring-while-idle. Not tested on the
  real device this round (would need a real recording to stream, and
  wasn't the priority given the recording test below didn't succeed).

**Full BLE E2E run** (`c1_local/e2e_test.py`, one continuous session):
- Device info + capabilities handshake + storage query: all repeated
  successfully, consistent with the prior breakthrough run.
- `getSessions` before recording: zero response (consistent with before).
- **`startRecord` (opcode 10, recordType=1/Common, exact decompiled
  packet layout) sent to `2bb1`.** Got a real response: opcode 4
  (`STICK_RECORD_START_CNF`), but **decoded `sessionId = 0`** — wire-hex
  confirmed (`04 00 00 00 00 00 01 00 00 00 ...`), not a parsing bug.
- Read `d005` immediately after: **still `0x0001` (STATE_POWERED_ON) — did
  NOT change to the documented `0x1003` (STATE_RECORDING).**
- Waited 5s (intended recording duration), sent `stopRecord` (opcode 2) —
  got response opcode 3 (`STICK_RECORD_STOP_IND`).
- `getSessions` after: still zero response, zero sessions.
- **Ground truth check via USB: no new file, no new date folder — RECORD/
  contains exactly the same 11 original recordings plus our own manual
  test copy.** Confirms, independently of BLE, that no real recording was
  created.

**Conclusion: the device acknowledges `startRecord`/`stopRecord` at the
opcode/ATT level, but no evidence a real recording actually happened.**
The `sessionId=0` in the start confirmation is the most likely tell — a
genuine successful start should assign a real (non-zero) session id: `0`
may be this protocol's rejection/no-op sentinel, not a placeholder to be
filled in later. Not confirmed; no further opcode/parameter variations
were attempted (would cross into exactly the "guessed parameters" the
task explicitly prohibited — the packet sent matches the decompiled
`C1ActionCreator.startRealtime()`/`stopRecord()` exactly).

**Downstream tests blocked, not executed, because there was no real new
recording to work with**: session-list-after-recording (already covered,
empty), file download of the "new" recording, downloaded-audio playback
verification, pause/resume, and the full start→stop→session→list→download→
playback closed loop. None of these were skipped by choice — they
structurally require a real recording, which this test did not produce.

No destructive operations attempted (`delRecord`, restore factory
settings, depair, OTA — none sent, per the task's own restriction). The
manual USB test file (`TEST_E2E_manualcopy.WAV`) was left in place, not
deleted (it's a copy we created for testing, not an original device
recording; not removed since cleanup wasn't explicitly requested).

---

## 2026-09-18 17:55-18:00 — Physical recording, real sessionId confirmed, USB ground truth

- User physically operated the C1's own record button while a passive BLE
  monitor (`c1_local/watch_physical_record.py`) watched `d005` state and
  the `2bb0` indicate channel. No commands sent besides the proven-necessary
  capabilities handshake (opcode 14) and passive `d005` polling.
- **Unprompted discovery**: immediately after the capabilities handshake,
  the device spontaneously streamed ~30 `STICK_GET_STAT_CNF` (opcode 26)
  indications with zero request from us. Decoded the first few dates
  (bytes[2:6] as LE uint32): 20241105, 20241115, 20241223, etc — **exact
  matches to the `STAT/*.DAT` filenames already known from USB.** The
  device dumps its entire usage-statistics history over BLE automatically
  once the capabilities handshake completes.
- **Physical button press produced a real, complete recording cycle**,
  captured live via indicate:
  - `STICK_RECORD_START_IND` (opcode 1), sessionId = `0x6aabb161` (real,
    non-zero — contrast with our own BLE-triggered attempt's `sessionId=0`)
  - `d005` read immediately after: `0x1003` (`STATE_RECORDING`) — the
    first time this documented state value has ever been observed on the
    real device
  - ~19s later: `STICK_RECORD_STOP_IND` (opcode 3), same sessionId
  - `d005` back to `0x0001` (`STATE_POWERED_ON`)
- **USB ground truth, after reconnecting**: a new folder `RECORD/20260917/`
  appeared with two real recordings:
  - `17_22_41.WAV` — 633,004 bytes, duration 19.78s. **This is the
    recording we watched live.** Cross-check: `sessionId 0x6aabb161`
    decoded as a little-endian uint32 Unix timestamp = `2026-09-17
    09:22:41 UTC`. Device's local timezone offset UTC+8 → `17:22:41` —
    **exact match to the filename**, and the duration matches what was
    observed (~19-20s). **This conclusively confirms `sessionId` is the
    Unix timestamp (device's own clock) of recording start.**
  - `17_20_01.WAV` — 1,001,644 bytes, duration 31.30s — an earlier
    recording (button press before the BLE monitor started watching),
    same day.
  - Device's own clock is confirmed running ~1 day behind real time
    (folder dated `20260917`, but the physical press was observed in
    real/host wall-clock time on `2026-09-18`) — consistent with
    `setTime()` never having been called in any session so far.
  - SHA256 recorded for both new files for future reference:
    `17_20_01.WAV` = `8ae252ac...`, `17_22_41.WAV` = `a5a2ec1e...`.
  - Both files confirmed standard RIFF/WAVE 16-bit/16kHz/mono PCM via
    `file`, same format as every other recording on the device.
- USB was physically unplugged by the user during the recording (to hold
  the device) — confirmed via `dmesg` (`usb 1-1: USB disconnect`) — and
  reconnected afterward via `udisksctl mount`. BLE stayed connected
  throughout the disconnect/reconnect, confirming USB and BLE are fully
  independent transports as expected.
- **This round did not attempt BLE download or a fresh `getSessions` test
  using this real sessionId** — the BLE connection had already closed
  (monitor script's `async with` block exited normally) by the time USB
  was checked. A follow-up BLE session would be needed to test whether
  `getSessions`/`getFiles`/`startSyncRecord` behave differently now that a
  real, undownloaded session genuinely exists on the device.
