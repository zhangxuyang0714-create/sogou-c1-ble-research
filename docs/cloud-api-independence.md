# Can C1 Be Used Without Sogou's Cloud/Servers?

Context: Sogou's recorder service has reportedly shut down — the original
app and WeChat mini-program no longer work. This document exists to answer
one question with evidence, not assumption: **how much of C1 can still be
used, locally, with the manufacturer's backend gone?**

Every claim below is tagged **Confirmed** (directly observed this
investigation), **Hypothesis** (reasoned inference, not proven), or
**Unknown** (no evidence either way). Do not treat a hypothesis as fact.

## 1. What the original software's workflow actually was

From the WeChat mini-program plugin README (the only public documentation
that exists — see §8):

```text
WeChat Mini-Program
   ↓ initSDK(userId, token, type, protocol, callback)
Sogou's closed-source plugin binary (provider wxcaba63e60ef46eab)
   ↓ startScanRecorder() / connectRecorder(deviceId, deviceName, sn)
BLE connection to C1
   ↓ getState() / getStorage() / getRecSessionWithUid() / startSyncRecord()
C1 hardware
```

The plugin binary itself is not in the GitHub repo — it's a closed,
compiled WeChat mini-program plugin, fetched by WeChat at runtime from
Sogou's plugin distribution (`provider: wxcaba63e60ef46eab`). **We have
never seen this binary or its network traffic.** Everything below about
what `initSDK`/`token` actually do is inference from indirect evidence,
not from reading its code.

## 2. What role do `userId` / `token` play? (§6 of the request)

**Confirmed, not inference:** the BLE device itself has been connected,
read from, and written to **repeatedly in this investigation — dozens of
times — without ever supplying any `userId`, `token`, or anything
resembling one.** No pairing, no bonding, no trust, no SMP/encryption
packets at all (checked at the raw HCI wire level, not just via
`bluetoothctl`). Every GATT read succeeded. Both write probes to `d007`
got a clean ATT-level ack (`Write Response`, no error code) — critically,
**no "Insufficient Authentication" or "Insufficient Authorization" ATT
error was ever returned**, which is the standard BLE mechanism a
peripheral would use to reject an unauthenticated client if the
characteristic required it. This is strong evidence that **the BLE GATT
layer itself enforces no token/account-based authentication** for the
operations tried so far.

**What we cannot rule out (Unknown):** whether the *application-layer
protocol* running on top of that open GATT connection — i.e., whatever
command format `d007`/`b002` actually expect — includes its own embedded
credential (e.g., a value derived from `token` that must appear inside a
write payload for privileged commands to execute). Our two write probes
(`0x00`, `0x01`) are too minimal to test this either way: a firmware that
silently ignores malformed/unrecognized commands would look identical
whether or not it also independently requires a credential once it does
recognize a well-formed command.

