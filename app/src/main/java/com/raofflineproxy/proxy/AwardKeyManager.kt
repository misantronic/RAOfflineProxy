package com.raofflineproxy.proxy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

private const val TAG = "RAProxy/AwardKeyManager"
private const val KEY_ALIAS = "ra_proxy_award_key"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

object AwardKeyManager {

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }

    private fun ensureKey() {
        if (loadKeyStore().containsAlias(KEY_ALIAS)) return

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .also { it.initialize(spec) }
            .generateKeyPair()

        Log.i(TAG, "ECDSA P-256 key pair generated in AndroidKeyStore")
    }

    fun sign(data: ByteArray): ByteArray {
        ensureKey()
        val privateKey = loadKeyStore().getKey(KEY_ALIAS, null)
        return Signature.getInstance("SHA256withECDSA")
            .also {
                it.initSign(privateKey as java.security.PrivateKey)
                it.update(data)
            }
            .sign()
    }

    fun getPublicKeyBase64(): String {
        ensureKey()
        val cert = loadKeyStore().getCertificate(KEY_ALIAS)
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }
}
