# Write / Notification / Indication Observations

## Status: one write observed, wire-confirmed, and it is not part of the vendor protocol

Real HCI/ATT capture now exists: `captures/c1-session.snoop` (btmon, root,
started by the user on this machine, covering 2026-09-18 16:06:38–16:06:42
local time — a passive `connect_and_enumerate.py` run). Full dump analyzed
via `btmon -r`.

**Exactly one `ATT: Write Request` appears in the entire capture:**

```text
API:            none — not SDK-triggered
Characteristic: Client Characteristic Configuration Descriptor (0x2902)
                for Battery Level (0x2a19)
ATT handle:     0x002e (CCCD), value handle 0x002d (Battery Level)
Direction:      host → device (our machine wrote to the C1)
Payload:        01 00  (Notification bit set)
Response handle: n/a (Write Request/Write Response pair, handle 0x002e)
Response payload: empty (ATT Write Response, 0 bytes = success ack)
Confidence:     Confirmed (wire)
Evidence:       captures/c1-session.snoop, packet #147 (TX) / #149 (RX ack)
```

**This write was not issued by our script.** `connect_and_enumerate.py`
never calls `write_gatt_char` or `start_notify`. This is BlueZ's own
well-known behavior: `bluetoothd` automatically enables notifications on
any device's standard Battery Service (`0x180f`/`0x2a19`) so it can expose
live battery level via its internal `org.bluez.Battery1` D-Bus interface,
independent of what any connecting application does. It is OS-level
plumbing for a standard SIG-defined service, not an interaction with the
C1's proprietary protocol.

**No write touched any vendor characteristic.** Checked directly against
the wire capture (not just against our own script's behavior): grepped the
full dump for every `Write Request`/`Write Command` — one result, the
Battery CCCD above. `dd68/d007` (handle `0x001d`), `cc68/b002` (handle
`0x0028`), and `1910/2bb1` (handle `0x000c`) were never written to, at the
ATT wire level, confirmed.

## What this means for the three write-capable candidates

| Characteristic | Handle | What's confirmed | What's still guessed |
|---|---|---|---|
| `dd68/d007` | `0x001d` | Exists, `write`+`write-without-response`, wire-confirmed never written | Everything about its purpose — no payload, no trigger relationship to any SDK API observed |
| `cc68/b002` | `0x0028` | Exists, `write`+`write-without-response`, wire-confirmed never written | Same — no payload observed |
| `1910/2bb1` | `0x000c` | Exists, `write` only, wire-confirmed never written, UUID coincidentally matches a SIG-assigned CTE characteristic name | Whether it's actually used for anything by this device — no traffic, no read value (write-only, can't sanity-check via read) |

**None of these three have been written to, and no payload for any of them
is known.** Any statement about what they do would be a guess, so none is
made here.

## Controlled experiment: `d007` write probe (user-authorized, 2026-09-18 16:13)

User explicitly authorized a single, one-shot write attempt to `dd68/d007`
after reviewing the specific payload and monitoring/abort plan (chat
transcript). Executed via `scripts/probe_d007.py`, with `btmon` capturing
the whole session for independent wire-level verification.

```text
API:            unknown — pure exploratory probe, not tied to any specific SDK call
Characteristic: dd68/d007
ATT handle:     0x001d (declaration), 0x001e (value — write target)
Direction:      host → device
Payload:        00  (1 byte)
Response handle: 0x001e
Response payload: ATT Write Response, 0 bytes (clean ack, no error code)
Confidence:     Confirmed (wire) — verified independently against captures/c1-session.snoop,
                packets #308 (TX Write Request) / #310 (RX Write Response)
Evidence:       captures/probe_d007_20260918T161340.json (script-level),
                cross-checked against the raw btmon dump
```

**Monitoring results (all confirmed, wire + app layer agree):**

- `b001` notify was subscribed before the write. **Zero notifications**
  arrived in a 5-second window after the write — confirmed both by the
  Python notify callback (0 calls) and independently by the raw capture
  (literally zero packets of any kind between the Write Response at
  t=460.883s and the next read at t=465.890s, i.e. total wire silence for
  the full 5s).
