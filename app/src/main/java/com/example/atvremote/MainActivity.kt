package com.example.atvremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.atvremote.bt.BleHidRemoteClient
import com.example.atvremote.bt.BluetoothRemoteClient
import com.example.atvremote.bt.BluetoothScanner
import com.example.atvremote.bt.HidDeviceUnavailableException
import com.example.atvremote.net.BoxScanner
import com.example.atvremote.net.CertificateManager
import com.example.atvremote.net.Direction
import com.example.atvremote.net.KeyCode
import com.example.atvremote.net.PairingClient
import com.example.atvremote.net.RemoteClient
import com.example.atvremote.net.RemoteTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Screen {
    object SavedDevice : Screen()

    object Reconnecting : Screen()

    object ChooseConnection : Screen()

    object EnterIp : Screen()
    object EnterPin : Screen()

    object BluetoothScan : Screen()

    object Remote : Screen()
}

@SuppressLint("MissingPermission")
private suspend fun reconnect(context: Context, saved: SavedConnection): RemoteTransport =
    when (saved) {
        is SavedConnection.Lan ->
            RemoteClient(saved.ip, x509FromBase64(saved.serverCertB64)).also { it.connect() }

        is SavedConnection.Bluetooth -> if (saved.ble) {
            BleHidRemoteClient(context).also { it.connect(saved.address, timeoutMs = 30_000) }
        } else {
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                    ?: error("Thiết bị không có Bluetooth")
            val device = adapter.getRemoteDevice(saved.address)
            BluetoothRemoteClient(context).also { it.connect(device) }
        }
    }

private fun isBluetoothOn(context: Context): Boolean =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

