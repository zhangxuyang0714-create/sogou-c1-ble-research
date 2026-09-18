# Protocol investigation: `getState()`

## Method

Two independent connect-read-disconnect cycles, ~8 minutes apart, both via
`scripts/connect_and_enumerate.py`, read-only:

- Run 1: `captures/gatt_enum_AABBCCDDEEFF_20260918T154806.json`
- Run 2: `captures/gatt_enum_AABBCCDDEEFF_20260918T155403.json`

Passive notify listen (no writes) on `cc68/b001` for 45s:
`captures/notify_0000b001_20260918T155426.jsonl` — 0 events.

## Candidate characteristics (service `dd68`, vendor-specific)

| Char | Run 1 | Run 2 | Stable? |
|---|---|---|---|
| `d005` | `01000000` | `01000000` | yes |
| `d001` | `567f0000` | `567f0000` | yes |
| `d003` | `35323035...` (SN ASCII) | same | yes |
| `d00a` | `02` | `02` | yes |
| `180f/2a19` (Battery) | `0x55` (85%) | `0x48` (72%) | **no — changed** |

## Assessment

- **`d005` = `01000000` is the strongest candidate for `getState()`'s `state`
  field.** Interpreted as a little-endian uint32, value = `1`, which matches
  the SDK README's documented enum value `0x0001` = "开机状态" (powered on).
  The device was sitting idle, connected, not recording — exactly the state
  that value implies. This is a plausible match, not a confirmed one: we
  have not observed the value change to any of the other documented states
  (`0x0002` connected, `0x1003` recording, etc.) because no state transition
  was triggered (no record start/stop attempted, per the read-only rule).
- **`d001` = `567f0000`** stayed constant across both runs and also appears
  verbatim inside the BLE advertisement's manufacturer data payload. This
  looks like a static device/session identifier, not a piece of state — it
  does **not** look like `isUDisk`/`isPrivacy`/`keyState` (those would be
  expected to be small enum/bool values, not a 4-byte constant matching the
  advertisement). No claim made about its meaning beyond "static per-device
  value."
- **`d00a` = `02`** — single byte, stable. Could be `isUDisk`, `isPrivacy`,
  `keyState`, or something unrelated to `getState()` entirely (e.g. a
  protocol/firmware sub-version marker). Not enough evidence to assign.
- **Battery level changing (85%→72% in ~8 min) is unexpected** for a device
  sitting idle and is flagged as an anomaly, not explained. Possible causes:
  BLE radio activity from repeated connect/disconnect cycles during this
  investigation, an inaccurate/noisy fuel gauge, or the value not actually
  being a percentage (though `0x180F`/`0x2A19` is the standard SIG Battery
  Level characteristic, which is defined as 0-100%). Worth re-checking with
  a longer idle baseline before trusting absolute values.
- **No characteristic changed value between the two reads**, so we have
  *not* observed any evidence of a live/dynamic state field beyond battery.
  `getState()`'s multi-field callback (`state, isUDisk, isPrivacy, keyState`)
  cannot be fully reconstructed from passive reads alone — only `d005`
  has a value consistent with one documented field (`state=1`).

## Conclusion

- `getState()` → **partially mapped**. `d005` (read) is a plausible source
  for the `state` field only. `isUDisk` / `isPrivacy` / `keyState` are
  unconfirmed — no characteristic was observed to unambiguously carry a
  boolean/enum matching those semantics.
- No write was required to obtain this partial mapping — `d005` is a plain
  `read` characteristic, consistent with state being cached/always-available
  rather than requiring a query round-trip.
- Confirming the full `getState()` mapping (all 4 fields, and confirming
  `d005` really is "state" and not something else) would require observing
  a state *transition* (e.g. device going from idle to some other
  documented state) — out of scope for Phase 1 since it likely requires
  either a write command or a physical action on the device (e.g. pressing
  record, which is explicitly excluded here).
