package com.example.atvremote.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission")
class BluetoothScanner(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun bondedDevices(): List<BluetoothDevice> =
        try {
            adapter?.bondedDevices?.sortedBy { nameOf(it) } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

    fun nameOf(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (_: SecurityException) { device.address }

    fun localName(): String =
        try { adapter?.name ?: "điện thoại này" } catch (_: SecurityException) { "điện thoại này" }

    private var receiver: BroadcastReceiver? = null
    private var bleCallback: ScanCallback? = null

    fun startDiscovery(onFound: (BluetoothDevice) -> Unit, onFinished: () -> Unit) {
        val a = adapter
        if (a == null || !a.isEnabled) { onFinished(); return }
        stopDiscovery()

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (d != null) onFound(d)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        stopDiscovery()
                        onFinished()
                    }
                }
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            context,
            r,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val leScanner = a.bluetoothLeScanner
        if (leScanner != null) {
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    onFound(result.device)
                }
                override fun onScanFailed(errorCode: Int) {}
            }
            bleCallback = cb
            try {
                leScanner.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    cb
                )
            } catch (_: Exception) {
                bleCallback = null
            }
        }

        if (a.isDiscovering) a.cancelDiscovery()
        if (!a.startDiscovery()) {
            stopDiscovery()
            onFinished()
        }
    }

    fun stopDiscovery() {
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        receiver?.let { rec ->
            try { context.unregisterReceiver(rec) } catch (_: Exception) {}
        }
        receiver = null
        bleCallback?.let { cb ->
            try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
        }
        bleCallback = null
    }
}