private fun openBluetoothSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun rememberBluetoothOn(): State<Boolean> {
    val context = LocalContext.current
    val on = remember { mutableStateOf(isBluetoothOn(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                on.value = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return on
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CertificateManager.ensureClientKey()
        setContent {
            AppTheme { AtvRemoteApp() }
        }
    }
}

private val BaseTypography = Typography()
private val DoubledTypography = scaledTypography(2f)

private fun scaledTypography(factor: Float): Typography {
    fun TextStyle.scaled(): TextStyle = copy(
        fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight
    )
    with(BaseTypography) {
        return Typography(
            displayLarge = displayLarge.scaled(),
            displayMedium = displayMedium.scaled(),
            displaySmall = displaySmall.scaled(),
            headlineLarge = headlineLarge.scaled(),
            headlineMedium = headlineMedium.scaled(),
            headlineSmall = headlineSmall.scaled(),
            titleLarge = titleLarge.scaled(),
            titleMedium = titleMedium.scaled(),
            titleSmall = titleSmall.scaled(),
            bodyLarge = bodyLarge.scaled(),
            bodyMedium = bodyMedium.scaled(),
            bodySmall = bodySmall.scaled(),
            labelLarge = labelLarge.scaled(),
            labelMedium = labelMedium.scaled(),
            labelSmall = labelSmall.scaled()
        )
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(typography = DoubledTypography) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge, content)
    }
}

@Composable
private fun BaseTextTheme(content: @Composable () -> Unit) {
    MaterialTheme(typography = BaseTypography) {
        ProvideTextStyle(BaseTypography.bodyLarge, content)
    }
}

@Composable
fun AtvRemoteApp() {
    val context = LocalContext.current
    val store = remember { ConnectionStore(context) }

    var screen by remember {
        mutableStateOf<Screen>(
            if (store.load() != null) Screen.SavedDevice else Screen.ChooseConnection
        )
    }
    var ip by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanInfo by remember { mutableStateOf("") }
    var foundIps by remember { mutableStateOf<List<String>>(emptyList()) }
    var pairingClient by remember { mutableStateOf<PairingClient?>(null) }
    var transport by remember { mutableStateOf<RemoteTransport?>(null) }
    var storeVersion by remember { mutableIntStateOf(0) }
    val scope: CoroutineScope = rememberCoroutineScope()

    fun goRemoteWith(t: RemoteTransport, saved: SavedConnection?) {
        transport?.close()
        transport = t
        t.onDisconnect = { status = "Mất kết nối tới box. Thử kết nối lại." }
        if (saved != null) { store.save(saved); storeVersion++ }
        status = ""
        screen = Screen.Remote
    }

    val saved = remember(screen, storeVersion) { store.load() }
    val savedLabel = saved?.let(::labelOf)

    val bluetoothOn by rememberBluetoothOn()
    var reconnectWhenBtOn by remember { mutableStateOf(false) }
    LaunchedEffect(bluetoothOn) {
        if (bluetoothOn && reconnectWhenBtOn && screen is Screen.SavedDevice) {
            reconnectWhenBtOn = false
            status = "Bluetooth đã bật, đang chuẩn bị kết nối lại..."
            delay(2_000)
            status = ""
            screen = Screen.Reconnecting
        }
    }

    LaunchedEffect(screen) {
        if (screen !is Screen.Reconnecting) return@LaunchedEffect
        val s = store.load() ?: run { screen = Screen.ChooseConnection; return@LaunchedEffect }
        try {
            goRemoteWith(reconnect(context, s), null)
        } catch (e: Exception) {
            status = "Không kết nối lại được: ${e.message}"
            screen = Screen.SavedDevice
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (screen) {
            is Screen.SavedDevice -> SavedDeviceScreen(
                deviceName = saved?.let(::nameOf) ?: "",
                detail = savedLabel ?: "",
                status = status,
                onReconnect = {
                    if (saved is SavedConnection.Bluetooth && !isBluetoothOn(context)) {
                        status = "Bluetooth đang tắt. Hãy bật Bluetooth, app sẽ tự kết nối lại."
                        reconnectWhenBtOn = true
                        openBluetoothSettings(context)
                    } else {
                        status = ""; screen = Screen.Reconnecting
                    }
                },
                onOtherDevice = {
                    reconnectWhenBtOn = false
                    status = ""; screen = Screen.ChooseConnection
                }
            )

            is Screen.Reconnecting -> ReconnectingScreen(
                label = savedLabel ?: "",
                onCancel = { status = ""; screen = Screen.SavedDevice }
            )

            is Screen.ChooseConnection -> ChooseConnectionScreen(
                status = status,
                onLan = {
                    status = ""; scanInfo = ""; foundIps = emptyList()
                    screen = Screen.EnterIp
                },
                onBluetooth = {
                    status = ""
                    if (!isBluetoothOn(context)) openBluetoothSettings(context)
                    screen = Screen.BluetoothScan
                },
                onBack = if (saved != null) ({ status = ""; screen = Screen.SavedDevice }) else null
            )

            is Screen.EnterIp -> EnterIpScreen(
                ip = ip,
                status = status,
                scanning = scanning,
                scanInfo = scanInfo,
                foundIps = foundIps,
                onBack = { screen = Screen.ChooseConnection },
                onIpChange = { ip = it },
                onScan = {
                    scanning = true
                    foundIps = emptyList()
                    status = ""
                    scanInfo = "Đang xác định mạng WiFi..."
                    scope.launch(Dispatchers.IO) {
                        try {
                            val prefix = BoxScanner.localSubnetPrefix()
                            scanInfo = "Đang quét $prefix.1-254 ..."
                            val results = BoxScanner.scan(
                                prefix = prefix,
                                onProgress = { d, t -> scanInfo = "Đang quét $prefix.x  ($d/$t)" },
                                onFound = { hit -> foundIps = (foundIps + hit).distinct() }
                            )
                            foundIps = results
                            scanInfo = if (results.isEmpty())
                                "Không thấy thiết bị nào mở cổng 6467.\nKiểm tra box đã bật và cùng mạng WiFi với điện thoại."
                            else
                                "Tìm thấy ${results.size} thiết bị. Chọn 1 IP bên dưới rồi bấm Kết nối."
                        } catch (e: Exception) {
                            scanInfo = "Lỗi khi quét: ${e.message}"
                        } finally {
                            scanning = false
                        }
                    }
                },
                onConnect = {
                    status = "Đang kết nối..."
                    scope.launch(Dispatchers.IO) {
                        try {
                            val client = PairingClient(ip)
                            client.connectAndRequestPin()
                            pairingClient = client
                            status = ""
                            screen = Screen.EnterPin
                        } catch (e: Exception) {
                            status = "Lỗi kết nối: ${e.message}\nKiểm tra lại IP và cùng mạng WiFi."
                        }
                    }
                }
            )

            is Screen.EnterPin -> EnterPinScreen(
                status = status,
                onSubmitPin = { pin ->
                    status = "Đang xác thực..."
                    scope.launch(Dispatchers.IO) {
                        try {
                            val client = pairingClient ?: error("Chưa kết nối")
                            val ok = client.submitPin(pin)
                            if (ok) {
                                val serverCert = client.serverCertificate!!
                                val rc = RemoteClient(ip, serverCert)
                                rc.connect()
                                goRemoteWith(rc, SavedConnection.Lan(ip, serverCert.toBase64()))
                            } else {
                                status = "Sai mã PIN, thử lại."
                            }
                        } catch (e: Exception) {
                            status = "Lỗi ghép đôi: ${e.message}"
                        }
                    }
                }
            )

            is Screen.BluetoothScan -> BluetoothScanScreen(
                onBack = { screen = Screen.ChooseConnection },
                onConnected = { t, saved -> goRemoteWith(t, saved) }
            )

            is Screen.Remote -> BaseTextTheme {
                RemoteScreen(
                    status = status,
                    onKey = { code, dir -> transport?.sendKey(code, dir) },
                    onDisconnect = {
                        transport?.close()
                        transport = null
                        status = ""
                        screen = if (store.load() != null) Screen.SavedDevice else Screen.ChooseConnection
                    }
                )
            }
        }
    }
}

private fun nameOf(c: SavedConnection): String = when (c) {
    is SavedConnection.Lan -> c.ip
    is SavedConnection.Bluetooth -> c.name
}

private fun labelOf(c: SavedConnection): String = when (c) {
    is SavedConnection.Lan -> "LAN · ${c.ip}"
    is SavedConnection.Bluetooth -> (if (c.ble) "Bluetooth LE · " else "Bluetooth · ") + c.name
}

@Composable
fun SavedDeviceScreen(
    deviceName: String,
    detail: String,
    status: String,
    onReconnect: () -> Unit,
    onOtherDevice: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onReconnect,
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Kết nối lại", style = MaterialTheme.typography.titleMedium)
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(status, color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedButton(onClick = onOtherDevice, modifier = Modifier.fillMaxWidth()) {
            Text("Thiết bị khác")
        }
    }
}

@Composable
fun ReconnectingScreen(label: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text("Đang kết nối lại...", style = MaterialTheme.typography.titleMedium)
        if (label.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCancel) { Text("Huỷ, chọn kết nối khác") }
    }
}

@Composable
fun ChooseConnectionScreen(
    onLan: () -> Unit,
    onBluetooth: () -> Unit,
    status: String = "",
    onBack: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Chọn cách kết nối tới box", style = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(24.dp))
        Button(onClick = onLan, modifier = Modifier.fillMaxWidth()) {
            Text("Kết nối qua WiFi / LAN")
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBluetooth, modifier = Modifier.fillMaxWidth()) {
            Text("Kết nối qua Bluetooth")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "LAN: điện thoại và box cùng mạng WiFi, dùng giao thức Android TV Remote v2.\n\n" +
                "Bluetooth: điện thoại đóng vai điều khiển HID; box cần được ghép đôi Bluetooth " +
                "với điện thoại (giống remote gốc).",
            style = MaterialTheme.typography.bodySmall
        )
        if (status.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(status, color = MaterialTheme.colorScheme.error)
        }
        if (onBack != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text("← Thiết bị đã lưu") }
        }
    }
}

