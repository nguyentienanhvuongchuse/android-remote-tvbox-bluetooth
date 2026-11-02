package com.example.atvremote.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.example.atvremote.net.Direction
import com.example.atvremote.net.RemoteTransport
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HidDeviceUnavailableException :
    IllegalStateException("Điện thoại này đã tắt profile Bluetooth HID Device (Classic).")

@SuppressLint("MissingPermission")
class BluetoothRemoteClient(context: Context) : RemoteTransport {

    private val appContext = context.applicationContext
    private val adapter =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val reportExecutor = Executors.newSingleThreadExecutor()
    private val callbackExecutor = Executor { it.run() }

    private var hid: BluetoothHidDevice? = null
    private var target: BluetoothDevice? = null

    @Volatile private var connected = false
    override var onDisconnect: (() -> Unit)? = null

    private var registerCont: CancellableContinuation<Unit>? = null
    private var connectCont: CancellableContinuation<Unit>? = null

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) {
                registerCont?.let { it.resume(Unit) }
                registerCont = null
            } else {
                registerCont?.let {
                    it.resumeWithException(IllegalStateException("Hệ thống huỷ đăng ký HID"))
                }
                registerCont = null
                if (connected) {
                    connected = false
                    onDisconnect?.invoke()
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = true
                    connectCont?.let { it.resume(Unit) }
                    connectCont = null
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = connected
                    connected = false
                    connectCont?.let {
                        it.resumeWithException(IllegalStateException("Box từ chối kết nối HID"))
                    }
                    connectCont = null
                    if (wasConnected) onDisconnect?.invoke()
                }
            }
        }
    }

    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = 25_000) {
        val a = adapter ?: throw IllegalStateException("Thiết bị không có Bluetooth")
        if (!a.isEnabled) throw IllegalStateException("Bluetooth đang tắt")

        try {
            withTimeout(timeoutMs) {
                val proxy = withTimeoutOrNull(4_000) { awaitProxy() }
                    ?: throw HidDeviceUnavailableException()
                hid = proxy
                target = device

                suspendCancellableCoroutine<Unit> { cont ->
                    registerCont = cont
                    cont.invokeOnCancellation { registerCont = null }
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "ATV Remote",
                        "Bluetooth remote for Android TV / MyTV box",
                        "ATV Remote",
                        BluetoothHidDevice.SUBCLASS2_UNCATEGORIZED,
                        HidReport.DESCRIPTOR
                    )
                    val ok = proxy.registerApp(
                        sdp, null, null, callbackExecutor, hidCallback
                    )
                    if (!ok) {
                        registerCont = null
                        cont.resumeWithException(
                            IllegalStateException("registerApp() bị hệ thống từ chối (một app HID khác đang giữ đăng ký?)")
                        )
                    }
                }

                suspendCancellableCoroutine<Unit> { cont ->
                    connectCont = cont
                    cont.invokeOnCancellation { connectCont = null }
                    val live = try {
                        proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                    } catch (_: Exception) {
                        false
                    }
                    if (live) {
                        connected = true
                        connectCont = null
                        cont.resume(Unit)
                    } else if (!proxy.connect(device)) {
                        connectCont = null
                        cont.resumeWithException(
                            IllegalStateException("Không gọi được connect(). Box có thể không nhận điều khiển kiểu bàn phím Bluetooth.")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            close()
            throw if (e is TimeoutCancellationException)
                IllegalStateException(
                    "Hết thời gian chờ box chấp nhận kết nối HID. Box có thể không hỗ trợ " +
                        "remote Bluetooth kiểu Classic HID (nhiều box chỉ ghép với remote BLE)."
                )
            else e
        }
    }

    override fun sendKey(keyCode: Int, direction: Int) {
        if (!connected) return
        val dev = target ?: return
        val h = hid ?: return
        val mapped = HidReport.map(keyCode) ?: return

        val (reportId, pressed, released) = when (mapped) {
            is HidReport.Mapped.Key -> Triple(
                HidReport.ID_KEYBOARD,
                HidReport.keyboardReport(mapped.usage),
                HidReport.keyboardReport(0)
            )
            is HidReport.Mapped.Consumer -> Triple(
                HidReport.ID_CONSUMER,
                HidReport.consumerReport(mapped.usage),
                HidReport.consumerReport(0)
            )
        }

        reportExecutor.execute {
            try {
                when (direction) {
                    Direction.START_LONG -> h.sendReport(dev, reportId, pressed)
                    Direction.END_LONG -> h.sendReport(dev, reportId, released)
                    else -> {
                        h.sendReport(dev, reportId, pressed)
                        Thread.sleep(40)
                        h.sendReport(dev, reportId, released)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        connected = false
        try { reportExecutor.shutdownNow() } catch (_: Exception) {}
        val h = hid
        try { h?.unregisterApp() } catch (_: Exception) {}
        try {
            if (h != null) adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        } catch (_: Exception) {}
        hid = null
        target = null
    }

    private suspend fun awaitProxy(): BluetoothHidDevice =
        suspendCancellableCoroutine { cont ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HID_DEVICE && cont.isActive) {
                        cont.resume(proxy as BluetoothHidDevice)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }
            val started =
                adapter?.getProfileProxy(appContext, listener, BluetoothProfile.HID_DEVICE) == true
            if (!started && cont.isActive) {
                cont.resumeWithException(IllegalStateException("Thiết bị không hỗ trợ Bluetooth HID"))
            }
        }
}
