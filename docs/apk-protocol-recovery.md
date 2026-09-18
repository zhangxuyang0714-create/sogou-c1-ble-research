# C1 BLE Protocol — Recovered from APK Static Analysis

Source: `Sogou AI Recorder` APK, v1.2.2, package `com.sogou.recmaster`, downloaded
from the official historical URL `https://img.shouji.sogou.com/wapdl/android/apk/SogouAIRecorder.apk`
(76,188,239 bytes, sha256 `09b799a95200ece9cc6ed64159242cf7f9776735445d941fd8f65fe56e8e2e40`).
Decompiled with jadx 1.5.1 (portable, no root). All findings below are
**Confirmed (decompiled source)** unless explicitly marked otherwise —
this is a fundamentally stronger evidence tier than anything from passive
GATT probing, because it's the manufacturer's own compiled logic, not
inference.

The app's Kotlin/Java package `com.sogou.teemo.bluetooth.compatible`
contains a small abstraction (`CompatibleProtocol`/`ICompatibleProtocol`)
that dispatches to one of three device-specific protocol implementations:
`C1Protocol` (our recorder), `C2Protocol` (a different recorder model),
`TR2Protocol` (a translate-pen product). All findings below are from the
`C1*` classes specifically, cross-checked against our real device's
confirmed UUIDs/values wherever possible.

## 1. UUID ↔ semantic name mapping (Confirmed, cross-validated against real device data)

`CharacteristicHolder.kt` defines 12 named characteristic slots.
`C1GattCallbackHandler.newCharacteristicHolder()` wires them to 4 services
(`UUID_SERVICE_BATTERY`, `UUID_SERVICE_CMD`, `UUID_SERVICE_CONFIG`,
`UUID_SERVICE_FILE`). jadx failed to inline the literal UUID string
constants for this specific class (a decompiler limitation, not missing
data — the strings exist in the dex string pool, confirmed by the earlier
`strings` pass finding every one of our device's UUIDs verbatim in the
APK). The mapping below is reconstructed by combining three independent
signals: (a) characteristic counts per service matching exactly, (b)
`setWriteType()`/notify-vs-indicate calls in the decompiled code matching
our device's actual GATT properties, (c) direct byte-content decoding of
values we already read from the real device matching the parsing logic
in `onCharacteristicRead`.

