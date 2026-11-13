import asyncio
from winrt.windows.devices.bluetooth.advertisement import (
    BluetoothLEAdvertisementWatcher,
    BluetoothLEScanningMode,
    BluetoothLEAdvertisementWatcherStatus
)

def on_received(watcher, event_args):
    addr = f"{event_args.bluetooth_address:012X}"
    name = event_args.advertisement.local_name
    print(f"Adv received: {addr} | RSSI={event_args.raw_signal_strength_in_dbm} | Name={name}")

def on_stopped(watcher, event_args):
    print(f"Watcher stopped: status={watcher.status}, error={event_args.error}")

watcher = BluetoothLEAdvertisementWatcher()
watcher.scanning_mode = BluetoothLEScanningMode.ACTIVE
watcher.add_received(on_received)
watcher.add_stopped(on_stopped)

print("Starting BluetoothLEAdvertisementWatcher...")
watcher.start()
print("Watcher status:", watcher.status)

import time
time.sleep(10)
watcher.stop()
print("Stopped.")
