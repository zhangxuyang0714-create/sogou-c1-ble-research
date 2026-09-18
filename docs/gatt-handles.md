# GATT UUID ↔ ATT Handle Mapping

## Data source (updated)

This version is built from a **real HCI/ATT capture**:
`captures/c1-session.snoop` (btmon, started by the user with root on this
machine at `captures/c1-session.snoop`),
covering a read-only reconnect at 2026-09-18 16:06:38–16:06:42 (local),
dumped to text via `btmon -r c1-session.snoop`. Every fact marked
**Confirmed (wire)** below comes directly from decoded ATT PDUs in that
capture. The previous version of this file (BlueZ D-Bus derived only) is
superseded — the wire capture agrees with it exactly on every UUID/handle,
so nothing from that pass turned out to be wrong, but this version has
strictly stronger evidence.

## Session facts (wire-confirmed)

- **LE Create Connection** at t=37.497s → **LE Enhanced Connection Complete**
  at t=37.546s. Peer address `AA:BB:CC:DD:EE:FF` (Random/Static). Connection
  interval 15.00 ms, supervision timeout 4000 ms.
- **ATT Exchange MTU**: our host requested 517, the device requested 89 and
  responded with server RX MTU 89. **Effective negotiated MTU = 89 bytes**
  (min of both sides) → max single ATT payload = 86 bytes (MTU−3). This is
  new information not visible through Bleak's abstraction, and is directly
  relevant to `startSyncRecord`'s documented 160-byte alignment: a 160-byte
  unit cannot fit in one ATT notification at this MTU, so any future file
  transfer will span multiple notify packets per 160-byte chunk.
- **Zero SMP/pairing/encryption packets** anywhere in the capture (checked
  by grepping the full dump for SMP/Pairing/Encrypt/LTK — only match is the
  device advertising "LE Encryption" as a *supported feature*, never used).
  Confirms at the wire level — not just via `bluetoothctl info` — that this
  was a fully unauthenticated, unencrypted GATT session.
- **Exactly one Write Request in the entire capture** (see
  `protocol-write-observations.md` for detail) — a CCCD write enabling
  Battery Level notify, issued automatically by BlueZ, not by our script.
- Session ends with a clean `Disconnect` (`Remote User Terminated
  Connection` reason on the wire, i.e. our side / the `async with` context
  manager closed it) at t=41.16s. Total connected duration ~3.6s.

## Primary service discovery (Read By Group Type Response, wire-confirmed)

| Handle range | UUID |
|---|---|
| `0x0001`-`0x0009` | Generic Access Profile (`0x1800`) — **not previously seen**, BlueZ's D-Bus GATT API hides this from Bleak entirely |
| `0x000a`-`0x000a` | Generic Attribute Profile (`0x1801`) |
| `0x000b`-`0x0012` | Unknown (`0x1910`, vendor) |
| `0x0013`-`0x0022` | Unknown (`0xdd68`, vendor) |
| `0x0023`-`0x002a` | Unknown (`0xcc68`, vendor) |
| `0x002b`-`0xffff` | Battery Service (`0x180f`) |

## Full characteristic table (Read By Type Response, wire-confirmed)