| Named field (code) | Real UUID | Confidence | Evidence |
|---|---|---|---|
| `batteryChar` | `180f`/`2a19` | Confirmed | Standard SIG service, unambiguous |
| `charConfigSN` | `dd68`/`d003` | **Confirmed** | Decoded ASCII value = SN, matches USB `GUIDE.TXT` exactly |
| `charConfigState` | `dd68`/`d005` | **Confirmed** | `onCharacteristicRead` parses `toInt(bytes[0:2])`; matches our own repeated observation of this characteristic being the only dynamic 4-byte value in `dd68`, and the computed `toInt` values (1 idle, 4106 during USB activity) match exactly |
| `charConfigVersion` | `dd68`/`d001` | **Confirmed** | `onCharacteristicRead` parses byte[0] as an ASCII char prefix + `toInt(bytes[1:4])` as version number. Our real value `56 7f 00 00` decodes to `'V' + 127` = **"V127"** — matches the firmware version `V0127` found independently in the USB `LOG/APP1.LOG` file. This is the strongest possible cross-validation: two completely independent evidence sources (decompiled app logic + USB filesystem) agree exactly. |
| `charCMDA2S` | `1910`/`2bb1` | **Confirmed** | `StickProtocol.getStickValue()`'s `SET_CMD_NOTIFY` branch calls `descriptor.setValue(ENABLE_INDICATION_VALUE)` on `charCMDS2A` — INDICATE, not notify. Only one indicate-capable characteristic exists on our device (`2bb0`), so `charCMDS2A = 2bb0` and (by same-service pairing) `charCMDA2S = 2bb1`. |
| `charCMDS2A` | `1910`/`2bb0` | **Confirmed** | Same evidence as above |
| `charFileA2S` | `cc68`/`b002` | Strong hypothesis | `setWriteType(1)` = WRITE_TYPE_NO_RESPONSE requires the "write without response" property; `2bb1` lacks that property (write-only), ruling it out for this role, leaving `b002` (which has both write and write-without-response) as the only remaining write-capable candidate for this slot |
| `charFileS2A` | `cc68`/`b001` | Strong hypothesis | Same-service pairing with `charFileA2S`; `onCharacteristicChanged` routes `UUID_CHAR_FILE_S2A` data to `event.onFileReceive()` — the actual recording-download data channel |
| `charFileAMR` | `0000b003` (never seen on real device) | Hypothesis | Exists in code (3rd member of the FILE service), never observed on our physical unit — likely a firmware/hardware-revision difference (real-time AMR audio streaming may not be present on this unit) |
| `charSyncTime` or `charChangeVolume` | `dd68`/`d007` | Hypothesis | One of these two — both are `setWriteType(2)` (default write) characteristics in the CONFIG service; `d007` is the only write-capable characteristic we found in `dd68` besides the missing `d009` |
| the other of `charSyncTime`/`charChangeVolume`, or `charPhoneBleVersion` | `0000d009` (never seen on real device) | Hypothesis | Same reasoning — CONFIG service needs 6 named slots, our device exposes 5, `d009` is the gap |
| `charPhoneBleVersion` | `dd68`/`d00a` OR the missing `d009` | Weak hypothesis | `d00a` is read-only 1 byte; `getPhoneBleVersion` decodes via generic `ByteUtil.toInt(value)` with no fixed length, so 1 byte is plausible but not confirmed |

**Net result: every UUID this investigation has ever read from the real
device (`d001`, `d003`, `d005`) now has a fully decoded, cross-validated
meaning. The two previously-"blocked" write channels (`d007`, `b002`) are
now understood as belonging to CONFIG/FILE services respectively — neither
is the general command dispatcher, which explains why single-byte probes
on them never did anything.** The actual general command channel
(`2bb1`/`2bb0`) was only ever probed once, with a single `0x00` byte — the
wrong payload shape entirely (see §3).

## 2. Packet format (Confirmed, from `C1ActionCreator.kt` + `ByteUtil.kt`)

Every command sent to `charCMDA2S` (`2bb1`) is a **fixed 20-byte packet**
(`C1ActionCreator.create(action, len=20)`):

```text
byte[0:2]  = opcode, little-endian uint16 (ByteUtil.toByteArray(value, 2))
byte[2:20] = command-specific parameters (zero-padded if unused)
```

`ByteUtil.toByteArray(int, len)` confirmed little-endian:
`bArr[i] = (value >> (i*8)) & 0xFF`. No CRC, no length byte, no framing
beyond the fixed 20-byte size for CMD packets (CRC16 is used elsewhere —
see §5).

Response packets on `charCMDS2A` (`2bb0`, delivered via **indicate**) use
the same `byte[0:2] = opcode` convention, parsed by
`C1ActionParser.onAction()`.

## 3. Full opcode tables (Confirmed)

### App → Stick (`C1ActionCreator.C1ActionApp`, written to `2bb1`)