@Composable
fun EnterIpScreen(
    ip: String,
    status: String,
    scanning: Boolean,
    scanInfo: String,
    foundIps: List<String>,
    onBack: () -> Unit,
    onIpChange: (String) -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Địa chỉ IP của MyTV Box", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Không biết IP? Bấm \"Quét mạng\" để tự dò các thiết bị trong mạng WiFi.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = ip,
            onValueChange = onIpChange,
            singleLine = true,
            label = { Text("Ví dụ: 192.168.1.23") }
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onScan, enabled = !scanning) {
                Text(if (scanning) "Đang quét..." else "Quét mạng")
            }
            Button(onClick = onConnect, enabled = ip.isNotBlank() && !scanning) {
                Text("Kết nối & ghép đôi")
            }
        }

        if (scanning) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (scanInfo.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(scanInfo, style = MaterialTheme.typography.bodySmall)
        }
        if (foundIps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Thiết bị mở cổng 6467 (nhiều khả năng là box):",
                style = MaterialTheme.typography.bodySmall
            )
            foundIps.forEach { found ->
                TextButton(onClick = { onIpChange(found) }) {
                    Text(if (found == ip) "● $found" else found)
                }
            }
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(status, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("← Chọn cách kết nối khác") }
    }
}

@Composable
fun EnterPinScreen(status: String, onSubmitPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Nhập mã 6 ký tự đang hiện trên màn hình TV", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = pin, onValueChange = { pin = it.uppercase() }, label = { Text("VD: 3F2A91") })
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmitPin(pin) }, enabled = pin.length >= 4) { Text("Xác nhận") }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(status, color = MaterialTheme.colorScheme.error)
        }
    }
}

