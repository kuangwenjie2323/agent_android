package com.karewinkcloud.agentweb.client.data

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** JVM implementation of the Keystore port, using real AES-GCM and disposable keys. */
class FakeCredentialCipher : CredentialCipher {
    var key: SecretKey? = null
    var failEncrypt = false
    override fun encrypt(plaintext: ByteArray): EncryptedCredential {
        check(!failEncrypt)
        if (key == null) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return EncryptedCredential(Base64.getEncoder().encodeToString(cipher.iv), Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)))
    }
    override fun decrypt(value: EncryptedCredential): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, requireNotNull(key), GCMParameterSpec(128, Base64.getDecoder().decode(value.iv)))
        doFinal(Base64.getDecoder().decode(value.ciphertext))
    }
    override fun eraseKey() { key = null }
}
class FakeCredentialPersistence : CredentialPersistence {
    var value: EncryptedCredential? = null
    var failWrite = false
    override fun read() = value
    override fun write(value: EncryptedCredential) { check(!failWrite); this.value = value }
    override fun clear() { value = null }
}
class FakeSettingsStorage : SettingsStorage {
    val persistence = FakeCredentialPersistence()
    val cipher = FakeCredentialCipher()
    override val credentials = CredentialStore(persistence, cipher)
    var preferences = Preferences()
    override fun load() = preferences.copy(hasToken = credentials.status.value.origin == preferences.origin)
    override fun save(origin: String, theme: String) {
        val normalized = normalizeBaseUrl(origin, true)
        if (normalized != preferences.origin) credentials.clear()
        preferences = preferences.copy(origin = normalized, theme = theme)
    }
    override fun saveLanguage(language: String) { require(language in setOf("system", "zh", "en")); preferences = preferences.copy(language = language) }
    override fun saveChoice(choice: com.karewinkcloud.agentweb.client.core.ModelChoice) { preferences = preferences.copy(choice = choice) }
    override fun tokenFor(origin: String) = credentials.tokenFor(origin)
    override fun unauthorized(origin: String, rejectedToken: String?) = credentials.unauthorized(origin, rejectedToken)
}
fun fakeCredential(origin: String = "https://agent.karewinkcloud.com", token: String = Pkce.randomValue(), expires: Long = System.currentTimeMillis() + 60_000) =
    DeviceCredential(origin, token, "Test phone", "Test account", expires)