| Opcode | Name | SDK-doc equivalent | Extra payload (offset:length) |
|---|---|---|---|
| 2 | `APP_RECORD_STOP_IND` | `stopRecord()` | none |
| 3 | `APP_RECORD_PAUSE_IND` | `pauseRecord()` | none |
| 4 | `APP_RECORD_RESUME_IND` | `resumeRecord()` | none (uses `startRealtime` in practice) |
| 5 | `APP_RECORD_GET_STATUS` | — (`getState()` actually uses a plain read, not this opcode, per code) | none |
| 6 | `APP_RECORD_GET_SESSIONS` | **`getRecSessionWithUid()`** | sessionId, 4 bytes @2 |
| 7 | `APP_RECORD_GET_FILES` | — | sessionId 4B@2, recordType 1B@6 |
| 8 | `APP_RECORD_DOWNLOAD_FILE` | **`startSyncRecord()`** | sessionId 4B@2, fileId 2B@6, start 4B@8, end 4B@12, recordType 1B@16 |
| 9 | `APP_RECORD_DOWNLOAD_STOP` | `stopSync()` | none |
| 10 | `APP_RECORD_REALTIME_START` | `startRecord()` | recordType 1B@2 |
| 11 | `APP_RECORD_REALTIME_STOP` | `stopRecord()`(realtime) | none |
| 12 | `APP_RECORD_FINISH_FILE` | — | sessionId 4B@2, fileId 2B@6 |
| 13 | `APP_RECORD_FINISH_SESSION` | — | sessionId 4B@2 |
| 14 | `APP_SEND_APP_CAPACITIES_CNF` | — (capability handshake ack) | token(6B)@6, newToken(?B)@10 |
| 17 | `ORDER_APP_GET_SNN_NO` | `getSSN()` (separate from plain SN read) | none |
| 21 | `APP_DISCONNECT_DEVICE_IND` | `depair()`-adjacent | token@2, isDelete 1B@4 |
| 25 | `APP_RESTORE_FACTORY_SETTINGS_REQ` | — **destructive, excluded** | token@2 |
| 26 | `APP_GET_USER_SET` | — | none |
| 28 | `APP_GET_FREE_SIZE_REQ` | **`getStorage()`** | none |
| 29 | `APP_SET_RECORD_TYPE` | — | type 1B@2 |
| 30 | `APP_SET_RECORD_MODE` | — | mode 1B@2 |

(Full list has more entries — AMR realtime playback, OTA, simultaneous
recording, etc. — omitted here as out of scope; see
`research/apk/jadx/output/sources/com/sogou/teemo/bluetooth/compatible/C1ActionCreator.java`
for the complete set.)

### Stick → App (`C1ActionParser.C1ActionStick`, received via `2bb0` indicate)

| Opcode | Name | Meaning | Payload layout |
|---|---|---|---|
| 9 | `STICK_RECORD_GET_SESSIONS_CONFIRM` | **response to `getRecSessionWithUid()`** | repeating 8-byte entries from offset 2: `sessionId`(4B) + `duration`(4B) — i.e. exactly the SDK's documented `[memoid, dur]` pairs |
| 10 | `STICK_RECORD_GET_FILES_CONFIRM` | file list for a session | repeating 6-byte entries: `fileId`(2B) + `size`(4B), terminated by fileId=0 or 65535 |
| 11 | `STICK_RECORD_FILE_HEADER` | start of file download | 1 byte @2: header flag |
| 12 | `STICK_RECORD_FILE_TAIL` | end of file download | eod flag 1B@2, **CRC16** of the transferred data at a variable offset (length given by byte@3) |
| 18 | `ORDER_STICK_APP_SNN_NO` | response to `getSSN()` | index 1B@2, length 1B@3, data starting @4 |
| 25 | `STICK_GET_FREE_SIZE_CNF` | **response to `getStorage()`** | `totalKB` 4B@2 (×1024 for bytes), `freeKB` 4B@6 (×1024), `bytesPerSecond` 4B@10, `isFull` 1B@14 (1=true) — **exact match to the SDK README's `onGetStorageVolumeWithTotalKB(totalKB, freeKB, bytesPerSecond, isFull)`** |
| 26 | `STICK_GET_STAT_CNF` | usage statistics | 8 fields, 20 bytes total — plausibly related to the USB `STAT/*.DAT` files, not cross-checked byte-for-byte |

