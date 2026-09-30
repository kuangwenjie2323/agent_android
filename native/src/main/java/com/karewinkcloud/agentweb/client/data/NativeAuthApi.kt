package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DeviceCredential(val origin: String, internal val token: String, val deviceName: String,
    val accountName: String?, val expiresAt: Long) {
    override fun toString() = "DeviceCredential([redacted])"
}

interface NativeAuthApi {
    suspend fun passkeyOptions(origin: String, deviceName: String): PasskeyChallenge = throw PasskeyException(PasskeyFailure.UNSUPPORTED)
    suspend fun verifyPasskey(origin: String, deviceName: String, challenge: PasskeyChallenge, credential: JsonObject): DeviceCredential =
        throw PasskeyException(PasskeyFailure.UNSUPPORTED)
    suspend fun exchange(grant: CodeGrant): DeviceCredential
    /** True means revoked or already unusable. False includes old servers without this route. */
    suspend fun revoke(credential: DeviceCredential): Boolean
}

class HttpNativeAuthApi(client: OkHttpClient = OkHttpClient(), private val clock: () -> Long = System::currentTimeMillis) : NativeAuthApi {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    private fun oneShotJson(value: String): RequestBody {
        val body = value.toRequestBody("application/json".toMediaType())
        return object : RequestBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            // Also disallow OkHttp's HTTP-level follow-ups (e.g. 503 Retry-After: 0),
            // which retryOnConnectionFailure(false) alone does not disable.
            override fun isOneShot() = true
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Discard transport exception causes: a URL/body may contain an authorization code.
                if (continuation.isActive) continuation.resumeWithException(SignInException("Could not complete sign-in. Start again when connected."))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    override suspend fun exchange(grant: CodeGrant): DeviceCredential {
        val body = buildJsonObject {
            put("grant_type", "authorization_code"); put("code", grant.code); put("code_verifier", grant.verifier)
            put("client_id", NATIVE_CLIENT_ID); put("redirect_uri", NATIVE_REDIRECT_URI); put("device_name", grant.deviceName)
        }
        val request = Request.Builder().url(grant.origin + "/_access/app/token")
            .post(oneShotJson(body.toString())).build()
        execute(request).use { response ->
            if (response.code != 200) throw SignInException(when (response.code) {
                400 -> "Sign-in expired or could not be verified. Start sign-in again."
                429 -> "Too many sign-in attempts. Wait a few minutes and try again."
                404 -> "Native sign-in is not available on this server yet."
                else -> "The server could not complete sign-in. Start sign-in again."
            })
            return readCredential(response, grant.origin, grant.deviceName)
        }
    }

    private fun readCredential(response: Response, origin: String, deviceName: String): DeviceCredential = try {
        val source = requireNotNull(response.body).source()
        require(!source.request(16_385))
        val json = wireJson.parseToJsonElement(source.readUtf8()).jsonObject
        val token = requireNotNull(json.string("access_token"))
        val lifetime = requireNotNull(json.long("expires_in"))
        require(Pkce.isOpaque(token) && json.string("token_type") == "Bearer" && lifetime in 1..15_552_000)
        fun clean(value: String?) = value?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
        DeviceCredential(origin, token, clean(json.obj("device")?.string("name")) ?: deviceName,
            clean(json.obj("account")?.string("name")), clock() + lifetime * 1000)
    } catch (_: Exception) { throw SignInException("The sign-in response was invalid. Start sign-in again.") }

    override suspend fun passkeyOptions(origin: String, deviceName: String): PasskeyChallenge {
        val body = buildJsonObject { put("client_id", NATIVE_CLIENT_ID); put("device_name", deviceLabel(deviceName)) }
        val request = Request.Builder().url(origin + "/_access/app/passkey/options").post(oneShotJson(body.toString())).build()
        return execute(request).use { response ->
            checkPasskeyStatus(response.code)
            try {
                val source = requireNotNull(response.body).source()
                require(!source.request(65_537))
                PasskeyChallenge.from(wireJson.parseToJsonElement(source.readUtf8()).jsonObject)
            } catch (_: Exception) { throw PasskeyException(PasskeyFailure.INVALID) }
        }
    }

    override suspend fun verifyPasskey(origin: String, deviceName: String, challenge: PasskeyChallenge, credential: JsonObject): DeviceCredential {
        val body = buildJsonObject { put("ceremony_id", challenge.ceremonyId); put("credential", credential) }
        val request = Request.Builder().url(origin + "/_access/app/passkey/verify").post(oneShotJson(body.toString())).build()
        return execute(request).use { response ->
            checkPasskeyStatus(response.code)
            readCredential(response, origin, deviceLabel(deviceName))
        }
    }

    private fun checkPasskeyStatus(status: Int) {
        when (status) {
            200 -> Unit
            404, 501 -> throw PasskeyException(PasskeyFailure.UNSUPPORTED)
            429 -> throw SignInException("Too many sign-in attempts. Wait a few minutes and try again.")
            400, 401, 403 -> throw PasskeyException(PasskeyFailure.INVALID)
            else -> throw PasskeyException(PasskeyFailure.PROVIDER)
        }
    }

    override suspend fun revoke(credential: DeviceCredential): Boolean {
        val request = Request.Builder().url(credential.origin + "/_access/app/revoke")
            .header("Authorization", "Bearer ${credential.token}")
            .post(oneShotJson("{}")).build()
        return execute(request).use { it.code == 200 || it.code == 401 }
    }
}
