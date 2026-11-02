package com.example.atvremote.net

import com.example.atvremote.proto.ProtoReader
import com.example.atvremote.proto.ProtoWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class PairingClient(private val host: String, private val port: Int = 6467) {

    var serverCertificate: X509Certificate? = null
        private set

    private lateinit var socket: SSLSocket
    private lateinit var out: OutputStream
    private lateinit var input: InputStream

    suspend fun connectAndRequestPin(clientName: String = "ATV Remote (Kotlin)") =
        withContext(Dispatchers.IO) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    serverCertificate = chain?.firstOrNull()
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(CertificateManager.keyManagerFactory().keyManagers, arrayOf(trustManager), null)

            socket = (sslContext.socketFactory.createSocket(host, port) as SSLSocket).apply {
                startHandshake()
            }
            out = socket.outputStream
            input = socket.inputStream

            send(buildPairingRequest("com.example.atvremote", clientName))
            readAck()

            send(buildOptionsMessage())
            readAck()

            send(buildConfigurationMessage())
            readAck()
        }

    suspend fun submitPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        val serverCert = serverCertificate ?: error("Not connected yet")
        val code = pin.trim().uppercase()
        if (code.length < 4 || code.any { Character.digit(it, 16) < 0 }) return@withContext false
        val secretHash = computeSecretHash(CertificateManager.clientCertificate(), serverCert, code)
        if ((secretHash[0].toInt() and 0xFF) != code.substring(0, 2).toInt(16)) {
            return@withContext false
        }
        send(buildSecretMessage(secretHash))
        val ackBody = ProtoReader.readFramedMessage(input)
        val status = ProtoReader.parseFields(ackBody).firstOrNull { it.number == 2 }?.varintValue
        socket.close()
        status == 200L
    }

    fun close() {
        if (::socket.isInitialized && !socket.isClosed) socket.close()
    }

    private fun header(): ByteArray =
        ProtoWriter.varintField(1, 2) + ProtoWriter.varintField(2, 200)

    private fun buildPairingRequest(serviceName: String, clientName: String): ByteArray {
        val inner = ProtoWriter.stringField(1, serviceName) + ProtoWriter.stringField(2, clientName)
        val body = header() + ProtoWriter.lengthDelimitedField(10, inner)
        return ProtoWriter.framed(body)
    }

    private fun buildOptionsMessage(): ByteArray {
        val encodingOption = ProtoWriter.varintField(1, 3) + ProtoWriter.varintField(2, 6)
        val optionsContent = ProtoWriter.lengthDelimitedField(1, encodingOption) +
            ProtoWriter.varintField(3, 1)
        val body = header() + ProtoWriter.lengthDelimitedField(20, optionsContent)
        return ProtoWriter.framed(body)
    }

    private fun buildConfigurationMessage(): ByteArray {
        val encodingOption = ProtoWriter.varintField(1, 3) + ProtoWriter.varintField(2, 6)
        val configContent = ProtoWriter.lengthDelimitedField(1, encodingOption) +
            ProtoWriter.varintField(2, 1)
        val body = header() + ProtoWriter.lengthDelimitedField(30, configContent)
        return ProtoWriter.framed(body)
    }

    private fun buildSecretMessage(secretHash: ByteArray): ByteArray {
        val secretContent = ProtoWriter.bytesField(1, secretHash)
        val body = header() + ProtoWriter.lengthDelimitedField(40, secretContent)
        return ProtoWriter.framed(body)
    }

    private fun computeSecretHash(
        clientCert: X509Certificate,
        serverCert: X509Certificate,
        pin: String
    ): ByteArray {
        val clientPub = clientCert.publicKey as RSAPublicKey
        val serverPub = serverCert.publicKey as RSAPublicKey
        val md = MessageDigest.getInstance("SHA-256")
        md.update(CertificateManager.unsigned(clientPub.modulus.toByteArray()))
        md.update(CertificateManager.unsigned(clientPub.publicExponent.toByteArray()))
        md.update(CertificateManager.unsigned(serverPub.modulus.toByteArray()))
        md.update(CertificateManager.unsigned(serverPub.publicExponent.toByteArray()))
        val nonceHex = pin.substring(2)
        val nonce = ByteArray(nonceHex.length / 2) { idx ->
            val hi = Character.digit(nonceHex[idx * 2], 16)
            val lo = Character.digit(nonceHex[idx * 2 + 1], 16)
            ((hi shl 4) or lo).toByte()
        }
        md.update(nonce)
        return md.digest()
    }

    private fun send(framedMessage: ByteArray) {
        out.write(framedMessage)
        out.flush()
    }

    private fun readAck() {
        ProtoReader.readFramedMessage(input)
    }
}
