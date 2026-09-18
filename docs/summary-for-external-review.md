# Sogou C1/C18D BLE Protocol Investigation — Summary for External Review

Status snapshot as of 2026-09-18. Written to be self-contained (no need to
read the other files in this repo to follow it). Everything is separated
into **Confirmed** (directly observed, either at the raw HCI/ATT wire level
via `btmon`, or via BlueZ/Bleak application-layer calls) vs. **Hypothesis**
(inferred, not proven) vs. **Unknown** (no evidence either way). Please
don't treat any hypothesis below as fact when reasoning about next steps.

## 1. Goal

Determine, from Linux only (no Android app, no cloud), how much of the
Sogou AI Recorder C1/C18D's functionality is reachable over BLE:
- Can it be discovered and connected? (yes, confirmed)
- Can device state, storage, and the recording list be read? (state:
  partially; storage/recordings: blocked, see below)
- Can an existing recording be downloaded over BLE, bypassing Sogou's
  cloud entirely? (not yet answered — blocked upstream of this)

## 2. Environment

- Linux kernel 7.0.0-31-generic, BlueZ 5.72, Python 3.12.3, Bleak 3.0.2 (in
  an isolated venv).
- Real hardware: a Sogou C1 recorder, MAC `AA:BB:CC:DD:EE:FF` (BLE random
  static address), advertised name `搜狗AI录音笔`, serial number
  `5200000000000000` (read from GATT, also embedded in the advertisement's
  manufacturer data).
- Tooling used: `bluetoothctl`, `btmon` (raw HCI/ATT capture, run with root
  by the user), Python + Bleak (application-layer GATT client).
- The only public reference material is a WeChat mini-program plugin
  README (`github.com/zxjay/Sogou-AI-Recorder-mp-plugin`) — it documents
  the JS-level SDK API surface (function signatures, callback shapes) but
  contains **zero** BLE protocol detail: no UUIDs, no opcodes, no byte
  layouts. The plugin binary itself is closed-source, not in that repo.

## 3. Confirmed GATT structure (wire-verified via btmon HCI/ATT capture)

All of the following were independently confirmed twice: once via BlueZ's
D-Bus GATT API (through Bleak), and once by decoding the raw HCI/ATT PDUs
in a `btmon` capture. Both sources agree exactly.

| Handle (decl → value) | UUID | Properties | Read value | Notes |
|---|---|---|---|---|
| `0x0002`→`0x0003` | Device Name `0x2a00` (standard GAP) | Read, Write | not read (see §6) | Service `0x1800`, hidden from BlueZ's D-Bus API — only visible via raw HCI |
| `0x0004`→`0x0005` | Appearance `0x2a01` | Read | not read | same hidden GAP service |
| `0x0006`→`0x0007` | Peripheral Preferred Connection Params `0x2a04` | Read | not read | |
| `0x0008`→`0x0009` | Central Address Resolution `0x2aa6` | Read | not read | |
| `0x000c`→`0x000d` | `0x2bb1` (vendor) | Write | n/a (write-only) | Service `0x1910`. UUID coincidentally matches a SIG-assigned "CTE" characteristic name — almost certainly unrelated, just 16-bit UUID space reuse |
| `0x000f`→`0x0010` | `0x2bb0` (vendor) | Indicate | n/a | never subscribed |
| `0x0014`→`0x0015` | `0xd001` (vendor) | Read | `567f0000` (constant across 5+ reads) | Also appears verbatim in the BLE advertisement's manufacturer data. Service `0xdd68`, the "info/state" service |
| `0x0017`→`0x0018` | `0xd003` (vendor) | Read | `5200000000000000` (ASCII) | This is the device serial number |
| `0x001a`→`0x001b` | `0xd005` (vendor) | Read | `01000000` (constant across every read, before and after write probes) | Leading candidate for SDK `getState()`'s `state` field — value 1 matches documented enum `0x0001` = "powered on" |
| `0x001d`→`0x001e` | `0xd007` (vendor) | Write, Write-without-response | n/a (write-only) | **The write probe target — see §5** |
| `0x0020`→`0x0021` | `0xd00a` (vendor) | Read | `02` (constant) | Meaning unknown |
| `0x0024`→`0x0025` | `0xb001` (vendor) | Notify | n/a | Service `0xcc68`, the "data channel" service. Never sends anything unsolicited (confirmed passively) |
| `0x0028`→`0x0029` | `0xb002` (vendor) | Write, Write-without-response | n/a (write-only) | Second write-capable candidate, never written |
| `0x002c`→`0x002d` | Battery Level `0x2a19` (standard) | Read, Notify | dropping over time: 85%→72%→60%→57%→52%→50% across ~40 min of intermittent connections (anomalous drain rate, unexplained) | Service `0x180f` |

