package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

internal val authenticationJson = """{"id":"credential-id","rawId":"cmF3","type":"public-key","response":{"clientDataJSON":"Y2xpZW50","authenticatorData":"YXV0aA","signature":"c2ln","userHandle":null},"clientExtensionResults":{}}"""
internal fun challenge() = PasskeyChallenge.from(wireJson.parseToJsonElement("""{"ceremony_id":"synthetic-ceremony","options":{"challenge":"Y2hhbGxlbmdl","rpId":"example.test","timeout":60000,"userVerification":"required"}}""").jsonObject)

class PasskeyAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpNativeAuthApi
    private lateinit var origin: String
    @Before fun setup() { server = MockWebServer().also { it.start() }; origin = server.url("/").toString().trimEnd('/'); api = HttpNativeAuthApi(clock = { 1000 }) }
    @After fun close() { server.shutdown() }
    private fun response(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun next() = server.takeRequest(2, TimeUnit.SECONDS)!!

    @Test fun optionsPostsOnlyClientAndDeviceAndPreservesWebAuthnOptions() = runBlocking {
        server.enqueue(response("""{"ceremony_id":"ceremony","options":{"challenge":"YQ","allowCredentials":[],"rpId":"example.test","userVerification":"required","extensions":{"x":true}}}"""))
        val challenge = api.passkeyOptions(origin, "Phone\nX")
        val request = next()
        assertEquals("POST", request.method); assertEquals("/_access/app/passkey/options", request.path)
        assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
        val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("client_id", "device_name"), body.keys)
        assertEquals(NATIVE_CLIENT_ID, body.string("client_id")); assertEquals("Phone X", body.string("device_name"))
        assertEquals("ceremony", challenge.ceremonyId); assertEquals(true, challenge.options.obj("extensions")?.boolean("x"))
        assertFalse(challenge.toString().contains("ceremony"))
    }
    @Test fun verifyEmbedsCredentialObjectAndUsesExistingEncryptedStore() = runBlocking {
        val token = Pkce.randomValue()
        server.enqueue(response("""{"access_token":"$token","token_type":"Bearer","expires_in":60,"account":{"name":"账户"},"device":{"name":"Phone"}}"""))
        val credential = passkeyCredential(authenticationJson)
        val grant = api.verifyPasskey(origin, "Test phone", challenge(), credential)
        val request = next()
        assertEquals("/_access/app/passkey/verify", request.path)
        val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("ceremony_id", "credential"), body.keys)
        assertEquals("synthetic-ceremony", body.string("ceremony_id")); assertEquals(credential, body.obj("credential"))
        assertEquals("账户", grant.accountName); assertEquals("Phone", grant.deviceName); assertEquals(61_000, grant.expiresAt)
        val persistence = FakeCredentialPersistence()
        val store = CredentialStore(persistence, FakeCredentialCipher(), clock = { 1000 })
        store.install(grant)
        assertEquals(token, store.tokenFor(origin)); assertFalse(persistence.value!!.ciphertext.contains(token))
    }
    @Test fun malformedOptionsAreSanitized() = runBlocking {
        for (body in listOf("not json", "{}", """{"ceremony_id":"id","options":{}}""", "x".repeat(65_537))) {
            server.enqueue(response(body))
            try { api.passkeyOptions(origin, "Phone"); fail() } catch (e: PasskeyException) { assertEquals(PasskeyFailure.INVALID, e.failure); assertNull(e.cause) }
        }
    }
    @Test fun allServerFailureClassesAreMappedWithoutRetry() = runBlocking {
        for ((status, failure) in listOf(400 to PasskeyFailure.INVALID, 401 to PasskeyFailure.INVALID, 403 to PasskeyFailure.INVALID,
            404 to PasskeyFailure.UNSUPPORTED, 501 to PasskeyFailure.UNSUPPORTED, 503 to PasskeyFailure.PROVIDER)) {
            server.enqueue(response("sensitive body", status).setHeader("Retry-After", "0"))
            try { api.verifyPasskey(origin, "Phone", challenge(), passkeyCredential(authenticationJson)); fail() }
            catch (e: PasskeyException) { assertEquals(failure, e.failure); assertFalse(e.toString().contains("sensitive")) }
        }
        assertEquals(6, server.requestCount)
    }
    @Test fun rateLimitUsesExistingClearMessage() = runBlocking {
        server.enqueue(response("{}", 429))
        try { api.passkeyOptions(origin, "Phone"); fail() } catch (e: SignInException) { assertTrue(e.message!!.contains("Too many")) }
    }
    @Test fun ambiguousVerifyNeverReplaysOrFollowsRedirect() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { api.verifyPasskey(origin, "Phone", challenge(), passkeyCredential(authenticationJson)); fail() } catch (_: SignInException) { }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
        try { api.passkeyOptions(origin, "Phone"); fail() } catch (_: PasskeyException) { }
        assertEquals(2, server.requestCount)
    }
    @Test fun authenticatorMappingPreservesNullExtensionsAndRejectsInvalidShapes() {
        val value = passkeyCredential(authenticationJson)
        assertEquals(JsonNull, value.obj("response")!!["userHandle"])
        assertEquals(JsonObject(emptyMap()), value.obj("clientExtensionResults"))
        for (bad in listOf("{}", "[]", authenticationJson.replace("public-key", "password"), authenticationJson.replace("c2ln", ""), "x".repeat(65_537))) {
            try { passkeyCredential(bad); fail() } catch (e: PasskeyException) { assertEquals(PasskeyFailure.INVALID, e.failure) }
        }
    }
    @Test fun eachFailureSelectsExplicitBrowserFallbackWithAFixedMessageKey() {
        assertEquals(SignInMethod.PASSKEY, signInMethod(null))
        val keys = PasskeyFailure.entries.map { failure ->
            assertEquals(SignInMethod.BROWSER, signInMethod(failure)); passkeyMessage(failure)
        }
        assertEquals(PasskeyFailure.entries.size, keys.toSet().size)
    }
}
