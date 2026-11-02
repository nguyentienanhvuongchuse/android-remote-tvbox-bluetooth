package com.example.atvremote.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.atvremote.net.Direction
import com.example.atvremote.net.RemoteTransport
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission")
class BleHidRemoteClient(context: Context) : RemoteTransport {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter = manager?.adapter

    private val reportExecutor = Executors.newSingleThreadExecutor()

    private var server: BluetoothGattServer? = null
    private var host: BluetoothDevice? = null
    private var expectedAddress: String? = null

    private lateinit var keyboardInput: BluetoothGattCharacteristic
    private lateinit var consumerInput: BluetoothGattCharacteristic
    private lateinit var batteryLevel: BluetoothGattCharacteristic

    @Volatile private var connected = false
    override var onDisconnect: (() -> Unit)? = null

    val hostAddress: String? get() = host?.address
    val hostName: String?
        get() = host?.let { try { it.name } catch (_: SecurityException) { null } }

    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private var servicesCont: CancellableContinuation<Unit>? = null
    private var hostCont: CancellableContinuation<BluetoothDevice>? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed: $errorCode")
            hostCont?.let {
                hostCont = null
                it.resumeWithException(IllegalStateException("Không phát được quảng bá BLE (mã lỗi $errorCode)"))
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(TAG, "service ${service.uuid} added, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesCont?.let {
                    servicesCont = null
                    it.resumeWithException(IllegalStateException("Không tạo được GATT service ${service.uuid} ($status)"))
                }
                return
            }
            addNextService()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "conn ${device.address} status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED && device.address == host?.address) {
                val was = connected
                connected = false
                host = null
                if (was) {
                    startAdvertising()
                    onDisconnect?.invoke()
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic
        ) {
            Log.i(TAG, "read ${c.uuid} by ${device.address} off=$offset")
            val full = valueFor(c)
            val value = if (offset >= full.size) ByteArray(0) else full.copyOfRange(offset, full.size)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (c.uuid == REPORT_MAP) attachHost(device)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            Log.i(TAG, "write ${c.uuid} by ${device.address}")
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor
        ) {
            val value = when (d.uuid) {
                REPORT_REFERENCE -> reportReferenceFor(d.characteristic)
                else -> byteArrayOf(0, 0)
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            Log.i(TAG, "cccd ${d.characteristic.uuid} by ${device.address} = ${value?.joinToString()}")
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (d.uuid == CCCD && d.characteristic.uuid == REPORT) attachHost(device)
        }
    }

