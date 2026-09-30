package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

class EncryptedCredential(val iv: String, val ciphertext: String)
interface CredentialCipher {
    fun encrypt(plaintext: ByteArray): EncryptedCredential
    fun decrypt(value: EncryptedCredential): ByteArray
    fun eraseKey()
}
interface CredentialPersistence {
    fun read(): EncryptedCredential?
    fun write(value: EncryptedCredential)
    fun clear()
}
data class CredentialStatus(val origin: String? = null, val accountName: String? = null, val deviceName: String? = null,
    val message: String? = null, val revision: Long = 0) {
    val signedIn get() = origin != null
}

val signInRequiredError get() = ClientError("Your session has ended. Sign in again in Settings.", "sign_in_required", false)

/** Synchronized so a late 401 for an old token cannot erase a newly signed-in account. */
class CredentialStore(private val persistence: CredentialPersistence, private val cipher: CredentialCipher,
    private val clock: () -> Long = System::currentTimeMillis) : TokenProvider {
    private var credential: DeviceCredential? = null
    private val mutable = MutableStateFlow(CredentialStatus())
    val status = mutable.asStateFlow()

    init {
        try {
            persistence.read()?.let { encrypted ->
                val bytes = cipher.decrypt(encrypted)
                try {
                    val data = wireJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    val origin = requireNotNull(data.string("origin"))
                    val token = requireNotNull(data.string("token"))
                    require(normalizeBaseUrl(origin, true) == origin && Pkce.isOpaque(token))
                    credential = DeviceCredential(origin, token, requireNotNull(data.string("device")), data.string("account"), requireNotNull(data.long("expires")))
                } finally { bytes.fill(0) }
                if (credential!!.expiresAt <= clock()) clear("Your session has expired. Sign in again.") else publish()
            }
        } catch (_: Exception) { clear("Saved sign-in could not be unlocked. Sign in again.") }
    }

    private fun publish(message: String? = null) {
        mutable.value = CredentialStatus(credential?.origin, credential?.accountName, credential?.deviceName, message, mutable.value.revision + 1)
    }

    @Synchronized fun install(value: DeviceCredential) {
        require(Pkce.isOpaque(value.token) && normalizeBaseUrl(value.origin, true) == value.origin && value.expiresAt > clock()) { "Invalid sign-in response." }
        val bytes = buildJsonObject {
            put("origin", value.origin); put("token", value.token); put("device", value.deviceName)
            value.accountName?.let { put("account", it) }; put("expires", value.expiresAt)
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            persistence.write(cipher.encrypt(bytes))
            credential = value
            publish()
        } catch (_: Exception) {
            clear("Sign-in could not be saved securely. Sign in again.")
            throw SignInException("Sign-in could not be saved securely. Sign in again.")
        } finally { bytes.fill(0) }
    }

    @Synchronized fun clear(message: String? = null) {
        credential = null
        // Also destroy the key: an unsuccessful disk erase cannot revive an old ciphertext.
        try { persistence.clear() } catch (_: Exception) { }
        try { cipher.eraseKey() } catch (_: Exception) { }
        publish(message)
    }

    @Synchronized fun current(): DeviceCredential? {
        if (credential?.expiresAt?.let { it <= clock() } == true) clear("Your session has expired. Sign in again.")
        return credential
    }

    @Synchronized override fun tokenFor(origin: String): String? = current()?.takeIf { it.origin == origin }?.token

    @Synchronized override fun unauthorized(origin: String, rejectedToken: String?) {
        val current = credential
        if (current != null && current.origin == origin && current.token == rejectedToken) clear(signInRequiredError.message)
    }
}
