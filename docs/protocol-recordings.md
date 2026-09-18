# Protocol investigation: `getStorage()` and `getRecSessionWithUid()`

## Method

- Full read-only GATT enumeration (twice, ~8 min apart) — see
  `docs/protocol-state.md` for the shared characteristic table.
- Passive `notify` subscription on `cc68/b001` for 45s, **no writes sent** —
  `captures/notify_0000b001_20260918T155426.jsonl`, 0 events received.
- No write attempted to `dd68/d007`, `cc68/b002`, or `1910/2bb1` (all three
  are write-capable candidates for a command channel, per the task's
  explicit instruction not to write to unknown characteristics).

## `getStorage()` — status: **not obtained, blocked**

Expected payload shape per README: `totalKB` (int), `freeKB` (int),
`bytesPerSecond` (int), `isFull` (bool) — at minimum ~9-13 bytes if packed as
3×uint32 + 1 byte, likely more with a header/opcode.

None of the read-only characteristics match:

| Char | Value | Length | Storage-shaped? |
|---|---|---|---|
| `d005` | `01000000` | 4 bytes | no — already attributed to `state` (see protocol-state.md) |
| `d001` | `567f0000` | 4 bytes | no — static, matches advertisement bytes, looks like an ID not a KB count |
| `d003` | ASCII `5200000000000000` | 17 bytes | no — this is the serial number |
| `d00a` | `02` | 1 byte | no — too short for any of the 4 storage fields |

No characteristic holds a value large enough to plausibly be `totalKB` for a
flash-based recorder (would expect values in the thousands-to-millions
range, e.g. a few GB of storage = several million KB). `d001`'s value
(`0x00007f56` = 32598 as LE uint32) is in a plausible *order of magnitude*
for a KB-scale number, but this is speculation — it was already established
as a static value that also appears in the advertisement payload, which
argues against it being live storage state (advertisement data is broadcast
before connection, when a storage query couldn't have happened yet, so a
storage number can't need to be in the advertisement — meaning it's more
likely to be a persistent identifier of some kind).

**Conclusion: `getStorage()` cannot be satisfied by a plain read.** Passive
notify listening (45s, `b001`) produced zero spontaneous events, so the
device also does not push storage data unprompted. The only remaining path
is a write to a command characteristic (most likely `dd68/d007` or
`cc68/b002`) to request the value, with the answer arriving via `b001`
notify. **This has not been attempted** — per instructions, discovering that
a write is required is the stopping point, not a green light to guess the
command byte(s).

## `getRecSessionWithUid()` — status: **not obtained, blocked (same reason)**

Expected payload shape: `uid`, `total`, `startPos`, `sessionArray` (list of
`[memoid, dur]` pairs) — inherently variable-length (depends on recording
count), which structurally rules out any of our fixed 1-4-17 byte read
characteristics as the source.

Same evidence as above applies: no static characteristic is shaped like a
list, and passive `b001` notify listening produced nothing. **No recording
count, session ID, memoid, or duration has been observed.** We cannot state
whether the C1 currently holds 0 or N recordings — there is no data to
report either way, and no guess is offered.

## What would unblock this

To go further without violating "don't write to unknown characteristics,"
we would need external ground truth for what to write — e.g.:

1. A legitimate WeChat mini-program session using the real SDK, captured at
   the BLE HCI level (this is exactly what `btmon` is for — **unavailable in
   this sandbox**, see main report: `sudo` is blocked here, so HCI capture
   during a real mini-program interaction would need to happen on hardware
   outside this sandboxed session, e.g. the user's own machine/phone with
   an Android BLE HCI snoop log).
2. Or: explicit user sign-off to attempt a specific, reasoned write (e.g. a
   single opcode byte to `d007` requesting state/storage refresh), evaluated
   case-by-case rather than blanket-authorized — this was not requested and
   is not attempted here.

## `startSyncRecord()` (file download) — status: **cannot be attempted**

Blocked transitively: file download requires knowing a valid `sessionId`
from `getRecSessionWithUid()`, which is itself blocked. Even setting that
aside, `startSyncRecord` is a write-triggered operation by the SDK's own
signature (it takes offsets and a session id, has no meaning as a read),
so it would require the same unauthorized-write step. Not attempted, per
task conditions in section six ("已经明确知道哪个 characteristic 是文件读取通道" is
not satisfied — we only have candidate write channels, not a confirmed
data-return path for one).

---

## Update: USB ground truth now available (this changes the priority, not the blocker)

USB Mass Storage access (see `docs/usb-findings.md`) has independently
answered the *outcome* both these APIs were meant to produce — the
recording list and the recording files themselves — without needing the
BLE protocol at all. **This section is retained because the BLE mechanism
itself is still an open, interesting question** (relevant to a future
wireless-only Android/M5Stack use case), but it is no longer the only path
to the underlying data, and USB now serves as ground truth for judging any
future BLE evidence.

### Ground-truth recording inventory (from USB, for future BLE cross-checking)

11 real recordings, computed directly from file sizes (`.WAV` is
confirmed-standard 16-bit/16kHz/mono PCM, so duration = `(filesize−44) /
32000` exactly):

