# Sogou AI Recorder Mini-Program SDK — API Inventory

Source: [`reference/sogou-recorder-plugin/README.md`](../reference/sogou-recorder-plugin/README.md)
(GitHub: `zxjay/Sogou-AI-Recorder-mp-plugin`, closed WeChat mini-program plugin,
provider `wxcaba63e60ef46eab`). This README is the *only* technical content in
that repo — no source, no UUIDs, no byte-level protocol. Everything below is
extracted verbatim from the documented JS call signatures and callbacks; BLE
mapping is a separate, evidence-based step (see `docs/protocol-*.md`).

## Init / lifecycle

### `initSDK(userId, token, type, protocol, callback)`
- **Input:** `userId` (pairing identity), `token` (SDK-assigned auth token),
  `type` (recorder model, currently `1`), `protocol` (protocol version,
  currently `1`), `callback` object.
- **Output:** none directly; async via callback.
- **Callback fields used:** `onDeviceFound`, `onError`, and (implicitly) all
  the other `on*` callbacks listed below get wired through this same object.
- **README note:** must be called from `App.onLaunch`.

### `startScanRecorder()`
- **Input:** none.
- **Output:** none directly.
- **Callback:** `onDeviceFound(deviceArray)` — `deviceArray: [{name, sn, deviceId}, ...]`.
- **README note:** "该插件仅能扫描到并且连接搜狗AI录音笔，其它蓝牙设备会被忽略" — plugin-side
  filtering only scans for/keeps Sogou recorders, i.e. relies on BLE
  advertisement filtering (name/manufacturer data) client-side.

### `stopScanRecorder()`
- **Input:** none. **Output:** none. **Callback:** none documented.

### `connectRecorder(deviceId, deviceName, sn)`
- **Input:** `deviceId`, `deviceName`, `sn` — all three sourced from a prior
  `onDeviceFound` entry.
- **Output:** none directly.
- **Callback:** `onConnectComplete()` — no params, fires once BLE link (and
  presumably any handshake) is done.

### `depair(isClearFile)`
- **Input:** `isClearFile` (bool) — true clears recordings on the device,
  false keeps them (default).
- **Output / callback:** not documented.
- **Risk:** destructive if `isClearFile=true`. Not tested (Phase 1 read-only rule).

## State / storage queries

### `getState()`
- **Input:** none.
- **Output:** none directly.
- **Callback:** `onGetState(state, isUDisk, isPrivacy, keyState)`.
  - `state` enum: `0x0000` init, `0x0001` powered on, `0x0002` BLE connected,
    `0x1003` (4099) recording, `0x1111` (4369, plugin-defined) paused,
    `0x2222` (8738, plugin-defined) stopped.
  - `isUDisk`: USB mass-storage mode flag.
  - `isPrivacy`: privacy mode flag.
  - `keyState`: reserved, undocumented.

### `getStorage()`
- **Input:** none.
- **Output:** none directly.
- **Callback:** `onGetStorageVolumeWithTotalKB(totalKB, freeKB, bytesPerSecond, isFull)`.
  - `totalKB` / `freeKB`: capacity in KB.
  - `bytesPerSecond`: recording bitrate/throughput in bytes/sec.
  - `isFull`: boolean, storage full flag.

## Recording session listing / control

### `getRecSessionWithUid(uid, sessionId, isOnlyOne)`
- **Input:** `uid` (request id, timestamp), `sessionId` (recording file id),
  `isOnlyOne` (reserved, currently `false`).
- **Output:** none directly.
- **Callback:** `onGetRecSessionsWithUid(uid, total, startPos, sessionArray)`.
  - `total`: total file count.
  - `startPos`: reserved (pagination offset, unused per README).
  - `sessionArray`: list of `[memoid, dur]` pairs — `memoid` = recording id,
    `dur` = duration in ms.

### `startRecord(recordType, scene)` — **not tested (destructive/write, out of scope for Phase 1)**
### `stopRecord(recordType)` — **not tested**
### `pauseRecord(sessionId)` — **not tested**
### `resumeRecord(sessionId, scene)` — **not tested**
### `delRecord(sessionId)` — **not tested, destructive**

## File sync (download)

### `startSyncRecord(sessionId, fileId, start, end, type)`
- **Input:**
  - `sessionId`: recording file id (same as `memoid`?).
  - `fileId`: documented as `=1` in the example — looks like a constant/fixed
    stream identifier rather than a per-file id (unclear, README example
    literally shows `fileId=1` as a fixed value, not derived from the session).
  - `start`: byte offset into the file, **must be a multiple of 160**.
  - `end`: byte offset to stop at; `0` means "to end of file", range is
    `0..filesize`, also must be a multiple of 160.
  - `type`: reserved, currently `0`.
- **Output:** none directly.
- **Callback:** not explicitly named in README (no `onSyncRecord`/`onData`
  callback documented) — file data delivery mechanism is undocumented in the
  README. Likely delivered via a notify characteristic in chunks, given the
  160-byte alignment (suggests a fixed BLE notification payload size, e.g. a
  20-byte ATT MTU multiple is more typical for BLE — 160 doesn't map to a
  standard default MTU, so this device likely negotiates or assumes a larger
  MTU, or the SDK reassembles multiple notify packets per 160-byte unit).
- **README note:** this is the one API explicitly called out by the user as
  "not to call/simulate yet" until the transport characteristic is confirmed
  by other means.

