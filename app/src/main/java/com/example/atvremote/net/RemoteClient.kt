package com.example.atvremote.net

import com.example.atvremote.proto.ProtoReader
import com.example.atvremote.proto.ProtoWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class RemoteClient(
    private val host: String,
    private val pinnedServerCert: X509Certificate,
    private val port: Int = 6466
) : RemoteTransport {
    private lateinit var socket: SSLSocket
    private lateinit var out: OutputStream
    private lateinit var input: InputStream
    @Volatile private var reading = false

    private val writer = Executors.newSingleThreadExecutor()

    override var onDisconnect: (() -> Unit)? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val presented = chain?.firstOrNull() ?: error("No server certificate presented")
                check(presented.encoded.contentEquals(pinnedServerCert.encoded)) {
                    "Server certificate changed since pairing - re-pair the device."
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(CertificateManager.keyManagerFactory().keyManagers, arrayOf(trustManager), null)
        socket = (sslContext.socketFactory.createSocket(host, port) as SSLSocket).apply {
            soTimeout = 10_000
            startHandshake()
        }
        out = socket.outputStream
        input = socket.inputStream

        var sentConfigure = false
        var ready = false
        while (!ready) {
            val fields = ProtoReader.parseFields(ProtoReader.readFramedMessage(input))
            when {
                fields.any { it.number == 1 } && !sentConfigure -> {
                    rawSend(buildClientConfigureMessage())
                    sentConfigure = true
                }
                fields.any { it.number == 2 } -> {
                    rawSend(buildSetActiveMessage())
                    ready = true
                }
                fields.any { it.number == 30 } -> ready = true
                fields.any { it.number == 8 } -> respondToPing(fields)
            }
        }

        socket.soTimeout = 0
        reading = true
        startBackgroundReadLoop()
    }

    override fun sendKey(keyCode: Int, direction: Int) {
        val content = ProtoWriter.varintField(1, keyCode.toLong()) +
            ProtoWriter.varintField(2, direction.toLong())
        val frame = ProtoWriter.framed(ProtoWriter.lengthDelimitedField(10, content))
        writer.execute {
            try {
                rawSend(frame)
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        reading = false
        try {
            if (::socket.isInitialized && !socket.isClosed) socket.close()
        } catch (_: Exception) {
        }
        writer.shutdownNow()
    }

    private fun buildClientConfigureMessage(): ByteArray {
        val deviceInfo =
            ProtoWriter.stringField(1, "ATV Remote") +
                ProtoWriter.stringField(2, "Kotlin") +
                ProtoWriter.varintField(3, 1) +
                ProtoWriter.stringField(4, "1") +
                ProtoWriter.stringField(5, "com.example.atvremote") +
                ProtoWriter.stringField(6, "1.0.0")
        val configure = ProtoWriter.varintField(1, 622) +
            ProtoWriter.lengthDelimitedField(2, deviceInfo)
        return ProtoWriter.framed(ProtoWriter.lengthDelimitedField(1, configure))
    }

    private fun buildSetActiveMessage(): ByteArray =
        ProtoWriter.framed(ProtoWriter.lengthDelimitedField(2, ProtoWriter.varintField(1, 622)))

    private fun respondToPing(fields: List<ProtoReader.Field>) {
        val pingBytes = fields.firstOrNull { it.number == 8 }?.bytes ?: return
        val val1 = ProtoReader.parseFields(pingBytes).firstOrNull { it.number == 1 }?.varintValue ?: 0L
        rawSend(ProtoWriter.framed(ProtoWriter.lengthDelimitedField(9, ProtoWriter.varintField(1, val1))))
    }

    private fun startBackgroundReadLoop() {
        Thread {
            try {
                while (reading) {
                    val fields = ProtoReader.parseFields(ProtoReader.readFramedMessage(input))
                    when {
                        fields.any { it.number == 8 } -> respondToPing(fields)
                        fields.any { it.number == 1 } -> rawSend(buildClientConfigureMessage())
                        fields.any { it.number == 2 } -> rawSend(buildSetActiveMessage())
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (reading) {
                    reading = false
                    onDisconnect?.invoke()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun rawSend(framedMessage: ByteArray) {
        out.write(framedMessage)
        out.flush()
    }
}
