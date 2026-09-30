package com.karewinkcloud.agentweb.client.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

const val NATIVE_CLIENT_ID = "agentweb-android"
const val NATIVE_REDIRECT_URI = "com.karewinkcloud.agentweb.client:/auth"

/** Only fixed, local messages may cross into UI/crash diagnostics. */
class SignInException(message: String) : Exception(message)

object Pkce {
    private val random = SecureRandom()
    fun randomValue(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun matches(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))
    fun isOpaque(value: String): Boolean = try {
        value.length == 43 && Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)) == value
    } catch (_: IllegalArgumentException) { false }
}

fun deviceLabel(model: String): String {
    val clean = StringBuilder()
    model.codePoints().limit(128).forEach { cp ->
        if (Character.isLetter(cp) || Character.getType(cp) in setOf(
                Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt()) ||
            cp in " -_().’'".map { it.code }) clean.appendCodePoint(cp) else clean.append(' ')
    }
    val collapsed = clean.toString().trim().replace(Regex(" +"), " ")
    return collapsed.codePoints().limit(64).toArray().let { String(it, 0, it.size) }.trim().ifEmpty { "Android phone" }
}

// Deliberately not data classes: generated toString/copy must not expose credentials.
class CodeGrant internal constructor(
    val origin: String, internal val code: String, internal val verifier: String, val deviceName: String,
) { override fun toString() = "CodeGrant([redacted])" }

/** Process-private, one-use pending operation; never saved to a Bundle or disk. */
class NativeSignInFlow(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Pending(val origin: String, val state: String, val verifier: String, val device: String, val created: Long)
    private var pending: Pending? = null

    @Synchronized fun begin(origin: String, model: String): String {
        val attempt = Pending(origin, Pkce.randomValue(), Pkce.randomValue(), deviceLabel(model), clock())
        pending = attempt
        return origin.toHttpUrl().newBuilder().addPathSegments("_access/app/authorize")
            .addQueryParameter("client_id", NATIVE_CLIENT_ID).addQueryParameter("redirect_uri", NATIVE_REDIRECT_URI)
            .addQueryParameter("code_challenge", Pkce.challenge(attempt.verifier)).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", attempt.state).addQueryParameter("device_name", attempt.device).build().toString()
    }

    @Synchronized fun cancel() { pending = null }

    @Synchronized fun consume(callback: String, origin: String): CodeGrant {
        val attempt = pending
        pending = null // Even a rejected delivery cannot be replayed against this operation.
        val invalid = "Sign-in callback was not accepted. Start sign-in again."
        if (attempt == null || attempt.origin != origin || clock() - attempt.created !in 0..600_000) throw SignInException(invalid)
        val fields = try {
            require(callback.length <= 2048)
            val uri = URI(callback)
            require(callback.substringBefore('?') == NATIVE_REDIRECT_URI && uri.rawFragment == null && uri.rawAuthority == null)
            val query = requireNotNull(uri.rawQuery)
            val pairs = query.split('&').map {
                val parts = it.split('=', limit = 2)
                require(parts.size == 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
            require(pairs.size == 2 && pairs.map { it.first }.toSet().size == pairs.size)
            pairs.toMap().also { require(it.keys == setOf("code", "state") || it.keys == setOf("error", "state")) }
        } catch (_: Exception) { throw SignInException(invalid) }
        val state = fields.getValue("state")
        if (!Regex("[A-Za-z0-9._~-]{32,128}").matches(state) || !Pkce.matches(attempt.state, state)) throw SignInException(invalid)
        if (fields["error"] != null) throw SignInException(
            if (fields["error"] == "access_denied") "Sign-in was cancelled. You can try again." else "Sign-in failed. Start sign-in again.")
        val code = fields.getValue("code")
        if (!Pkce.isOpaque(code)) throw SignInException(invalid)
        return CodeGrant(attempt.origin, code, attempt.verifier, attempt.device)
    }
}