**Most evidence-supported reading of `userId`/`token` (Hypothesis):**
Given the README's own phrasing — `token`: "申请SDK分配的token" ("a token
issued by applying for the SDK") — this reads as a **developer/platform
credential that authenticates the calling mini-program to Sogou's plugin
distribution and/or backend**, analogous to an API key for using the
plugin at all, not evidence of a device-side BLE authentication scheme.
`userId` is documented as "用于跟录音笔配对,实现自动连接" ("used to pair
with the recorder for auto-reconnect") — this reads as **app-side
bookkeeping** (which recorder belongs to which user, for the *app's* UI/
reconnect convenience and possibly for uploading transcriptions to the
right account), not as something transmitted to or checked by the C1
hardware over BLE.

**Bottom line for §6's core question — "does C1 need Sogou's servers for
local device operations after BLE connection?":** No confirmed evidence
that it does. The BLE link itself is open. Whatever's blocking
`getStorage()`/`getRecSessionWithUid()` from working (see
`docs/protocol-write-observations.md`) has not been shown to be an
authentication requirement — the much simpler and currently unfalsified
explanation is that we simply haven't sent the right *command bytes* yet
(possibly to the wrong characteristic — see §5). There is no observed
handshake, challenge-response, session-key exchange, or device-binding
check anywhere in any capture so far.

## 3. USB: fully confirmed cloud-independent (§3, strongest result of this whole investigation)

USB Mass Storage mode requires **zero** authentication of any kind. It is
a standard SCSI/FAT block device — the OS mounts it exactly like a USB
flash drive. Reading `GUIDE.TXT` on the device itself confirms this is the
**documented, intended, official local-only pathway**: "文件...可导出到
电脑回听" ("files ... can be exported to a computer for playback"). This
was never a workaround — it's a feature the manufacturer built in on
purpose, and it has no dependency on any server, account, or app.

**Confirmed via USB, no cloud involved at any point:**
- Device identity (`Product: Sogou C1`, SN `5200000000000000` — matches
  BLE exactly)
- Firmware version (`V0127`, from `LOG/APP1.LOG` — BLE never exposed this)
- Full recording list (11 files, `RECORD/<date>/<time>.WAV`)
- Full recording download (they're just files — `cp` is enough)
- Historical battery levels and boot events (from the log)

## 4. BLE: partially confirmed cloud-independent

| Capability | Cloud needed? | Evidence |
|---|---|---|
| Discovery + connection | **No** (Confirmed) | Repeated successful connects, zero pairing |
| Read SN, static IDs | **No** (Confirmed) | `d003`, `d001` read directly |
| Read battery | **No** (Confirmed) | Standard SIG Battery Service, plain read |
| Read live state (`d005`) | **No** (Confirmed) | Plain read, and it does change with real device activity (USB I/O) — proven live, proven local |
| Storage / recording list / file download over BLE | **Unknown** | Not yet triggered by anything tried; not shown to require cloud, not shown not to |

## 5. Is the GitHub repo real protocol, or just a surface? (§5's core question)

**It is only the upper-layer API surface — confirmed, not inferred.** The
README documents JS function signatures and callback shapes. It contains:
- Zero GATT service/characteristic UUIDs
- Zero byte-level command formats or opcodes
- Zero mention of MTU, encryption, or connection parameters
- One (and only one) piece of genuinely low-level information: that
  `startSyncRecord`'s `start`/`end` byte offsets must be multiples of 160
  — a real protocol-shaped constraint, but still not the actual bytes on
  the wire.

Everything below the JS API — which characteristic actually gets written,
what the command byte layout is, how a response is framed — lives only
inside the closed plugin binary, which we do not have. So: **the repo
tells us *what* the original software could do, not *how* it did it over
BLE.** This investigation's own `btmon`/GATT work is the only source of
real protocol-level ground truth that exists, and it currently covers
discovery/connection/reads/two single-byte write probes — not the actual
command protocol.

## 6. Which BLE characteristic is most likely which role (architectural reasoning, no new writes performed)

Not tested — reasoning only, from the confirmed GATT structure
(`docs/gatt-handles.md`):

- **`cc68` service (`b002` write + `b001` notify) is architecturally the
  strongest command/response pipe candidate.** It's a clean two-member
  service: one write-only characteristic paired with one notify-only
  characteristic, the textbook shape of a BLE "TX/RX" command channel.
  **This has never been tested** — all write probing so far targeted
  `d007`, not `b002`.
- **`dd68/d007`** (already probed with `0x00`/`0x01`, no effect) sits
  among four read-only "info register" characteristics (`d001`, `d003`,
  `d005`, `d00a`) in the same service, which structurally looks more like
  a simple control/config toggle than a full command dispatcher — this
  may explain why single bytes produced nothing.
- **`1910` service (`2bb1` write + `2bb0` indicate)** — indicate (not
  notify) is often used for guaranteed-delivery, one-time or critical
  exchanges (e.g. handshake/setup), which would fit a "session
  init"/pairing-like step if one exists. Never tested. Also worth
  remembering: `2bb0`/`2bb1`'s UUIDs coincidentally match SIG-assigned
  "Constant Tone Extension" characteristics — almost certainly unrelated,
  just 16-bit UUID space reuse, but not proven.

**Recommendation, not yet executed:** the single highest-value next BLE
experiment is a reasoned write to `cc68/b002` (not `d007`) while watching
`b001` notify — but this is a *new* characteristic never written before,
so per the established pattern in this investigation it should get
explicit sign-off before being attempted, same as the `d007` probes did.

## 7. Confirmed vs. not, summarized

**Confirmed to work without any Sogou involvement whatsoever:**
- USB: connect, identify, read all device info, list all recordings,
  download all recordings, read firmware version and historical logs.
- BLE: connect, read SN/battery/one live state field.

**Not yet achieved, but no evidence it requires the cloud either — just
unresolved protocol:**
- BLE storage query, BLE recording list, BLE file download.

**Not needed at all, since USB already solves it:**
- The BLE recording-list/download problem is not actually blocking the
  stated goal ("recover the ability to list and download recordings
  without Sogou") — USB already does this today, fully, with zero
  reverse engineering.

## 8. What a future Android APK will need to build itself

Since the GitHub repo is only an API surface with no protocol
implementation (§5), an Android app cannot "wrap" the official plugin —
that plugin is gone/inaccessible anyway (WeChat-only, tied to a dead
backend). Any future app has to implement its own protocol layer:

```text
Android 16 APK
      ↓
Android BLE (already-open GATT, confirmed) / Android USB Host (mass storage, confirmed)
      ↓
Our own C1 protocol layer  ← does not exist yet, must be built from scratch
      ↓
C1
```

What's already known enough to build directly:
- USB path: standard Android USB Host API + mass-storage/FAT handling (or
  simpler: tell the user to plug into a PC — Android's own native
  mass-storage host support requires either root or a MSC/SCSI library,
  since Android doesn't auto-mount arbitrary USB drives the way desktop
  Linux does; this is a real implementation detail to plan for, not
  solved by this investigation).
- BLE path: connect + read SN/battery/`d005` directly, using the exact
  UUIDs documented in `docs/gatt-handles.md`.

What's still missing and needs more investigation before an app can rely
on it:
- The actual command protocol for BLE storage/recording-list/download
  (§6's recommended next experiment).
- Whether `d005`'s other bits map to `isUDisk`/`isPrivacy`/`keyState`
  cleanly (useful but not blocking, since USB already proves file access).

## 9. Answering §6's headline question directly

> C1 在 BLE 连接之后，是否真的需要搜狗服务器才能进行本地设备操作？

**No evidence that it does, for anything tried so far** (connect, read
SN/battery/state). **Unproven either way** for the BLE operations not yet
reached (storage, recording list, file download over BLE) — but this is
moot for the project's actual goal, since USB already delivers those
capabilities today with zero cloud involvement, zero reverse engineering,
and zero risk.
