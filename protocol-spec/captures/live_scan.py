import asyncio
from bleak import BleakScanner

async def main():
    print("Starting continuous BLE scan for 20 seconds...")
    def callback(device, advertisement_data):
        name = device.name or advertisement_data.local_name or ""
        mfg = advertisement_data.manufacturer_data
        uuids = advertisement_data.service_uuids
        print(f"[{device.address}] RSSI={advertisement_data.rssi} Name={name!r} Mf={mfg} UUIDs={uuids}")

    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    await asyncio.sleep(20.0)
    await scanner.stop()
    print("Scan finished.")

if __name__ == "__main__":
    asyncio.run(main())