Session facts also confirmed at the wire level:
- **Effective ATT MTU = 89 bytes** (our host requested 517, the device
  requested/responded 89; negotiated MTU = min = 89). Max single ATT
  payload = 86 bytes. Relevant to any future file-transfer analysis.
- **Zero SMP/pairing/encryption packets** in any capture across 6+
  connect/disconnect cycles. The device accepts plain unauthenticated,
  unencrypted GATT connections — no pairing ever occurs or is required for
  any read performed so far.
- Every connection is fast and clean: LE Create Connection → Connection
  Complete in <100ms, 15ms connection interval, clean disconnects.

## 4. SDK API surface (from the WeChat plugin README, for reference)

Documented JS-level calls and their callback shapes (paraphrased):

- `initSDK(userId, token, type, protocol, callback)` — app-layer init, not
  BLE-specific.
- `startScanRecorder()` → `onDeviceFound([{name, sn, deviceId}])`
- `connectRecorder(deviceId, deviceName, sn)` → `onConnectComplete()`
- `getState()` → `onGetState(state, isUDisk, isPrivacy, keyState)`. `state`
  enum: `0x0000` init, `0x0001` powered on, `0x0002` BLE connected,
  `0x1003` recording, `0x1111`/`0x2222` paused/stopped (plugin-defined).
- `getStorage()` → `onGetStorageVolumeWithTotalKB(totalKB, freeKB,
  bytesPerSecond, isFull)`
- `getRecSessionWithUid(uid, sessionId, isOnlyOne)` →
  `onGetRecSessionsWithUid(uid, total, startPos, sessionArray)` where
  `sessionArray` is a list of `[memoid, dur]` pairs.
- `startSyncRecord(sessionId, fileId, start, end, type)` — `start`/`end`
  must be multiples of **160 bytes**, `end=0` means "to end of file". No
  callback is documented in the README for the actual data delivery.
- `stopSync()` → `onStopSync()`
- `startRecord`/`stopRecord`/`pauseRecord`/`resumeRecord`/`delRecord`/
  `depair` — all destructive or state-mutating, **not tested**, out of
  scope for this investigation by design.

Every query-style API (`getState`, `getStorage`, `getRecSessionWithUid`) is
shaped as "call with no return value, answer arrives via a separate
callback" — consistent with, but not proof of, a BLE "write command → get
notify response" pattern.

## 5. API → BLE mapping status

| SDK API | Status | Evidence |
|---|---|---|
| `connectRecorder` | **Confirmed** | Plain BLE connect succeeds repeatedly, no pairing |
| `getState` | **Partial hypothesis** | `d005`=`01000000` (read-only) is consistent with `state`=1="powered on", but the other 3 fields (`isUDisk`, `isPrivacy`, `keyState`) have no identified characteristic, and no state *transition* has ever been observed (device was always idle) |
| `getStorage` | **Blocked, no evidence** | No read characteristic is shaped like the expected 4-field payload (totalKB/freeKB/bytesPerSecond/isFull). Passive notify listening produced nothing |
| `getRecSessionWithUid` | **Blocked, no evidence** | Same — no characteristic is shaped like a variable-length list; nothing arrives unsolicited |
| `startSyncRecord` | **Cannot be attempted** | Transitively blocked (needs a real `sessionId` from the previous item) |
| destructive APIs | **Deliberately not tested** | Out of scope |

## 6. Write probe experiments (the most direct evidence toward unblocking §5)