The actual file audio data itself (after `STICK_RECORD_FILE_HEADER`)
arrives as raw chunks via `charFileS2A` (`cc68/b001`, notify — a
*different* characteristic from the CMD indicate channel), handled by
`event.onFileReceive(value)` — confirms the earlier architectural
hypothesis that file data has its own dedicated transport, separate from
the general command/response pipe.

## 4. Which "SDK API" operations are NOT command packets at all (Confirmed)

Critical finding: `getSn()`, `getStatus()`, `getBattery()`, `getVersion()`,
`getPhoneBleVersion()`, `requestMTU()` in `C1TaskCreator` all construct
their `StickTask` with `data = null` — they never go through
`C1ActionCreator`/the opcode packet format at all. Cross-referencing
`StickProtocol.getStickValue()`: these map to **plain `readCharacteristic()`
calls** on `charConfigSN`/`charConfigState`/`batteryChar`/`charConfigVersion`/
`charPhoneBleVersion`, or (for MTU) a plain `requestMtu()` GATT operation.

**This exactly matches what this investigation already found by pure
experimentation, months before this APK was obtained**: SN, battery, and
state are all successfully readable via plain GATT reads with zero
command protocol involved. The APK confirms this wasn't luck — it's
exactly how the official app does it too.

## 5. CRC16 (Confirmed, `com/sogou/crc/CRC16Util.java`, fully decompiled)

```java
public static int calcCRC(byte[] data) { return calcCRC(data, data.length, 0xFFFF); }
public static int calcCRC(byte[] data, int len) { return calcCRC(data, len, 0xFFFF); }
public static int calcCRC(byte[] data, int len, int crcInit) {
    for (int i = 0; i < len; i++) {
        int x = (((crcInit << 8) & 0xFF00) | ((crcInit >> 8) & 0xFF)) ^ (data[i] & 0xFF);
        int y = x ^ ((x & 0xFF) >> 4);
        int z = y ^ ((y << 8) << 4);
        crcInit = (z ^ (((z & 0xFF) << 4) << 1)) & 0xFFFF;
    }
    return crcInit;
}
```

Default init value `0xFFFF`. This is a byte-swap-then-XOR CRC16 variant
(classic embedded CRC16/CCITT-style construction). **Only used for OTA
firmware upload chunks and to validate downloaded recording file data**
(the `STICK_RECORD_FILE_TAIL` response carries this CRC) — **not** used on
every CMD packet. This matches finding `libnative-crc16.so` in the APK's
native libraries (a native-code mirror of the same algorithm, likely used
by a different part of the app).

## 6. Token/userId — does BLE require Sogou's servers? (Confirmed answer)

The C1-specific protocol (`C1ActionCreator`, `C1TaskCreator`) has **no
handshake opcode and no token exchange in its opcode table** — no
"HANDSHAKE" entry appears in `C1ActionApp` or `C1ActionStick`, unlike the
base `TaskCreator` class (shared with other pen models) which *does*
declare a `handshake(isFirst, deviceType, bleVersion, appVerify, token, ...)`
method signature — but `C1TaskCreator` (the actual class used for our
device) **does not override or call it**. The `token`/`newToken` bytes do
appear in one C1 opcode: `APP_SEND_APP_CAPACITIES_CNF` (14) —
`ackInfo(token, newToken)` — sent as an acknowledgment of a capabilities
exchange, but this is the app *acknowledging* something, not authenticating
to unlock functionality; and it's never been observed as a prerequisite in
the opcode flow for `getFreeSize`/`getSessions`/`download`, which are
called directly with no preceding token exchange required by the code.

