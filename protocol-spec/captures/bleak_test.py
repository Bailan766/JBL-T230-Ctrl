import asyncio
from bleak import BleakScanner

async def scan():
    print("Starting BleakScanner (scanning for 15s)...")
    devices = await BleakScanner.discover(timeout=15.0)
    print(f"Total devices found: {len(devices)}")
    for d in devices:
        print(f"  {d.address} | {d.name}")

if __name__ == "__main__":
    asyncio.run(scan())
