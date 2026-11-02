package com.example.atvremote.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

object CertificateManager {

    private const val ALIAS = "atv_remote_client_key"
    private const val PROVIDER = "AndroidKeyStore"

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun ensureClientKey() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) return

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER)
        val notBefore = Date()
        val notAfter = Date(notBefore.time + 10L * 365 * 24 * 60 * 60 * 1000)

        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setKeySize(2048)
            .setCertificateSubject(X500Principal("CN=AndroidTvRemote"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    fun clientCertificate(): X509Certificate {
        ensureClientKey()
        return keyStore().getCertificate(ALIAS) as X509Certificate
    }

    fun keyManagerFactory(): KeyManagerFactory {
        ensureClientKey()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(), null)
        return kmf
    }

    fun unsigned(bytes: ByteArray): ByteArray =
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
}
