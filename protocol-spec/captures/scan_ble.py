import asyncio
from bleak import BleakScanner

async def main():
    print("Scanning for Bluetooth LE devices (5s)...")
    devices = await BleakScanner.discover(timeout=5.0, return_adv=True)
    for address, (device, adv) in devices.items():
        name = device.name or adv.local_name or "<Unknown>"
        if "JBL" in name.upper() or "TUNE" in name.upper() or "230" in name:
            print(f"*** FOUND TARGET: {address} | {name} | RSSI: {adv.rssi} | Services: {adv.service_uuids} | MfData: {adv.manufacturer_data}")
        else:
            print(f"    {address} | {name} | RSSI: {adv.rssi}")

if __name__ == "__main__":
    asyncio.run(main())
