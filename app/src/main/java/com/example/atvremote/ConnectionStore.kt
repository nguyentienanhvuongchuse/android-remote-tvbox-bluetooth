package com.example.atvremote

import android.content.Context
import android.util.Base64
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

sealed class SavedConnection {
    data class Lan(val ip: String, val serverCertB64: String) : SavedConnection()

    data class Bluetooth(val address: String, val name: String, val ble: Boolean = false) : SavedConnection()
}

class ConnectionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("atv_connection", Context.MODE_PRIVATE)

    fun load(): SavedConnection? = when (prefs.getString(KEY_TYPE, null)) {
        TYPE_LAN -> {
            val ip = prefs.getString(KEY_LAN_IP, null)
            val cert = prefs.getString(KEY_LAN_CERT, null)
            if (ip != null && cert != null) SavedConnection.Lan(ip, cert) else null
        }
        TYPE_BT -> {
            val addr = prefs.getString(KEY_BT_ADDR, null)
            if (addr != null) SavedConnection.Bluetooth(
                addr, prefs.getString(KEY_BT_NAME, addr) ?: addr, prefs.getBoolean(KEY_BT_BLE, false)
            )
            else null
        }
        else -> null
    }

    fun save(c: SavedConnection) {
        prefs.edit().apply {
            clear()
            when (c) {
                is SavedConnection.Lan -> {
                    putString(KEY_TYPE, TYPE_LAN)
                    putString(KEY_LAN_IP, c.ip)
                    putString(KEY_LAN_CERT, c.serverCertB64)
                }
                is SavedConnection.Bluetooth -> {
                    putString(KEY_TYPE, TYPE_BT)
                    putString(KEY_BT_ADDR, c.address)
                    putString(KEY_BT_NAME, c.name)
                    putBoolean(KEY_BT_BLE, c.ble)
                }
            }
        }.apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_TYPE = "type"
        const val KEY_LAN_IP = "lan_ip"
        const val KEY_LAN_CERT = "lan_cert"
        const val KEY_BT_ADDR = "bt_addr"
        const val KEY_BT_NAME = "bt_name"
        const val KEY_BT_BLE = "bt_ble"
        const val TYPE_LAN = "lan"
        const val TYPE_BT = "bt"
    }
}

fun X509Certificate.toBase64(): String = Base64.encodeToString(encoded, Base64.NO_WRAP)

fun x509FromBase64(b64: String): X509Certificate =
    CertificateFactory.getInstance("X.509")
        .generateCertificate(Base64.decode(b64, Base64.NO_WRAP).inputStream()) as X509Certificate