data class BtDeviceRow(val address: String, val name: String, val bonded: Boolean = false)

@Composable
fun BluetoothScanScreen(
    onBack: () -> Unit,
    onConnected: (RemoteTransport, SavedConnection.Bluetooth) -> Unit
) {
    val context = LocalContext.current
    val scanner = remember { BluetoothScanner(context) }
    val scope = rememberCoroutineScope()

    val devices = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var connectingAddr by remember { mutableStateOf<String?>(null) }

    val needPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        else
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
    }

    fun granted(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    fun hasPerms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT) &&
                granted(Manifest.permission.BLUETOOTH_ADVERTISE)
        else
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun loadBonded() {
        devices.clear()
        scanner.bondedDevices().forEach { devices.add(it) }
        status = if (devices.isEmpty())
            "Chưa có thiết bị nào từng ghép đôi. Nhấn \"Quét thiết bị Bluetooth\" để tìm box."
        else
            "Chọn thiết bị đã ghép đôi bên dưới để kết nối, hoặc quét để tìm thêm."
        isError = false
    }

    fun beginScan() {
        if (!scanner.isSupported) { status = "Máy này không có Bluetooth."; isError = true; return }
        if (!scanner.isEnabled) {
            status = "Bluetooth đang tắt. Hãy bật Bluetooth, app sẽ tự quét."
            isError = true
            openBluetoothSettings(context)
            return
        }
        devices.clear()
        scanner.bondedDevices().forEach { devices.add(it) }
        status = "Đang quét thiết bị Bluetooth..."
        isError = false
        scanning = true
        scanner.startDiscovery(
            onFound = { d -> if (devices.none { it.address == d.address }) devices.add(d) },
            onFinished = {
                scanning = false
                if (devices.isEmpty()) {
                    status = "Không thấy thiết bị Bluetooth nào.\n" +
                        "Đảm bảo box đang bật và ở chế độ ghép đôi, rồi quét lại."
                    isError = true
                } else {
                    status = "Chọn thiết bị bên dưới để kết nối."
                    isError = false
                }
            }
        )
    }

    val permLauncher =
        if (LocalInspectionMode.current) null
        else rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                result[Manifest.permission.BLUETOOTH_SCAN] == true &&
                    result[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                    result[Manifest.permission.BLUETOOTH_ADVERTISE] == true
            else
                result.values.any { it }
            if (ok) beginScan()
            else { status = "Cần cấp quyền Bluetooth để quét thiết bị."; isError = true }
        }

    fun connectByAddress(address: String) {
        val device = devices.firstOrNull { it.address == address } ?: return
        scanner.stopDiscovery()
        scanning = false
        connectingAddr = device.address
        status = "Đang kết nối tới ${scanner.nameOf(device)}..."
        isError = false
        scope.launch {
            try {
                val client = BluetoothRemoteClient(context)
                client.connect(device)
                connectingAddr = null
                onConnected(client, SavedConnection.Bluetooth(device.address, scanner.nameOf(device)))
            } catch (_: HidDeviceUnavailableException) {
                status = "Điện thoại không hỗ trợ remote Bluetooth Classic, đã chuyển sang remote BLE.\n\n" +
                    "Trên box: Cài đặt → Điều khiển & phụ kiện → Thêm phụ kiện, " +
                    "rồi chọn \"${scanner.localName()}\". Đang chờ box kết nối (tối đa 2 phút)..."
                isError = false
                try {
                    val ble = BleHidRemoteClient(context)
                    ble.connect(device.address)
                    connectingAddr = null
                    val addr = ble.hostAddress ?: device.address
                    val name = ble.hostName ?: scanner.nameOf(device)
                    onConnected(ble, SavedConnection.Bluetooth(addr, name, ble = true))
                } catch (e: Exception) {
                    connectingAddr = null
                    status = "Remote BLE thất bại: ${e.message}"
                    isError = true
                }
            } catch (e: Exception) {
                connectingAddr = null
                status = "Kết nối Bluetooth thất bại: ${e.message}"
                isError = true
            }
        }
    }

    fun requestScan() {
        when {
            hasPerms() -> beginScan()
            permLauncher != null -> permLauncher.launch(needPerms)
            else -> beginScan()
        }
    }

    LaunchedEffect(Unit) {
        if (!scanner.isEnabled) return@LaunchedEffect
        if (hasPerms()) loadBonded()
        requestScan()
    }

    DisposableEffect(Unit) {
        onDispose { scanner.stopDiscovery() }
    }

    val bluetoothOn by rememberBluetoothOn()
    var wasOff by remember { mutableStateOf(!bluetoothOn) }
    LaunchedEffect(bluetoothOn) {
        if (!bluetoothOn) {
            wasOff = true
            if (!scanning) { status = "Bluetooth đang tắt. Hãy bật Bluetooth, app sẽ tự quét."; isError = true }
        } else if (wasOff) {
            wasOff = false
            if (hasPerms()) loadBonded()
            requestScan()
        }
    }

    val bondedAddrs = scanner.bondedDevices().map { it.address }.toSet()

    BluetoothScanContent(
        scanning = scanning,
        status = status,
        isError = isError,
        devices = devices.map { BtDeviceRow(it.address, scanner.nameOf(it), it.address in bondedAddrs) },
        connectingAddr = connectingAddr,
        onScanClick = ::requestScan,
        onDeviceClick = ::connectByAddress,
        onBack = onBack
    )
}

