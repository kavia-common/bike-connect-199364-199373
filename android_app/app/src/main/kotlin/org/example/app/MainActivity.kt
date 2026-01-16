package org.example.app

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import org.example.app.ble.BleManager

class MainActivity : Activity() {

    private val tag = "MainActivity"

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var stopButton: Button
    private lateinit var deviceList: ListView

    private lateinit var deviceAdapter: ArrayAdapter<String>
    private val devices: MutableList<String> = ArrayList()

    private lateinit var bleManager: BleManager

    // Using startActivityForResult for max compatibility (no AndroidX dependencies needed)
    private val requestEnableBtCode = 1001
    private val requestPermissionsCode = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        stopButton = findViewById(R.id.stopButton)
        deviceList = findViewById(R.id.deviceList)

        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, devices)
        deviceList.adapter = deviceAdapter

        bleManager = BleManager(this)

        scanButton.setOnClickListener {
            Log.i(tag, "Scan button tapped.")
            devices.clear()
            deviceAdapter.notifyDataSetChanged()

            if (!bleManager.isBluetoothAvailable()) {
                setStatus("Bluetooth not available on this device.")
                return@setOnClickListener
            }

            // Request permissions first (Android 12+ requires BLUETOOTH_SCAN runtime permission)
            if (!hasRequiredBlePermissions()) {
                requestRequiredBlePermissions()
                return@setOnClickListener
            }

            // Ensure Bluetooth is enabled
            if (!bleManager.isBluetoothEnabled()) {
                setStatus("Bluetooth is OFF — requesting enable…")
                requestEnableBluetooth()
                return@setOnClickListener
            }

            startScanNoFilter()
        }

        stopButton.setOnClickListener {
            Log.i(tag, "Stop button tapped.")
            bleManager.stopScan()
            setStatus("Scan stopped.")
        }

        // Initial status
        setStatus("Ready. Tap Scan (no filter).")
    }

    override fun onStop() {
        super.onStop()
        // Avoid leaking scans when activity goes background.
        bleManager.stopScan()
    }

    private fun startScanNoFilter() {
        bleManager.startScanNoFilter(object : BleManager.Listener {
            override fun onScanStateChanged(isScanning: Boolean, statusMessage: String) {
                Log.i(tag, "Scan state changed: isScanning=$isScanning msg=$statusMessage")
                runOnUiThread { setStatus(statusMessage) }
            }

            override fun onDeviceFound(deviceLine: String) {
                runOnUiThread {
                    devices.add(deviceLine)
                    deviceAdapter.notifyDataSetChanged()
                }
            }

            override fun onScanError(errorMessage: String) {
                Log.e(tag, "Scan error: $errorMessage")
                runOnUiThread { setStatus("Error: $errorMessage") }
            }
        })
    }

    private fun setStatus(message: String) {
        statusText.text = "BLE status: $message"
    }

    private fun requestEnableBluetooth() {
        try {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, requestEnableBtCode)
        } catch (t: Throwable) {
            Log.e(tag, "Unable to request Bluetooth enable: ${t.message}", t)
            setStatus("Unable to prompt for Bluetooth enable.")
        }
    }

    private fun hasRequiredBlePermissions(): Boolean {
        // On Android 12+ we need BLUETOOTH_SCAN and BLUETOOTH_CONNECT runtime permissions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted =
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectGranted =
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            return scanGranted && connectGranted
        }

        // minSdk=30, so this branch is mostly for completeness; pre-31 often needs location permission for scan results.
        val fineLocationGranted =
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fineLocationGranted
    }

    private fun requestRequiredBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        Log.i(tag, "Requesting BLE permissions: ${perms.joinToString()}")
        requestPermissions(perms, requestPermissionsCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != requestPermissionsCode) return

        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        Log.i(tag, "Permissions result allGranted=$allGranted perms=${permissions.joinToString()}")

        if (!allGranted) {
            setStatus("Permissions denied. Cannot scan.")
            return
        }

        setStatus("Permissions granted. Tap Scan again.")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == requestEnableBtCode) {
            val enabled = resultCode == RESULT_OK
            Log.i(tag, "Bluetooth enable result: enabled=$enabled")
            if (enabled) {
                setStatus("Bluetooth enabled. Starting scan…")
                startScanNoFilter()
            } else {
                setStatus("Bluetooth not enabled. Cannot scan.")
            }
        }
    }
}