| Decl. handle | Value handle | UUID | Properties | Value (source) |
|---|---|---|---|---|
| `0x0002` | `0x0003` | Device Name (`0x2a00`) | Read, Write | len=17 confirmed on wire (Read Response); content not directly read by us — 17 bytes matches the UTF-8 length of `搜狗AI录音笔` exactly, so **very likely** that string, but not independently confirmed byte-for-byte (BlueZ hides this service from the app-layer API we have, so we could not cross-check with a decoded read) |
| `0x0004` | `0x0005` | Appearance (`0x2a01`) | Read | len=2 confirmed on wire; value not decoded |
| `0x0006` | `0x0007` | Peripheral Preferred Connection Parameters (`0x2a04`) | Read | not read this round |
| `0x0008` | `0x0009` | Central Address Resolution (`0x2aa6`) | Read | not read this round |
| `0x000c` | `0x000d` | `0x2bb1` (vendor, coincidental SIG-name collision) | Write | never read (write-only) or written |
| `0x000e` | — | descriptor: Characteristic User Description (`0x2901`) | — | not read |
| `0x000f` | `0x0010` | `0x2bb0` (vendor, coincidental SIG-name collision) | Indicate | never subscribed |
| `0x0011` | — | descriptor: CCCD (`0x2902`) | — | not written |
| `0x0012` | — | descriptor: Characteristic User Description (`0x2901`) | — | not read |
| `0x0014` | `0x0015` | `0xd001` (vendor) | Read | `567f0000` (Confirmed — app-layer read, len=4 matches wire Read Response len=4) |
| `0x0016` | — | descriptor: Characteristic User Description (`0x2901`) | — | not read |
| `0x0017` | `0x0018` | `0xd003` (vendor) | Read | `5200000000000000` ASCII (Confirmed — app-layer read, len=16 matches wire Read Response len=16) |
| `0x0019` | — | descriptor: Characteristic User Description (`0x2901`) | — | not read |
| `0x001a` | `0x001b` | `0xd005` (vendor) | Read | `01000000` (Confirmed — app-layer read, len=4 matches wire Read Response len=4) |
| `0x001c` | — | descriptor: Characteristic User Description (`0x2901`) | — | not read |
| `0x001d` | `0x001e` | `0xd007` (vendor) | Write, Write Without Response | **never read (no read property), never written** |
| `0x001f` | — | descriptor: Characteristic User Description (`0x2901`) | — | not read |
| `0x0020` | `0x0021` | `0xd00a` (vendor) | Read | `02` (Confirmed — app-layer read, len=1 matches wire Read Response len=1) |
| `0x0022` | — | descriptor: Characteristic User Description (`0x2901`) | — | not read |
| `0x0024` | `0x0025` | `0xb001` (vendor) | Notify | not subscribed this round |
| `0x0026` | — | descriptor: CCCD (`0x2902`) | — | not written |
| `0x0027` | — | descriptor: Characteristic User Description (`0x2901`) | — | not read |
| `0x0028` | `0x0029` | `0xb002` (vendor) | Write, Write Without Response | **never read (no read property), never written** |
| `0x002a` | — | descriptor: Characteristic User Description (`0x2901`) | — | not read |
| `0x002c` | `0x002d` | Battery Level (`0x2a19`) | Read, Notify | `0x39` = 57% at this session (Confirmed — app-layer read, len=1 matches wire) |
| `0x002e` | — | descriptor: CCCD (`0x2902`) | — | **written** `0100` (enable notify) — BlueZ automatic, see write-observations doc |

## What changed vs. the previous (D-Bus-only) version

- Confirmed the previously-built handle numbers are **exactly correct** —
  wire capture agrees on every UUID and handle.
- **New**: discovered the GAP service (`0x1800`) and its 4 standard
  characteristics, invisible to Bleak/BlueZ's D-Bus GATT API.
- **New**: effective ATT MTU = 89 bytes, relevant to future file-transfer
  chunking analysis.
- **New**: wire-level confirmation of zero pairing/encryption for the whole
  session (stronger than the `bluetoothctl info` check alone).
