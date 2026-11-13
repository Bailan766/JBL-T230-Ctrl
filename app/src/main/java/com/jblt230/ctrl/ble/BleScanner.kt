package com.jblt230.ctrl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import com.jblt230.ctrl.protocol.ScanConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Finds the LE control side of a classically-paired JBL earbud set. */
@SuppressLint("MissingPermission")
class BleScanner(private val config: ScanConfig) {

    /** Looks through bonded devices by name / address. This is the preferred path. */
    fun findClassicDevice(adapter: BluetoothAdapter?, preferredMac: String?): BluetoothDevice? {
        val bonded = adapter?.bondedDevices ?: return null
        return bonded.firstOrNull { it.address.equals(preferredMac, ignoreCase = true) }
            ?: bonded.firstOrNull {
                (it.name ?: "").uppercase(Locale.ROOT).let { n ->
                    n.startsWith("JBL TUNE230") || n.contains("TUNE230")
                }
            }
            ?: bonded.firstOrNull { (it.name ?: "").contains("JBL", ignoreCase = true) }
    }

    /**
     * Falls back to an LE scan when the control service does not answer on the
     * classic address. Filters on Harman manufacturer id 0x0ECB / FDDF and the
     * "-LE" name suffix. Never keeps what it saw.
     */
    suspend fun scanForLeSide(adapter: BluetoothAdapter?): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val deferred = CompletableDeferred<BluetoothDevice?>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!deferred.isCompleted && isCandidate(result)) {
                    deferred.complete(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "LE scan failed: $errorCode")
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            scanner.startScan(null, settings, callback)
            withTimeoutOrNull(config.scanTimeoutMs) { deferred.await() }
        } catch (e: Throwable) {
            Log.w(TAG, "LE scan error: ${e.message}", e)
            null
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (ignored: Throwable) {
                // Ignore any error on stopping scan
            }
        }
    }

    private fun isCandidate(result: ScanResult): Boolean {
        val name = result.scanRecord?.deviceName ?: ""
        if (name.endsWith(config.nameSuffix, ignoreCase = true)) return true
        val record = result.scanRecord ?: return false
        val manufacturer = record.manufacturerSpecificData
        for (i in 0 until manufacturer.size()) {
            if (manufacturer.keyAt(i) == config.manufacturerId) return true
        }
        val serviceData = record.serviceData
        return serviceData.keys.any { it.toString().contains(config.serviceDataUuid, ignoreCase = true) }
    }

    companion object {
        private const val TAG = "JblT230Ctrl/Scan"
    }
}