    suspend fun connect(expectedAddress: String?, timeoutMs: Long = 120_000) {
        val a = adapter ?: throw IllegalStateException("Thiết bị không có Bluetooth")
        if (!a.isEnabled) throw IllegalStateException("Bluetooth đang tắt")
        if (a.bluetoothLeAdvertiser == null)
            throw IllegalStateException("Điện thoại không hỗ trợ phát quảng bá BLE")
        this.expectedAddress = expectedAddress

        try {
            val s = manager!!.openGattServer(appContext, callback)
                ?: throw IllegalStateException("Không mở được GATT server")
            server = s
            suspendCancellableCoroutine<Unit> { cont ->
                servicesCont = cont
                cont.invokeOnCancellation { servicesCont = null }
                pendingServices.clear()
                pendingServices.addAll(buildServices())
                addNextService()
            }

            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<BluetoothDevice> { cont ->
                    hostCont = cont
                    cont.invokeOnCancellation { hostCont = null }
                    startAdvertising()
                }
            }
            stopAdvertising()
        } catch (e: Exception) {
            close()
            throw if (e is TimeoutCancellationException)
                IllegalStateException(
                    "Box chưa kết nối tới remote BLE. Trên box vào Cài đặt → Điều khiển & phụ kiện " +
                        "→ Thêm phụ kiện, rồi chọn tên điện thoại này."
                )
            else e
        }
    }

    private fun attachHost(device: BluetoothDevice) {
        if (connected && host?.address == device.address) return
        host = device
        connected = true
        Log.i(TAG, "HID host attached: ${device.address} (expected $expectedAddress)")
        hostCont?.let { hostCont = null; it.resume(device) }
    }

    private fun addNextService() {
        val s = server ?: return
        val next = pendingServices.removeFirstOrNull()
        if (next == null) {
            servicesCont?.let { servicesCont = null; it.resume(Unit) }
            return
        }
        if (!s.addService(next)) {
            servicesCont?.let {
                servicesCont = null
                it.resumeWithException(IllegalStateException("addService(${next.uuid}) bị từ chối"))
            }
        }
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(HID_SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        try {
            adv.stopAdvertising(advertiseCallback)
            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising", e)
        }
    }

    private fun stopAdvertising() {
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
    }

    override fun sendKey(keyCode: Int, direction: Int) {
        if (!connected) return
        val dev = host ?: return
        val mapped = HidReport.map(keyCode) ?: return

        val (ch, pressed, released) = when (mapped) {
            is HidReport.Mapped.Key -> Triple(
                keyboardInput, HidReport.keyboardReport(mapped.usage), HidReport.keyboardReport(0)
            )
            is HidReport.Mapped.Consumer -> Triple(
                consumerInput, HidReport.consumerReport(mapped.usage), HidReport.consumerReport(0)
            )
        }

        reportExecutor.execute {
            try {
                when (direction) {
                    Direction.START_LONG -> notify(dev, ch, pressed)
                    Direction.END_LONG -> notify(dev, ch, released)
                    else -> {
                        notify(dev, ch, pressed)
                        Thread.sleep(40)
                        notify(dev, ch, released)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun notify(dev: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray) {
        val s = server ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.notifyCharacteristicChanged(dev, ch, false, value)
        } else {
            @Suppress("DEPRECATION")
            ch.value = value
            @Suppress("DEPRECATION")
            s.notifyCharacteristicChanged(dev, ch, false)
        }
    }

    override fun close() {
        connected = false
        try { reportExecutor.shutdownNow() } catch (_: Exception) {}
        stopAdvertising()
        try { server?.close() } catch (_: Exception) {}
        server = null
        host = null
    }

    private fun buildServices(): List<BluetoothGattService> {
        val readEnc = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        val rwEnc = readEnc or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED

        val hid = BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        hid.addCharacteristic(
            BluetoothGattCharacteristic(HID_INFORMATION, BluetoothGattCharacteristic.PROPERTY_READ, readEnc)
        )
        hid.addCharacteristic(
            BluetoothGattCharacteristic(REPORT_MAP, BluetoothGattCharacteristic.PROPERTY_READ, readEnc)
        )
        hid.addCharacteristic(
            BluetoothGattCharacteristic(
                HID_CONTROL_POINT, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
            )
        )
        hid.addCharacteristic(
            BluetoothGattCharacteristic(
                PROTOCOL_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                rwEnc
            )
        )
        consumerInput = inputReport(rwEnc).also { hid.addCharacteristic(it) }
        keyboardInput = inputReport(rwEnc).also { hid.addCharacteristic(it) }

        val dis = BluetoothGattService(DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        dis.addCharacteristic(
            BluetoothGattCharacteristic(PNP_ID, BluetoothGattCharacteristic.PROPERTY_READ, readEnc)
        )

        val battery = BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        batteryLevel = BluetoothGattCharacteristic(
            BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            readEnc
        ).also {
            it.addDescriptor(cccd())
            battery.addCharacteristic(it)
        }

        return listOf(dis, battery, hid)
    }

    private fun inputReport(perm: Int) = BluetoothGattCharacteristic(
        REPORT,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        perm
    ).apply {
        addDescriptor(cccd())
        addDescriptor(
            BluetoothGattDescriptor(REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED)
        )
    }

    private fun cccd() = BluetoothGattDescriptor(
        CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
    )

    private fun reportReferenceFor(c: BluetoothGattCharacteristic): ByteArray = when {
        c === consumerInput -> byteArrayOf(HidReport.ID_CONSUMER.toByte(), REPORT_TYPE_INPUT)
        c === keyboardInput -> byteArrayOf(HidReport.ID_KEYBOARD.toByte(), REPORT_TYPE_INPUT)
        else -> byteArrayOf(0, REPORT_TYPE_INPUT)
    }

    private fun valueFor(c: BluetoothGattCharacteristic): ByteArray = when (c.uuid) {
        HID_INFORMATION -> byteArrayOf(0x11, 0x01, 0x00, 0x02)
        REPORT_MAP -> HidReport.DESCRIPTOR
        PROTOCOL_MODE -> byteArrayOf(0x01)
        PNP_ID -> byteArrayOf(0x02, 0x15, 0x19, 0xEE.toByte(), 0xEE.toByte(), 0x01, 0x00)
        BATTERY_LEVEL -> byteArrayOf(100)
        REPORT -> if (c === consumerInput) HidReport.consumerReport(0) else HidReport.keyboardReport(0)
        else -> ByteArray(0)
    }

    private companion object {
        const val TAG = "BleHidRemote"
        const val REPORT_TYPE_INPUT: Byte = 0x01

        fun uuid16(v: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", v))

        val HID_SERVICE = uuid16(0x1812)
        val HID_INFORMATION = uuid16(0x2A4A)
        val REPORT_MAP = uuid16(0x2A4B)
        val HID_CONTROL_POINT = uuid16(0x2A4C)
        val REPORT = uuid16(0x2A4D)
        val PROTOCOL_MODE = uuid16(0x2A4E)
        val DEVICE_INFO_SERVICE = uuid16(0x180A)
        val PNP_ID = uuid16(0x2A50)
        val BATTERY_SERVICE = uuid16(0x180F)
        val BATTERY_LEVEL = uuid16(0x2A19)
        val CCCD = uuid16(0x2902)
        val REPORT_REFERENCE = uuid16(0x2908)
    }
}
