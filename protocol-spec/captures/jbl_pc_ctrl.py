"""JBL Tune 230NC TWS - PC Bluetooth LE Controller & Protocol Prober.

Connects to the earbuds directly from Windows via Bleak, subscribes to
notify characteristic, sends commands and decodes responses in real time.

Service UUID:        65786365-6c70-6f69-6e74-2e636f6d0000
Notify Char UUID:    65786365-6c70-6f69-6e74-2e636f6d0001
Write Char UUID:     65786365-6c70-6f69-6e74-2e636f6d0002
"""

from __future__ import annotations

import asyncio
import sys
from typing import Optional
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "65786365-6c70-6f69-6e74-2e636f6d0000"
NOTIFY_CHAR_UUID = "65786365-6c70-6f69-6e74-2e636f6d0001"
WRITE_CHAR_UUID = "65786365-6c70-6f69-6e74-2e636f6d0002"

def format_hex(data: bytes) -> str:
    return data.hex().upper()

def notification_handler(sender, data: bytearray):
    raw = bytes(data)
    hex_str = format_hex(raw)
    first = raw[0] if raw else 0
    if first == 0xAA and len(raw) >= 2:
        cmd = raw[1]
        print(f"\n[← RX] CMD=0x{cmd:02X} | LEN={len(raw)} | HEX={hex_str}")
        if cmd == 0x00 and len(raw) >= 5:
            print(f"       -> ACK for cmd 0x{raw[3]:02X}, status=0x{raw[4]:02X}")
        elif cmd == 0x12 and len(raw) >= 32:
            name = raw[3:19].decode("utf-8", errors="ignore").strip("\x00 ")
            pid = f"{raw[20]:02X}{raw[19]:02X}"
            fw = f"{raw[29]}.{raw[30]}.{raw[31]}"
            print(f"       -> Device Info: Name='{name}', PID={pid}, FW={fw}")
        elif cmd == 0x25 and len(raw) >= 7:
            left, right, case = raw[3], raw[4], raw[6]
            print(f"       -> Battery: Left={left}%, Right={right}%, Case={case}%")
        elif cmd == 0x91 and len(raw) >= 2:
            print(f"       -> ANC/Noise Response: {hex_str}")
        elif cmd == 0x77:
            print(f"       -> Gesture Table: {hex_str}")
        elif cmd == 0x43:
            total_len = raw[2] if len(raw) > 2 else 0
            chunks = raw[3] if len(raw) > 3 else 0
            print(f"       -> Multi-part EQ Header: total={total_len} bytes, chunks={chunks}")
        elif cmd == 0x04 and len(raw) >= 3 and raw[2] == 0x43:
            print(f"       -> Multi-part EQ Ret Finished (AA 04 43)")
    elif first in range(0x10):
        print(f"\n[← RX] Multi-part chunk #{first}: {hex_str}")
    else:
        print(f"\n[← RX] RAW: {hex_str}")

async def send_command(client: BleakClient, hex_frame: str, desc: str = ""):
    data = bytes.fromhex(hex_frame.replace(" ", ""))
    print(f"[→ TX] {desc} | HEX={format_hex(data)}")
    await client.write_gatt_char(WRITE_CHAR_UUID, data, response=True)
    await asyncio.sleep(0.3)

async def scan_and_connect(target_address: Optional[str] = None):
    address = target_address
    if not address:
        print("Scanning for JBL TUNE230 device on Bluetooth LE (10s)...")
        devices = await BleakScanner.discover(timeout=10.0, return_adv=True)
        for addr, (dev, adv) in devices.items():
            name = dev.name or adv.local_name or ""
            if "230" in name or "JBL" in name.upper() or "TUNE" in name.upper():
                print(f"Found candidate: {addr} | Name: {name!r} | RSSI: {adv.rssi}")
                address = addr
                break

    if not address:
        print("No JBL 230 device found in LE scan.")
        print("Tip: If earbuds are already connected to a phone or in case, please disconnect from phone or put earbuds in pairing mode.")
        return

    print(f"Connecting to {address}...")
    async with BleakClient(address) as client:
        print(f"Connected! MTU: {client.mtu_size}")

        print("Subscribing to notifications on", NOTIFY_CHAR_UUID)
        await client.start_notify(NOTIFY_CHAR_UUID, notification_handler)
        await asyncio.sleep(0.5)

        # 1. Handshake: Device Info
        await send_command(client, "AA1100", "Read Device Info (0x11)")
        # 2. Sync App Status
        await send_command(client, "AA10020000", "Sync App Status (0x10)")
        # 3. Battery Reading
        await send_command(client, "AA250100", "Read Battery (0x25)")
        # 4. Read Noise State
        await send_command(client, "AA910111", "Read Noise Mode (0x91 11)")
        # 5. Read Gestures
        await send_command(client, "AA770201FF", "Read Gestures (0x77)")

        print("\n--- Initial handshake & read finished. Listening for 10 seconds... ---")
        await asyncio.sleep(10.0)

        # 6. Test ANC On
        print("\nTesting: Switch to ANC On...")
        await send_command(client, "AA910710010102000300", "Set ANC On")
        await send_command(client, "AA910111", "Readback Noise Mode")
        await asyncio.sleep(3.0)

        # 7. Test Ambient Aware
        print("\nTesting: Switch to Ambient Aware...")
        await send_command(client, "AA910710010002010300", "Set Ambient Aware")
        await send_command(client, "AA910111", "Readback Noise Mode")
        await asyncio.sleep(3.0)

        # 8. Test EQ Preset Bass (0x03)
        print("\nTesting: Switch EQ Preset to BASS (0x03)...")
        await send_command(client, "AA400103", "Set EQ Preset BASS (0x40 01 03)")
        await send_command(client, "AA210134", "Readback EQ Preset status")
        await asyncio.sleep(3.0)

        print("\nProbing session finished.")

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else None
    asyncio.run(scan_and_connect(target))
