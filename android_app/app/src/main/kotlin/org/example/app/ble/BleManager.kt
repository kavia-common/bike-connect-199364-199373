package org.example.app.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log

/**
 * PUBLIC_INTERFACE
 *
 * Thin wrapper around Android BLE scanning.
 *
 * Key design goals:
 * - Make scanning failures visible via logs (adapter null/off, scanner null, callback errors).
 * - Provide an explicit "no filter" scan mode to debug discovery issues.
 * - Avoid duplicate device spam by tracking seen addresses.
 */
class BleManager(private val context: Context) {

    interface Listener {
        fun onScanStateChanged(isScanning: Boolean, statusMessage: String)
        fun onDeviceFound(deviceLine: String)
        fun onScanError(errorMessage: String)
    }

    private val tag = "BleManager"

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private val seenAddresses: MutableSet<String> = LinkedHashSet()

    private fun hasScanPermission(): Boolean {
        // BLUETOOTH_SCAN is runtime permission on Android 12+ (API 31+)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT is runtime permission on Android 12+ (API 31+)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun safeGetDeviceAddress(device: android.bluetooth.BluetoothDevice?): String {
        // Some BluetoothDevice accessors are guarded by BLUETOOTH_CONNECT on Android 12+.
        return if (device == null) {
            "unknown"
        } else if (hasConnectPermission()) {
            device.address ?: "unknown"
        } else {
            "unknown"
        }
    }

    private fun safeGetDeviceName(device: android.bluetooth.BluetoothDevice?, result: ScanResult): String {
        // Prefer scanRecord name when BLUETOOTH_CONNECT is not granted.
        val scanRecordName = result.scanRecord?.deviceName
        return if (device == null) {
            scanRecordName ?: "(no name)"
        } else if (hasConnectPermission()) {
            device.name ?: scanRecordName ?: "(no name)"
        } else {
            scanRecordName ?: "(no name)"
        }
    }

    // PUBLIC_INTERFACE
    fun isBluetoothAvailable(): Boolean {
        /** Returns true if the device has a Bluetooth adapter. */
        return adapter != null
    }

    // PUBLIC_INTERFACE
    fun isBluetoothEnabled(): Boolean {
        /** Returns true if Bluetooth is enabled. */
        return adapter?.isEnabled == true
    }

    // PUBLIC_INTERFACE
    fun startScanNoFilter(listener: Listener) {
        /**
         * Starts an unfiltered BLE scan.
         *
         * This is the most reliable mode for debugging “no devices found” issues because:
         * - It does not require any advertised service UUID match.
         * - It relies only on system BLE discovery.
         */
        if (adapter == null) {
            val msg = "Bluetooth adapter is null (device likely does not support Bluetooth)."
            Log.e(tag, msg)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
            return
        }

        if (!adapter.isEnabled) {
            val msg = "Bluetooth is OFF. Enable Bluetooth to scan."
            Log.w(tag, msg)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
            return
        }

        if (!hasScanPermission()) {
            val msg = "Missing BLUETOOTH_SCAN permission (runtime). Cannot start scan."
            Log.e(tag, msg)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            val msg = "BluetoothLeScanner is null (BLE may be unsupported or Bluetooth not ready)."
            Log.e(tag, msg)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
            return
        }

        // Stop any prior scan cleanly.
        stopScanInternal()

        seenAddresses.clear()

        val settings = ScanSettings.Builder()
            // LOW_LATENCY improves chance of seeing devices during short debug scans.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters: List<ScanFilter> = emptyList()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = safeGetDeviceAddress(device)
                if (!seenAddresses.add(address)) return

                val name = safeGetDeviceName(device, result)
                val rssi = result.rssi
                val line = "$name  •  $address  •  RSSI $rssi"

                Log.i(tag, "Found device: $line")
                listener.onDeviceFound(line)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.i(tag, "Batch results size=${results.size}")
                for (r in results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val msg = "Scan failed. errorCode=$errorCode (API=${Build.VERSION.SDK_INT})"
                Log.e(tag, msg)
                listener.onScanError(msg)
                listener.onScanStateChanged(false, msg)
            }
        }

        try {
            Log.i(tag, "Starting BLE scan (no filter). API=${Build.VERSION.SDK_INT}")
            scanner?.startScan(filters, settings, scanCallback)
            listener.onScanStateChanged(true, "Scanning… (no filter)")
        } catch (se: SecurityException) {
            val msg = "SecurityException starting scan. Missing runtime permission? ${se.message}"
            Log.e(tag, msg, se)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
        } catch (t: Throwable) {
            val msg = "Unexpected error starting scan: ${t.message}"
            Log.e(tag, msg, t)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
        }
    }

    // PUBLIC_INTERFACE
    fun startScanWithServiceUuid(serviceUuid: ParcelUuid, listener: Listener) {
        /**
         * Starts a filtered scan by service UUID.
         * Use this after confirming "no filter" mode works.
         */
        if (adapter == null || !adapter.isEnabled) {
            val msg = "Cannot scan with filter: adapter missing or Bluetooth off."
            Log.w(tag, msg)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
            return
        }

        if (!hasScanPermission()) {
            val msg = "Missing BLUETOOTH_SCAN permission (runtime). Cannot start filtered scan."
            Log.e(tag, msg)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            val msg = "BluetoothLeScanner is null."
            Log.e(tag, msg)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
            return
        }

        stopScanInternal()
        seenAddresses.clear()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(serviceUuid)
                .build()
        )

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = safeGetDeviceAddress(device)
                if (!seenAddresses.add(address)) return

                val name = safeGetDeviceName(device, result)
                val rssi = result.rssi
                val line = "$name  •  $address  •  RSSI $rssi"

                Log.i(tag, "Found device (filtered): $line")
                listener.onDeviceFound(line)
            }

            override fun onScanFailed(errorCode: Int) {
                val msg = "Filtered scan failed. errorCode=$errorCode"
                Log.e(tag, msg)
                listener.onScanError(msg)
                listener.onScanStateChanged(false, msg)
            }
        }

        try {
            Log.i(tag, "Starting BLE scan (service filter=$serviceUuid)")
            scanner?.startScan(filters, settings, scanCallback)
            listener.onScanStateChanged(true, "Scanning… (service filter)")
        } catch (se: SecurityException) {
            val msg = "SecurityException starting filtered scan: ${se.message}"
            Log.e(tag, msg, se)
            listener.onScanError(msg)
            listener.onScanStateChanged(false, msg)
        }
    }

    // PUBLIC_INTERFACE
    fun stopScan() {
        /** Stops BLE scanning (if running). */
        stopScanInternal()
    }

    private fun stopScanInternal() {
        val cb = scanCallback
        val sc = scanner
        scanCallback = null

        if (cb != null && sc != null) {
            if (!hasScanPermission()) {
                // If we no longer have permission, avoid calling into stopScan (lint + potential SecurityException).
                Log.w(tag, "Not stopping scan because BLUETOOTH_SCAN permission is not granted.")
                return
            }

            try {
                Log.i(tag, "Stopping BLE scan.")
                sc.stopScan(cb)
            } catch (se: SecurityException) {
                Log.e(tag, "SecurityException stopping scan: ${se.message}", se)
            } catch (t: Throwable) {
                Log.e(tag, "Unexpected error stopping scan: ${t.message}", t)
            }
        }
    }
}