- `d005` (state candidate): `01000000` before → `01000000` after. No change.
- Battery: 52% before → 52% after (unchanged; too short an interval to
  read into this given the earlier drain-rate anomaly).
- Follow-up read-only pass (`scripts/check_secondary_effects.py`, no writes)
  checked `d001`, `d003`, `d005`, `d00a`, and battery again ~1 minute later:
  all identical to every previous reading in this whole investigation.
  **No delayed or secondary effect observed anywhere.**
- No indication ever arrived on `2bb0` (never subscribed, and never seen
  unsolicited in the wire capture either).
- Clean disconnect afterward, no anomalies.

**Interpretation (explicitly labeled — not confirmed):**

- The device's firmware accepted a 1-byte write to `d007` without an
  ATT-level protocol error (no "Invalid Attribute Value Length", no
  "Write Not Permitted", no "Application Error" response code) — so `d007`
  does take short writes structurally.
- Whatever `0x00` means to the firmware (if anything), it produced **no
  observable effect** in any readable characteristic or notification within
  the observation window. Possible explanations, none confirmed: (a) `0x00`
  is a reserved/null opcode the firmware silently ignores, (b) the real
  command format needs more bytes (opcode + length + params, e.g. a small
  header) and a bare single zero byte doesn't parse as anything actionable,
  (c) the response to a valid command doesn't arrive via `b001` at all,
  (d) the command did do something with no readable/observable side effect
  in this characteristic set.
- **No further opcodes were tried in that round.** This was authorized and
  executed as a single one-shot experiment per the user's explicit
  instruction.

## Controlled experiment #2: `d007` = 0x01 (user-authorized, 2026-09-18 16:17)

User explicitly authorized a second single write, payload `0x01`, after
reviewing the first result. Executed via `scripts/probe_d007.py 01`
(script extended to also check `d001`/`d00a` before/after, not just
`d005`). `btmon` still capturing.

```text
API:            unknown — pure exploratory probe
Characteristic: dd68/d007
ATT handle:     0x001d (declaration), 0x001e (value — write target)
Direction:      host → device
Payload:        01  (1 byte)
Response handle: 0x001e
Response payload: ATT Write Response, 0 bytes (clean ack, no error)
Confidence:     Confirmed (wire) — captures/c1-session.snoop packets #631 (TX) / #633 (RX)
Evidence:       captures/probe_d007_20260918T161759.json
```

**Result: identical outcome to the `0x00` probe.**

- Baseline: `d005=01000000`, `d001=567f0000`, `d00a=02`, battery=50%
- After (5s later, post-write): all four identical, no change
- `b001` notifications: 0
- No indications, no unexpected traffic in the wire capture between the
  Write Response and the next Read Request (5.007s of silence)

**Two data points now (`0x00` and `0x01`), same result both times: `d007`
structurally accepts single-byte writes with a clean ack, and neither value
produces any observable effect** in state, battery, the two other
previously-unexplained characteristics, or notifications. This is starting
to look less like "wrong opcode, try another" and more like evidence that a
**bare single byte isn't the right shape of command at all** — real
commands may need a longer/structured payload (e.g. a length-prefixed or
multi-field packet), or the response path isn't `b001`. Stated as
interpretation, not fact — still no ground truth. No further opcodes
attempted without fresh authorization.

## Controlled experiment #3: `b002` = 0x00 (user-authorized, 2026-09-18 16:50)

User authorized a single write to `cc68/b002` (not `d007`) after this
investigation's architectural reasoning identified `b002`/`b001` as the
strongest command/response pipe candidate (clean 2-member write+notify
service, vs. `d007` sitting among 4 read-only registers). Same spec as the
two `d007` probes: baseline read → write → 5s notify window → re-read.
Executed via `scripts/probe_b002.py 00`. `btmon` still capturing.

```text
API:            unknown — pure exploratory probe
Characteristic: cc68/b002
ATT handle:     0x0028 (declaration), 0x0029 (value — write target)
Direction:      host → device
Payload:        00  (1 byte)
Response handle: 0x0029
Response payload: ATT Write Response, 0 bytes (clean ack, no error)
Confidence:     Confirmed (wire) — captures/c1-session.snoop packets #2691 (TX) / #2693 (RX)
Evidence:       captures/probe_b002_20260918T165046.json
```

