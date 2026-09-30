package com.karewinkcloud.agentweb.client.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidCredentialCipher : CredentialCipher {
    private val alias = "agentweb-native-sign-in-v2"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        (store().getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Sign-in key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun encrypt(plaintext: ByteArray): EncryptedCredential {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        return EncryptedCredential(Base64.getEncoder().encodeToString(cipher.iv), Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)))
    }
    override fun decrypt(value: EncryptedCredential): ByteArray {
        val iv = Base64.getDecoder().decode(value.iv)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(Base64.getDecoder().decode(value.ciphertext))
    }
    override fun eraseKey() { store().deleteEntry(alias) }
}

class AndroidCredentialPersistence(context: Context) : CredentialPersistence {
    private val prefs = context.getSharedPreferences("native-credentials", Context.MODE_PRIVATE)
    override fun read(): EncryptedCredential? {
        val ciphertext = prefs.getString("ciphertext", null) ?: return null
        return EncryptedCredential(requireNotNull(prefs.getString("iv", null)), ciphertext)
    }
    override fun write(value: EncryptedCredential) {
        check(prefs.edit().clear().putString("iv", value.iv).putString("ciphertext", value.ciphertext).commit()) { "Unable to save sign-in." }
    }
    override fun clear() { check(prefs.edit().clear().commit()) { "Unable to clear sign-in." } }
}