@Composable
private fun BluetoothScanContent(
    scanning: Boolean,
    status: String,
    isError: Boolean,
    devices: List<BtDeviceRow>,
    connectingAddr: String?,
    onScanClick: () -> Unit,
    onDeviceClick: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Kết nối Bluetooth", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Chạm vào thiết bị để kết nối. Danh sách tự cập nhật trong lúc quét.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onScanClick,
            enabled = !scanning && connectingAddr == null
        ) {
            Text(if (scanning) "Đang quét..." else "Quét lại")
        }

        if (scanning) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val (bonded, found) = devices.partition { it.bonded }
        BtDeviceSection("Đã ghép đôi", bonded, connectingAddr, onDeviceClick)
        BtDeviceSection("Thiết bị tìm thấy (${found.size})", found, connectingAddr, onDeviceClick)

        if (status.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                status,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("← Chọn cách kết nối khác") }
    }
}

@Composable
private fun BtDeviceSection(
    title: String,
    devices: List<BtDeviceRow>,
    connectingAddr: String?,
    onDeviceClick: (String) -> Unit
) {
    if (devices.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    )
    devices.forEach { d ->
        val connecting = connectingAddr == d.address
        OutlinedButton(
            onClick = { onDeviceClick(d.address) },
            enabled = connectingAddr == null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(d.name + if (connecting) "  (đang kết nối...)" else "")
                if (d.name != d.address) {
                    Text(d.address, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private val RemoteBody = Color(0xFF2B2B2F)
private val RemoteBodyEdge = Color(0xFF1E1E22)
private val RemoteKey = Color(0xFF3B3B41)
private val RemoteKeyRaised = Color(0xFF4A4A52)
private val RemoteKeyText = Color(0xFFE9E9EC)
private val RemotePowerRed = Color(0xFFD8382F)
private val RemotePressedGreen = Color(0xFF2E9E48)
private const val PRESS_HIGHLIGHT_MS = 3_000L

private val LocalLitKey = compositionLocalOf<String?> { null }

@Composable
fun RemoteScreen(status: String, onKey: (Int, Int) -> Unit, onDisconnect: () -> Unit = {}) {
    var note by remember { mutableStateOf("") }
    var lastKey by remember { mutableStateOf<String?>(null) }
    var litKey by remember { mutableStateOf<String?>(null) }
    var pressSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(pressSeq) {
        if (pressSeq == 0) return@LaunchedEffect
        delay(PRESS_HIGHLIGHT_MS)
        litKey = null
    }
    fun press(name: String) {
        lastKey = name
        litKey = name
        pressSeq++
    }
    val tap: (String, Int) -> Unit = { name, code ->
        note = ""
        press(name)
        onKey(code, Direction.SHORT)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            lastKey ?: " ",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        CompositionLocalProvider(LocalLitKey provides litKey) {
            Surface(
                shape = RoundedCornerShape(44.dp),
                color = RemoteBody,
                border = BorderStroke(1.dp, RemoteBodyEdge)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                        RoundKey(Icons.Filled.PowerSettingsNew, "Nguồn", bg = RemotePowerRed, tint = Color.White) {
                            tap("Nguồn", KeyCode.POWER)
                        }
                        RoundKey(Icons.AutoMirrored.Filled.VolumeOff, "Tắt tiếng") { tap("Tắt tiếng", KeyCode.VOLUME_MUTE) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                        RoundKey(Icons.AutoMirrored.Filled.VolumeDown, "Giảm âm lượng") {
                            tap("Giảm âm lượng", KeyCode.VOLUME_DOWN)
                        }
                        RoundKey(Icons.AutoMirrored.Filled.VolumeUp, "Tăng âm lượng") {
                            tap("Tăng âm lượng", KeyCode.VOLUME_UP)
                        }
                    }

                    DPad(tap)

                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PillKey(Icons.AutoMirrored.Filled.KeyboardReturn, "Quay lại") { tap("Quay lại", KeyCode.BACK) }
                        PillKey(Icons.Filled.Menu, "Menu") { tap("Menu", KeyCode.MENU) }
                        PillKey(Icons.Filled.Home, "Màn hình chính") { tap("Màn hình chính", KeyCode.HOME) }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NumKey(1, tap); NumKey(2, tap); NumKey(3, tap)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NumKey(4, tap); NumKey(5, tap); NumKey(6, tap)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NumKey(7, tap); NumKey(8, tap); NumKey(9, tap)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RoundKey(Icons.Filled.Mouse, "Chế độ chuột", size = 52.dp) {
                                press("Chế độ chuột")
                                note = "Chế độ chuột chưa được hỗ trợ trên kết nối này."
                            }
                            NumKey(0, tap)
                            RoundKey(Icons.AutoMirrored.Filled.Backspace, "Xoá", size = 52.dp) {
                                tap("Xoá", KeyCode.DEL)
                            }
                        }
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(status, color = MaterialTheme.colorScheme.error)
        }
        if (note.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                note,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onDisconnect,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Ngắt kết nối")
        }
        Text(
            "App vẫn nhớ thiết bị này để kết nối lại lần sau.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun keyColor(name: String, normal: Color): Color {
    val color by animateColorAsState(
        if (LocalLitKey.current == name) RemotePressedGreen else normal,
        label = "key-$name"
    )
    return color
}

@Composable
private fun DPad(onTap: (String, Int) -> Unit) {
    Box(
        modifier = Modifier.size(216.dp).clip(CircleShape).background(RemoteKey),
        contentAlignment = Alignment.Center
    ) {
        DirKey(Icons.Filled.KeyboardArrowUp, "Lên", Modifier.align(Alignment.TopCenter)) { onTap("Lên", KeyCode.DPAD_UP) }
        DirKey(Icons.Filled.KeyboardArrowDown, "Xuống", Modifier.align(Alignment.BottomCenter)) { onTap("Xuống", KeyCode.DPAD_DOWN) }
        DirKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Trái", Modifier.align(Alignment.CenterStart)) { onTap("Trái", KeyCode.DPAD_LEFT) }
        DirKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Phải", Modifier.align(Alignment.CenterEnd)) { onTap("Phải", KeyCode.DPAD_RIGHT) }
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(keyColor("OK", RemoteKeyRaised))
                .clickable { onTap("OK", KeyCode.DPAD_CENTER) },
            contentAlignment = Alignment.Center
        ) {
            Text("OK", color = RemoteKeyText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun DirKey(icon: ImageVector, desc: String, modifier: Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(52.dp).clip(CircleShape).background(keyColor(desc, Color.Transparent))
    ) {
        Icon(icon, contentDescription = desc, tint = RemoteKeyText, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun RoundKey(
    icon: ImageVector,
    desc: String,
    bg: Color = RemoteKey,
    tint: Color = RemoteKeyText,
    size: Dp = 56.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(keyColor(desc, bg)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun PillKey(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 62.dp, height = 42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(keyColor(desc, RemoteKey))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = RemoteKeyText, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun NumKey(n: Int, onTap: (String, Int) -> Unit) {
    val name = "Số $n"
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(keyColor(name, RemoteKey))
            .clickable { onTap(name, KeyCode.digit(n)) },
        contentAlignment = Alignment.Center
    ) {
        Text("$n", color = RemoteKeyText, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Preview(name = "1 · Chọn kết nối", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun ChooseConnectionScreenPreview() {
    AppTheme {
        Surface { ChooseConnectionScreen(onLan = {}, onBluetooth = {}) }
    }
}

@Preview(name = "0 · Thiết bị đã lưu", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun SavedDeviceScreenPreview() {
    AppTheme {
        Surface {
            SavedDeviceScreen(
                deviceName = "VNPT SmartBox 2x",
                detail = "Bluetooth · VNPT SmartBox 2x",
                status = "Không kết nối lại được: timeout",
                onReconnect = {}, onOtherDevice = {}
            )
        }
    }
}

@Preview(name = "0 · Đang kết nối lại", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun ReconnectingScreenPreview() {
    AppTheme {
        Surface { ReconnectingScreen(label = "LAN · 192.168.1.12", onCancel = {}) }
    }
}

@Preview(name = "1 · LAN — nhập / quét IP", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun EnterIpScreenPreview() {
    AppTheme {
        Surface {
            EnterIpScreen(
                ip = "192.168.1.12",
                status = "",
                scanning = false,
                scanInfo = "Tìm thấy 2 thiết bị. Chọn 1 IP bên dưới rồi bấm Kết nối.",
                foundIps = listOf("192.168.1.12", "192.168.1.30"),
                onBack = {},
                onIpChange = {},
                onScan = {},
                onConnect = {}
            )
        }
    }
}

@Preview(name = "1 · LAN — nhập PIN", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun EnterPinScreenPreview() {
    AppTheme {
        Surface { EnterPinScreen(status = "Sai mã PIN, thử lại.", onSubmitPin = {}) }
    }
}

@Preview(name = "1 · Bluetooth — quét", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun BluetoothScanScreenPreview() {
    AppTheme {
        Surface {
            BluetoothScanContent(
                scanning = false,
                status = "Chọn thiết bị bên dưới để kết nối.",
                isError = false,
                devices = listOf(
                    BtDeviceRow("AA:BB:CC:DD:EE:01", "MyTV Box", bonded = true),
                    BtDeviceRow("AA:BB:CC:DD:EE:02", "Phòng khách TV"),
                    BtDeviceRow("AA:BB:CC:DD:EE:03", "Galaxy Buds")
                ),
                connectingAddr = "AA:BB:CC:DD:EE:01",
                onScanClick = {},
                onDeviceClick = {},
                onBack = {}
            )
        }
    }
}

@Preview(name = "2 · Remote (ngoas_remote)", showBackground = true, widthDp = 360, heightDp = 940)
@Composable
private fun RemoteScreenPreview() {
    BaseTextTheme {
        Surface { RemoteScreen(status = "", onKey = { _, _ -> }, onDisconnect = {}) }
    }
}