- **New**: descriptor handles for every characteristic (mostly
  Characteristic User Description `0x2901`, plus CCCD `0x2902` on all three
  notify/indicate characteristics — `0x2bb0` at `0x0011`, `0xb001` at
  `0x0026`, Battery Level at `0x002e`. None of these three CCCDs were
  written by us; only Battery Level's was written, and that was BlueZ
  automatically (see write-observations doc).

## Full capability tree (compiled from all prior work, no new experiments)

```text
Service 0x1800 — Generic Access Profile (standard, hidden from BlueZ D-Bus API)
 ├── 0x2a00 Device Name          [Read, Write]
 │     readable: yes (never actually read by us — BlueZ hides this service
 │                     from the app-layer API; only seen via raw HCI Read
 │                     Request/Response, length=17 confirmed, content not
 │                     decoded — 17 bytes matches UTF-8 "搜狗AI录音笔" exactly)
 │     writable: yes (never written — no reason to; would rename the device)
 │     notify/indicate: no
 │     inferred function: standard BLE device name, cosmetic only
 ├── 0x2a01 Appearance            [Read]
 │     readable: yes (length=2 confirmed on wire, value not decoded)
 │     inferred function: standard BLE appearance icon code, cosmetic only
 ├── 0x2a04 Peripheral Preferred Connection Parameters  [Read]
 │     not read at all — standard connection-tuning hint, low value
 └── 0x2aa6 Central Address Resolution  [Read]
       not read at all — standard BLE privacy-feature flag, low value

Service 0x1801 — Generic Attribute Profile (standard)
  no characteristics enumerated (typically just a "Service Changed" 0x2a05
  indicate characteristic on many devices — not observed here at all,
  meaning either it's absent or wasn't captured; low priority)

Service 0x1910 — vendor-specific
 ├── 0x2bb1  [Write]                    (handle 0x000c/0x000d)
 │     readable: no (write-only, cannot sanity-check via read)
 │     writable: yes — NEVER WRITTEN, no data at all
 │     notify/indicate: no
 │     inferred function: unknown. UUID coincidentally equals a SIG-assigned
 │       "Advertising CTE Interval" characteristic — almost certainly
 │       unrelated (16-bit UUID space reuse), not confirmed either way
 └── 0x2bb0  [Indicate]                 (handle 0x000f/0x0010, CCCD 0x0011)
       readable: no (indicate-only)
       subscribed: yes, passively, 30s — ZERO indications received
       inferred function: unknown, paired with 0x2bb1 by service grouping —
         could be a handshake/setup acknowledgment channel (indicate is
         often used for guaranteed one-shot exchanges), never triggered

Service 0xdd68 — vendor-specific ("info/state" service)
 ├── 0xd001  [Read]                     (handle 0x0014/0x0015)
 │     current value: 567f0000, constant across every read all investigation
 │     also appears verbatim in the BLE advertisement's manufacturer data
 │     inferred function: some kind of static device/session identifier —
 │       not a KB-scale storage number (ruled out: static value can't be a
 │       live storage figure, and it's already broadcast pre-connection)
 ├── 0xd003  [Read]                     (handle 0x0017/0x0018)
 │     current value: ASCII "5200000000000000" — CONFIRMED = serial number
 │       (matches USB GUIDE.TXT exactly — cross-validated via ground truth)
 │     inferred function: serial number readout
 ├── 0xd005  [Read]                     (handle 0x001a/0x001b)
 │     current value: 01000000 at rest, 0a100000 during active USB flash I/O
 │       (CONFIRMED dynamic — see USB-correlation section below)
 │     inferred function: live device/activity state register — strongest
 │       candidate for getState()'s state field, see analysis below
 ├── 0xd007  [Write, Write Without Response]  (handle 0x001d/0x001e)
 │     writable: yes — tested with 0x00 and 0x01, both cleanly ack'd,
 │       zero observable effect on any characteristic or notify channel
 │     inferred function: unknown — sits among read-only "info registers"
 │       in this service, structurally looks more like a simple
 │       toggle/config write than a full command dispatcher (see cc68/b002
 │       for the stronger command-channel candidate)
 └── 0xd00a  [Read]                     (handle 0x0020/0x0021)
       current value: 02, constant across every read all investigation
       inferred function: unknown single-byte value — too short to be any
         of getStorage()'s fields, could be isUDisk/isPrivacy/keyState or
         something unrelated entirely; no evidence either way

Service 0xcc68 — vendor-specific ("data channel" service)
 ├── 0xb001  [Notify]                   (handle 0x0024/0x0025, CCCD 0x0026)
 │     subscribed: yes, twice (45s and again during d007 probes) —
 │       ZERO notifications ever received, including during both write probes
 │     inferred function: strongest architectural candidate for a
 │       command-RESPONSE channel (paired 1:1 with b002 as the only two
 │       members of this service — classic BLE TX/RX pipe shape)
 └── 0xb002  [Write, Write Without Response]  (handle 0x0028/0x0029)
       writable: yes — NEVER WRITTEN, no data at all
       inferred function: strongest architectural candidate for a
         command-REQUEST channel (see reasoning above) — this is the
         top-recommended next experiment, not yet attempted

Service 0x180f — Battery Service (standard)
 └── 0x2a19 Battery Level  [Read, Notify]  (handle 0x002c/0x002d, CCCD 0x002e)
       readable: yes — confirmed working, standard 0-100% encoding
       notify: BlueZ auto-subscribes to this on every connection
         (org.bluez.Battery1 internal behavior, not app-driven)
       observed values across the investigation: 85%→72%→60%→57%→52%→50%→33%
         over roughly an hour of intermittent connections — steady decline,
         cause still unconfirmed (connection overhead vs. real drain vs.
         noisy gauge)
```

## D005 ↔ USB correlation detail (new this round, reusing prior data — no new BLE reconnects needed for two of the three states below)

Three of four possible USB states have data (the fourth, "USB physically
unplugged again," would need the user to unplug once — not done, see main
report):

| USB state | `d005` value | Data source |
|---|---|---|
| USB never connected (whole rest of the investigation, dozens of reads) | `01000000` | Reused — every read before this session's USB testing |
| USB connected, mounted, but idle (no recent filesystem access) | `01000000` | Reused — `watch_d005.py`'s later reads, `correlate_usb_d005.py`'s cooldown reads, both taken while USB stayed connected |
| USB connected, actively being read (`find`/`ls`/`cat` in progress or just finished) | `0a100000` | New this session — `correlate_usb_d005.py` |
| USB physically unplugged after having been connected | not tested | Would need the user to physically unplug once |

**This is an important nuance for the `isUDisk` hypothesis**: if `d005`
encoded a simple "is USB currently connected" boolean, the idle-but-connected
row should differ from the never-connected row — **it doesn't; both are
`01000000`.** Only active filesystem I/O produces the different value. This
means the changing bits more likely represent a **flash/storage activity
("busy") flag** shared between the BLE-exposed state and whatever the MCU's
internal flash controller is doing (USB Mass Storage reads hit the same
physical flash chip), rather than a clean `isUDisk` mode indicator.
Confirming true `isUDisk` semantics would need the fourth data point
(physical unplug) to see if a *third* distinct value appears for
"USB electrically connected, enumerated, but not currently transferring" —
which is different from "not connected at all" only if the MCU can tell the
two apart, which we can't determine without that test.