### `stopSync()`
- **Input:** none. **Output:** none.
- **Callback:** `onStopSync()` — no params.

## Other callbacks (control acks, not separately triggered by a query API)

- `onPauseRecord(sessionId, scene, startPos, status)`
- `onResumeRecord(sessionId, scene, startPos, status)`
- `onStartRecord(sessionId, scene, startPos, status)`
- `onStopRecord(sessionId, reason, fileExist, fileSize, status)`
- `onError(errorCode, value, errorMsg)`

## Observations relevant to BLE mapping

1. Every query-style API (`getState`, `getStorage`, `getRecSessionWithUid`) is
   **write-then-callback** shaped: the JS call itself carries no return value,
   and the answer always arrives through a separate `on*` callback. This is
   the classic BLE "write a command to a control characteristic, receive the
   answer via notify" pattern — it does **not** by itself prove any specific
   characteristic requires a write, but it means we should not expect a
   plain `read()` to return storage or recording-list data unless the device
   happens to cache the answer in a static characteristic (state looks like
   it does; storage/recordings plausibly do not, see `docs/protocol-*.md`).
2. `startSyncRecord`'s 160-byte alignment strongly implies the BLE file
   transfer is chunked in fixed units, likely reassembled from `notify`
   packets on the data channel (`cc68/b001` is our leading candidate, see
   protocol docs).
3. Nothing in the README specifies MTU, encryption, or whether a write needs
   a response — these are protocol details invisible from the mini-program
   API surface and must come from live GATT observation only.

## API → BLE investigation matrix

Confirmed GATT map on the real device (`AA:BB:CC:DD:EE:FF`), from
`captures/gatt_enum_AABBCCDDEEFF_20260918T155403.json`:

- **Battery**: `180f` / `2a19` (read, notify) — standard SIG Battery Level
- **Vendor info/state** `dd68`: `d001` (read), `d003` (read, = SN),
  `d005` (read), `d00a` (read), `d007` (write, write-without-response)
- **Vendor data channel** `cc68`: `b001` (notify), `b002` (write, write-without-response)
- **Vendor** `1910`: `2bb0` (indicate), `2bb1` (write) — 16-bit UUIDs that
  collide with SIG-assigned "Constant Tone Extension" names; almost
  certainly unrelated, proprietary reuse of that UUID space (not verified
  as CTE, not assumed to be CTE)

No UUID's meaning is assumed beyond what read data or documented SDK shape
supports.

| SDK API | README input | README output | Possible GATT | Status | Next step |
|---|---|---|---|---|---|
| `initSDK` | userId, token, type, protocol | none (async via callback) | n/a — app-layer/pairing bookkeeping, not a single characteristic | not applicable to BLE-only investigation | none |
| `startScanRecorder` | none | `onDeviceFound([{name,sn,deviceId}])` | BLE advertisement (name `搜狗AI录音笔`, manufacturer data w/ embedded SN) | **confirmed** — matches passive scan data | none needed |
| `stopScanRecorder` | none | none | stop scanning (client-side only) | n/a | none |
| `connectRecorder` | deviceId, deviceName, sn | `onConnectComplete()` | plain BLE GATT connect | **confirmed** — Bleak connect succeeds, no pairing required | none needed |
| `getState` | none | `onGetState(state,isUDisk,isPrivacy,keyState)` | `dd68/d005` (read) | **partial** — `d005`=1 plausibly matches `state`=powered-on; other 3 fields unconfirmed | observe a real state transition (out of scope, needs write or physical action) |
| `getStorage` | none | `onGetStorageVolumeWithTotalKB(totalKB,freeKB,bytesPerSecond,isFull)` | none found | **blocked** — no read characteristic is storage-shaped; passive notify on `b001` produced 0 events | requires write to a command char (`d007`/`b002`) — not attempted, needs explicit authorization or external protocol ground truth |
| `getRecSessionWithUid` | uid, sessionId, isOnlyOne | `onGetRecSessionsWithUid(uid,total,startPos,sessionArray)` | none found | **blocked** — same reason as storage; variable-length list can't live in any fixed-size read char observed | same as above |
| `startSyncRecord` | sessionId, fileId, start(×160), end(×160), type | undocumented in README (no callback named) | `cc68/b002` (write) is the leading candidate for the trigger, `cc68/b001` (notify) for the data return — **unverified** | **not attempted** — transitively blocked by `getRecSessionWithUid` and by the no-guessed-write rule | needs confirmed session id + confirmed command opcode first |
| `stopSync` | none | `onStopSync()` | unknown | not investigated | n/a for Phase 1 |
| `startRecord` | recordType, scene | `onStartRecord(...)` | unknown | **not tested — destructive, excluded** | excluded from Phase 1 |
| `stopRecord` | recordType | `onStopRecord(...)` | unknown | **not tested — destructive, excluded** | excluded from Phase 1 |
| `pauseRecord` | sessionId | `onPauseRecord(...)` | unknown | **not tested — excluded** | excluded from Phase 1 |
| `resumeRecord` | sessionId, scene | `onResumeRecord(...)` | unknown | **not tested — excluded** | excluded from Phase 1 |
| `delRecord` | sessionId | undocumented | unknown | **not tested — destructive, excluded** | excluded from Phase 1 |
| `depair` | isClearFile | undocumented | unknown | **not tested — destructive, excluded** | excluded from Phase 1 |
