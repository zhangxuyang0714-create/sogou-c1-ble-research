#!/usr/bin/env python3
"""Low-confidence, single-shot probe: does writing to b002 (charFileA2S,
real code role = OTA upload channel, not a documented getFiles trigger)
change getFiles' "no file" response for a known-real session? No code
evidence supports this; single 0x00 byte, one attempt only."""
import asyncio
from bleak import BleakClient

ADDRESS = "AA:BB:CC:DD:EE:FF"
UUID_CMD_A2S = "00002bb1-0000-1000-8000-00805f9b34fb"
UUID_CMD_S2A = "00002bb0-0000-1000-8000-00805f9b34fb"
UUID_FILE_A2S = "0000b002-0000-1000-8000-00805f9b34fb"
UUID_FILE_S2A = "0000b001-0000-1000-8000-00805f9b34fb"
KNOWN_SESSION = 0x67d1acb0  # 1.74s, confirmed real via getSessions


def le_bytes(v, n): return bytes([(v >> (8 * i)) & 0xFF for i in range(n)])
def le_int(d): return sum((b & 0xFF) << (8 * i) for i, b in enumerate(d))
def build(op, params=b"", n=20):
    buf = bytearray(n); buf[0:2] = le_bytes(op, 2); buf[2:2+len(params)] = params
    return bytes(buf)


async def main():
    q = asyncio.Queue()
    async with BleakClient(ADDRESS) as client:
        print("connected:", client.is_connected)
        client_add = lambda s, d: (print(f"RX indicate: {bytes(d).hex()}"), q.put_nowait(bytes(d)))
        await client.start_notify(UUID_CMD_S2A, client_add)
        await client.start_notify(UUID_FILE_S2A, lambda s, d: print(f"RX file-notify len={len(d)} hex={bytes(d).hex()[:60]}"))

        pkt = bytearray(20); pkt[0:2] = bytes([14, 0]); pkt[2], pkt[3], pkt[4], pkt[5], pkt[8], pkt[9] = 1, 1, 3, 0, 1, 1
        await client.write_gatt_char(UUID_CMD_A2S, bytes(pkt), response=True)
        print("handshake sent")
        await asyncio.sleep(2)
        while not q.empty(): q.get_nowait()

        print("=== writing 0x00 to b002 ===")
        try:
            await client.write_gatt_char(UUID_FILE_A2S, bytes([0x00]), response=True)
            print("b002 write: ack, no error")
        except Exception as e:
            print("b002 write FAILED:", e)
        await asyncio.sleep(2)
        while not q.empty(): q.get_nowait()

        print(f"=== retry getFiles on known session {hex(KNOWN_SESSION)} ===")
        await client.write_gatt_char(UUID_CMD_A2S, build(7, le_bytes(KNOWN_SESSION, 4) + le_bytes(1, 1)), response=True)
        try:
            data = await asyncio.wait_for(q.get(), timeout=8)
            print("getFiles response:", data.hex())
        except asyncio.TimeoutError:
            print("getFiles: TIMEOUT")

        await client.stop_notify(UUID_CMD_S2A)
        await client.stop_notify(UUID_FILE_S2A)

asyncio.run(main())