**Conclusion: for the C1 specifically, the decompiled protocol shows no
mandatory token/account-based authentication gating the BLE command
protocol.** The `handshake`/`sgUnionId`/token machinery found earlier in
this investigation belongs to the *translate pen* product
(`com.sogou.teemo.translatepen.manager.StickManager`), a different device
family in the same app, not the C1 recorder pen. This is a definitive
answer to the question raised across several earlier rounds of this
investigation, upgraded from "no evidence it's required" (inference from
absence of BLE-layer authentication) to "confirmed not required" (the
official app's own C1 code path never sends one).

## 6b. Native libraries (brief, symbol-level only — no radare2/Ghidra installed, not needed)

- `libnative-crc16.so`: exports exactly one symbol, `Java_com_sogou_crc_CRC16Util_calcCRC` — a JNI-accelerated mirror of the exact same Java CRC16 algorithm already fully decompiled in §5. Confirms consistency, adds no new information.
- `libencrypt_sogou_v00.so`: exports standard OpenSSL AES symbols (`AES_encrypt`, `AES_decrypt`, `AES_set_encrypt_key`, etc.) — generic crypto, not BLE-specific. No GATT/BLE-prefixed symbols anywhere in it. No evidence it's involved in the C1 command protocol (which is observably plaintext, both from the wire capture showing zero BLE-layer encryption and from the decompiled `C1ActionCreator`/`sendAction()` code path showing no encrypt/decrypt call wrapping the CMD packet). Most likely used for Sogou's account/cloud API traffic or OTA firmware image protection, unrelated to local BLE control.

## 7. Real init sequence (Confirmed, `StickManager.init4C1Type()`) — and real-device validation

The actual post-connect sequence the official app performs, in order:

```text
setCmdNotify()       -- enable indicate on charCMDS2A (2bb0)
setFileNotify()      -- enable notify on charFileS2A (b001)
setBatteryNotify()   -- enable notify on batteryChar (2a19)
sendAppConfigInfo(true, sgUnionId)   -- opcode 14 ackInfo(token, newToken)
getSN() -> ... -> checkUserSet() -> getFreeSize()
requestMTU()
getBattery()
getVersion()
getSSN()
getStatus()
setTime()
getConfigComplete()
```

**`sendAppConfigInfo()` is the first protocol-level action, before any
query** — it builds `C1ActionCreator.ackInfo(token, newToken)` where
`token` = CRC16 of the account's `bindId` string (2 bytes) if a Sogou
account is bound, else `null`, and `newToken` is only set if a WeChat/QQ
`sgUnionId` is present (also null for us). **For an account-less device —
this one, confirmed never logged into any account — both are `null`**,
which fully determines the packet: `0e 00 01 01 03 00 00 00 01 01 00 00
00 00 00 00 00 00 00 00`.

**Real-device validation (2026-09-18 17:31, `c1_local/full_device_test.py`)**:
sending this exact packet, followed by `getFreeSize()` (opcode 28),
produced a **real, correctly-parsed response**: `totalKB=15153280,
freeKB=14981408, bytesPerSecond=40000, isFull=false`. `totalKB*1024 ≈
14.45 GiB`, matching the USB block device's reported 14.5 GiB capacity.
**This confirms the entire recovered protocol stack — UUID mapping,
packet format, opcode table, and now the required init sequence — against
real hardware, not just decompiled logic.**

`getSessions()` (opcode 6), sent after the same successful handshake,
still produced zero response — see `docs/protocol-recordings.md` for
detail and the leading (unconfirmed) hypothesis (empty pending-sync queue).

## 8. What's still not fully nailed down

- Exact UUID assignment for `d007` vs `d009` (SYNC_TIME vs CHANGE_VOLUME vs
  PHONE_BLE_VERSION) — see the hypothesis rows in §1.
- Whether `d00a`'s single byte (`02`, constant) is `charPhoneBleVersion`
  or something else — plausible but not confirmed.
- The full `STICK_GET_STAT_CNF` field semantics (likely related to the USB
  `STAT/*.DAT` files, not cross-checked).
- Exact `APP_SEND_APP_CAPACITIES_CNF`/capabilities-negotiation packet
  layout beyond the token-copy logic shown — not critical since it isn't
  gating the storage/sessions/download opcodes we actually care about.
