package com.example.atvremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

object BoxScanner {

    private const val PAIRING_PORT = 6467
    private const val CONNECT_TIMEOUT_MS = 400
    private const val MAX_CONCURRENCY = 64

    fun localSubnetPrefix(): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        return ip.substringBeforeLast('.')
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "192.168.1"
    }

    suspend fun scan(
        prefix: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onFound: (ip: String) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val hosts = (1..254).map { "$prefix.$it" }
        val gate = Semaphore(MAX_CONCURRENCY)
        val done = AtomicInteger(0)
        val found = java.util.Collections.synchronizedList(ArrayList<String>())
        coroutineScope {
            hosts.map { host ->
                async {
                    gate.withPermit {
                        if (isPairingPortOpen(host)) {
                            found.add(host)
                            onFound(host)
                        }
                    }
                    onProgress(done.incrementAndGet(), hosts.size)
                }
            }.awaitAll()
        }
        found.sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private fun isPairingPortOpen(host: String): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, PAIRING_PORT), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }
}