Two single-shot, explicitly user-authorized write experiments were run
against `dd68/d007` (the write-only characteristic in the same service as
`d005`/`d001`/`d00a`/`d003`), each with full `btmon` wire capture:

**Probe 1**: wrote `0x00` (1 byte), Write Request (with response).
**Probe 2**: wrote `0x01` (1 byte), Write Request (with response).

Both probes, identical outcome:
- ATT-level: clean `Write Response`, zero bytes, no error code. The
  characteristic structurally accepts short writes without protocol-level
  rejection.
- `b001` notify: subscribed before each write, **zero notifications** in a
  5-second window after — confirmed both by the app-layer callback and
  independently by total wire silence in the raw capture during that
  window.
- No change in `d005`, `d001`, or `d00a` before vs. after (probe 2 checked
  all three; probe 1 checked `d005` only, then a separate read-only pass
  ~1 minute later confirmed `d001`/`d00a` also unchanged).
- No indications on `2bb0` (never subscribed, never seen unsolicited).
- Battery unaffected beyond its already-noted steady background drain.

**`b002`** (the other write-capable vendor characteristic, in the `cc68`
"data" service alongside `b001` notify) has **never been written to**.
**`2bb1`** has also never been written to.

No further write experiments have been run — each was authorized
individually, one at a time, and the investigation deliberately stopped
after 2 data points rather than blindly sweeping the byte space.

## 7. Open questions / where this is stuck

1. **`getStorage()` and `getRecSessionWithUid()` cannot be reached by
   reading anything** — confirmed no static characteristic holds this data.
2. **Whatever triggers those responses requires a write**, almost
   certainly to `d007` or `b002` — but two single-byte guesses (`0x00`,
   `0x01`) on `d007` produced zero observable effect, suggesting either:
   - the command format needs more structure (length-prefixed? multi-byte
     opcode? checksum? a session/sequence field?),
   - `b002` is the actual trigger instead of `d007` (not yet tried at all),
   - the response doesn't arrive via `b001` (not yet tried subscribing to
     `2bb0` indicate, or checking if a response might land on `b001` only
     after subscribing to *both* channels, or after a longer wait),
   - or there's a required setup step first (e.g. writing to `2bb1`, or a
     specific sequence/handshake) that hasn't been tried.
3. **No ground-truth protocol data exists at all.** The user no longer has
   the original WeChat mini-program / Sogou app available to capture real
   SDK traffic, which would have been the highest-value way to resolve
   this without guessing.
4. Minor unexplained anomaly: **battery level has dropped steadily
   (85%→50%) over ~40 minutes of intermittent BLE connect/disconnect
   cycles** — never independently verified against the device's own
   on-device indicator, cause unconfirmed (could be connection overhead,
   could be a coincidentally-timed real drain, could be a noisy fuel
   gauge).
5. `d00a` = `0x02` and `d001` = `0x567f0000` remain fully unexplained —
   both are constant, read-only, and don't match any documented SDK field
   name obviously.

## 8. What has NOT been tried (deliberately, for safety)

- Any multi-byte / structured payload to `d007`.
- Any write to `b002` or `2bb1`.
- Subscribing to `2bb0` indicate.
- Reading the newly-discovered hidden GAP service (`Device Name`,
  `Appearance`, etc.) — not blocked, just not prioritized yet; these are
  pure reads, low effort, could be done anytime.
- Anything from the explicitly-excluded destructive API list
  (`startRecord`, `stopRecord`, `delRecord`, `depair`, firmware/OTA).
- Byte-space sweeping / brute-forcing opcodes — explicitly avoided per the
  investigation's "no guessing without per-step authorization" rule.

## 9. Artifacts available if deeper analysis is wanted

All in `c1-ble-research/` on the investigator's machine:
- `docs/sdk-api-map.md` — full SDK API extraction + mapping matrix
- `docs/gatt-handles.md` — full wire-confirmed handle table with notes
- `docs/protocol-state.md`, `docs/protocol-recordings.md`,
  `docs/protocol-write-observations.md` — per-topic deep dives
- `docs/experiment-log.md` — full chronological experiment log with raw
  values
- `captures/` — raw JSON captures, two full `btmon` text dumps, the
  `.snoop` binary capture itself