| Date/time | WAV bytes | AVC bytes | Duration | WAV/AVC ratio |
|---|---|---|---|---|
| 20250307/19_00_32 | 7,397,804 | 1,849,440 | 231.2s | 4.00x |
| 20250307/19_10_21 | 113,785,644 | 28,446,400 | 3555.8s (~59m) | 4.00x |
| 20250312/23_48_00 | 55,724 | 13,920 | 1.7s | 4.00x |
| 20250312/23_52_52 | 5,717,164 | 1,429,280 | 178.7s | 4.00x |
| 20250312/23_59_15 | 154,924 | 38,720 | 4.8s | 4.00x |
| 20250316/19_08_20 | 293,804 | 73,440 | 9.2s | 4.00x |
| 20250520/06_11_50 | 64,684 | 16,160 | 2.0s | 4.00x |
| 20250531/10_33_13 | 3,284,524 | 821,120 | 102.6s | 4.00x |
| 20250924/00_47_45 | 663,724 | 165,920 | 20.7s | 4.00x |
| 20250926/15_06_25 | 1,169,324 | 292,320 | 36.5s | 4.00x |
| 20260203/19_12_34 | 2,065,964 | 516,480 | 64.6s | 4.00x |

**The WAV/AVC ratio is exactly 4.00x for all 11 recordings, no exceptions.**
This is not coincidental. WAV = 32,000 bytes/sec (16-bit × 16kHz × mono).
AVC at exactly 1/4 that = 8,000 bytes/sec = 64 kbps. **Strong hypothesis
(not proven, no byte-level reverse engineering of AVC done)**: `.AVC` is
raw 4-bit ADPCM (a very common, simple embedded speech codec giving exactly
4:1 compression from 16-bit linear PCM) — consistent with `file` reporting
it as headerless `data` (raw ADPCM has no container format) and with the
GUIDE.TXT description of AVC as "raw recording data for transcription
upload" (compressed audio is what you'd upload to save bandwidth). If this
hypothesis is right, `getStorage()`'s documented `bytesPerSecond` field
plausibly refers to this 8,000 bytes/sec *on-flash storage* rate, not the
`.WAV` playback rate — useful for later cross-checking if a real BLE
storage value is ever obtained.

### Chunked file-transfer protocol structure (reasoning only, nothing attempted)

The pieces we have, combined:

- `startSyncRecord(sessionId, fileId, start, end, type)` — `start`/`end`
  must be multiples of **160 bytes**, `end=0` means "to end of file."
- Effective ATT MTU = **89 bytes** (confirmed via btmon) → max single ATT
  notification payload = 86 bytes (MTU−3).
- `cc68/b002` (write) + `cc68/b001` (notify) is the only clean two-member
  write+notify service pair in the whole GATT tree — the strongest
  architectural candidate for a request/response data pipe.

**Inferred structure (Hypothesis, not tested):**

```text
App writes to b002: { opcode=startSyncRecord, sessionId, fileId, start, end, type }
   ↓
C1 begins streaming the requested byte range via b001 notify,
   in chunks ≤86 bytes each (ATT MTU limit) — so a single 160-byte
   "alignment unit" from the SDK's perspective would arrive as roughly
   2 BLE notification packets (e.g. 86+74 bytes, or similar split)
   ↓
App reassembles notify packets back into the requested [start,end) byte range
   ↓
stopSync() (or reaching end) terminates the stream
```

The 160-byte alignment likely reflects an **on-device buffer/flash-page
size** (a common embedded-storage granularity), independent of the BLE MTU
— the SDK's `start`/`end` parameters describe a request in the device's
own terms, and the BLE transport underneath just has to split whatever
160-byte-aligned range is requested into MTU-sized notification packets.
This is a coherent, evidence-consistent theory but **has not been tested
in any way** — no write to `b002` has ever been attempted.

**Recommended next experiment (not yet executed, needs sign-off):** a
single reasoned write to `cc68/b002` — the same one-write-at-a-time,
explicit-authorization pattern already used for the two `d007` probes —
while watching `b001` notify. This is a new, never-touched characteristic,
so per the established pattern in this investigation it is presented here
as a recommendation, not performed.

---

## BREAKTHROUGH (2026-09-18 17:31): `getFreeSize()` confirmed working on the real device

After recovering the real protocol from APK static analysis (see
`docs/apk-protocol-recovery.md`), a full real-device test
(`c1_local/full_device_test.py`) sent the actual `getFreeSize()` opcode
(28) to `1910/2bb1` and received a real, correctly-parsed response on
`1910/2bb0` (indicate), opcode 25 (`STICK_GET_FREE_SIZE_CNF`):

```text
TX (2bb1): 1c00000000000000000000000000000000000000
RX (2bb0, opcode 25): totalKB=15153280  freeKB=14981408  bytesPerSecond=40000  isFull=false
```

`totalKB × 1024 = 15,516,958,720 bytes ≈ 14.45 GiB` — matches the USB
Mass Storage device's reported 14.5 GiB / 15.5 GB capacity (`lsblk`)
almost exactly. **This is the first successful, cross-validated BLE
command/response cycle in the entire investigation.**

**What made it work**: the real app (`StickManager.init4C1Type()`) sends
an `APP_SEND_APP_CAPACITIES_CNF` (opcode 14) "capabilities ack" packet
immediately after subscribing to notifications, *before* any query. This
investigation's first live-protocol attempt skipped that step and got zero
response to anything; after replicating it (using the exact decompiled
`C1ActionCreator.ackInfo(null, null)` packet — `null` token/newToken
because this device has never been logged into any Sogou account),
`getFreeSize()` immediately started working. Full detail in
`docs/experiment-log.md` and `docs/apk-protocol-recovery.md` §8.

**`getSessions()` (opcode 6) still gets zero response**, wire-confirmed
(clean `Write Response`, then total silence on `2bb0` — not a script bug).
Leading hypothesis, not confirmed: the device's internal "pending sync"
session queue is genuinely empty (all 11 real recordings may have already
been marked synced/finished by the official app before the service shut
down), and the firmware simply doesn't send a response when there's
nothing to report, rather than sending an explicit `total=0` confirmation.