**Result: same null outcome as both `d007` probes.**

- Baseline: `d005=01000000`, `d001=567f0000`, `d00a=02`, battery=63%
- After (5s later): all four identical, no change
- `b001` notifications: 0
- Wire capture confirms total silence for the full 5.006s window between
  the Write Response and the next Read Request — no notify, no indicate,
  nothing

**Three data points now (`d007`=0x00, `d007`=0x01, `b002`=0x00), all three
producing the identical null result: clean ATT ack, zero observable
effect anywhere.** This is consistent across two structurally different
characteristics in two different services, which weakens the "wrong
channel" hypothesis somewhat — if `b002` were the right channel and `0x00`
were simply the wrong opcode, that would look identical to this result, so
this alone doesn't rule `b002` in or out. It does further support that a
**bare single byte is very unlikely to be a complete, well-formed command**
regardless of which write-capable characteristic receives it — any real
command protocol here most likely needs more structure (length field,
multi-byte opcode, or additional parameters) than a lone byte can carry.
No further payloads attempted without fresh authorization.

## Controlled experiments #4-6: exhausting the known write-capable set (user-authorized batch, 2026-09-18 16:54-16:57)

User authorized continuing through the remaining untested write-capable
characteristics as a batch (skip anything already done, stop only for a
fault or a genuinely valuable finding). Two gaps remained:
`1910/2bb1` (never touched) and `b002=0x01` (only `0x00` had been tried).
Used a new generalized script (`scripts/probe_char.py`) that watches
**both** `b001` notify and `2bb0` indicate simultaneously for more
complete coverage than the earlier single-channel probes.

**`2bb1` = 0x00**: clean Write Response, zero change in `d005`/`d001`/`d00a`,
zero events on either monitored channel. Same null result as everything else.

**`b002` = 0x01 (first attempt)**: clean Write Response. `d005` changed
from `01000000` (baseline) to `0a100000` 5 seconds after the write —
looked like a possible real signal, the first time any write had ever
correlated with a `d005` change.

**Reproducibility check**: confirmed `d005` was cold (`01000000`) via a
standalone read, then immediately repeated the exact same `b002=0x01`
write. Result: **`d005` was already `0a100000` in the baseline read taken
at the start of that connection — before the write happened.** This
falsifies the write as the cause: the hot state appeared with no write
at all in between the cold check and this connection.

**Conclusion: the `b002=0x01` → `d005` correlation was coincidental, not
causal.** The most likely explanation (consistent with the original
USB-correlation finding in `docs/gatt-handles.md`): background desktop
processes (`gvfs-udisks2-volume-monitor`, `gvfsd-trash`, confirmed running
via `ps aux`) poll the mounted USB volume periodically and independently
of anything done over BLE, and this is what actually drives `d005`'s
"hot"/"cold" transitions — not any characteristic we've written to. This
doesn't overturn the original USB-activity finding, it reinforces it: the
activity source is broader than "our own deliberate `find`/`cat` commands"
— it includes OS-level background polling we don't directly control.

**Summary across all five write attempts in this investigation**
(`d007`×2, `b002`×2, `2bb1`×1): **none produced an effect attributable to
the write itself** — every apparent signal, once checked, traced back to
independent USB volume-monitor activity rather than the write. This
strengthens (not weakens) the "single bytes aren't a well-formed command"
conclusion from earlier, now across all three known write-capable
characteristics, not just `d007`. No further single-byte sweeping is
planned — the marginal value of trying more bytes on these same three
characteristics is now very low given this consistent pattern.

## Template for future entries (fill in once real SDK-driven traffic exists)

```text
API:
Characteristic:
ATT handle:
Direction:
Payload:
Response handle:
Response payload:
Confidence:
Evidence:
```

## Next step to unblock this document

Still needs one of:
- The original WeChat mini-program / Sogou app connecting to the device
  while `btmon` capture is live (highest-value option — gives real SDK
  command payloads with zero guessing).
- Explicit, per-write user authorization for a specific, reasoned write
  attempt to `d007` or `b002` (not attempted here — no payload has been
  proposed, let alone approved).
