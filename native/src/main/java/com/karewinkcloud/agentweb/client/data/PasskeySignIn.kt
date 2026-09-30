package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.serialization.json.*

enum class SignInMethod { PASSKEY, BROWSER }
enum class PasskeyFailure { NO_CREDENTIAL, CANCELLED, UNSUPPORTED, PROVIDER, INVALID }
class PasskeyException(val failure: PasskeyFailure) : Exception(failure.name)

// No generated toString: ceremonies and authenticator JSON must not reach diagnostics.
class PasskeyChallenge(val ceremonyId: String, val options: JsonObject) {
    companion object {
        fun from(value: JsonObject): PasskeyChallenge {
            val id = requireNotNull(value.string("ceremony_id"))
            require(id.isNotBlank() && id.length <= 512 && id.none(Char::isISOControl))
            val options = requireNotNull(value.obj("options"))
            require(!options.string("challenge").isNullOrBlank())
            return PasskeyChallenge(id, options)
        }
    }
    override fun toString() = "PasskeyChallenge([redacted])"
}

/** The Activity-owned Credential Manager UI is injectable; the ViewModel retains no Activity. */
fun interface PasskeyProvider {
    suspend fun authenticate(requestJson: String): String
}

fun passkeyCredential(json: String): JsonObject = try {
    require(json.length <= 65_536)
    wireJson.parseToJsonElement(json).jsonObject.also {
        require(it.string("type") == "public-key" && !it.string("id").isNullOrBlank())
        val response = requireNotNull(it.obj("response"))
        for (key in listOf("clientDataJSON", "authenticatorData", "signature")) require(!response.string(key).isNullOrBlank())
    }
} catch (_: Exception) { throw PasskeyException(PasskeyFailure.INVALID) }

/** Fallback is offered after a failure; only a deliberate tap opens the browser. */
fun signInMethod(failure: PasskeyFailure?): SignInMethod = if (failure == null) SignInMethod.PASSKEY else SignInMethod.BROWSER

// Fixed keys only. Never surface a provider exception's message or cause.
fun passkeyMessage(failure: PasskeyFailure): String = when (failure) {
    PasskeyFailure.NO_CREDENTIAL -> "passkey_none"
    PasskeyFailure.CANCELLED -> "passkey_cancelled"
    PasskeyFailure.UNSUPPORTED -> "passkey_unsupported"
    PasskeyFailure.PROVIDER -> "passkey_provider"
    PasskeyFailure.INVALID -> "passkey_invalid"
}
