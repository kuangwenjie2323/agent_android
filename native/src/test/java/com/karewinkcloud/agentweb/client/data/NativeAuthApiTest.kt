package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class NativeAuthApiTest {
    private lateinit var server: MockWebServer
    private lateinit var origin: String
    private lateinit var api: HttpNativeAuthApi
    private lateinit var grant: CodeGrant
    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        origin = server.url("/").toString().trimEnd('/')
        api = HttpNativeAuthApi()
        grant = CodeGrant(origin, Pkce.randomValue(), Pkce.randomValue(), "Test phone")
    }
    @After fun cleanup() { server.shutdown() }
    private fun json(value: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(value)
    private fun success(token: String) = json("""{"access_token":"$token","token_type":"Bearer","expires_in":15552000,"account":{"name":"Test account"},"device":{"id":"app_test","name":"Test phone"}}""")
    private fun next() = server.takeRequest(2, TimeUnit.SECONDS)!!

    @Test fun exchangeBindsAllFieldsAndReturnsDisplayMetadata() = runBlocking {
        val token = Pkce.randomValue()
        server.enqueue(success(token))
        val result = api.exchange(grant)
        assertEquals(token, result.token); assertEquals(origin, result.origin)
        assertEquals("Test account", result.accountName); assertEquals("Test phone", result.deviceName)
        val request = next()
        assertEquals("POST", request.method); assertEquals("/_access/app/token", request.path)
        assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("grant_type", "code", "code_verifier", "client_id", "redirect_uri", "device_name"), body.keys)
        assertEquals(grant.verifier, body.string("code_verifier")); assertEquals(grant.code, body.string("code"))
        assertEquals(NATIVE_CLIENT_ID, body.string("client_id")); assertEquals(NATIVE_REDIRECT_URI, body.string("redirect_uri"))
    }
    @Test fun oldServerWithoutDisplayMetadataStillSupportsSignIn() = runBlocking {
        server.enqueue(json("""{"access_token":"${Pkce.randomValue()}","token_type":"Bearer","expires_in":100}"""))
        assertNull(api.exchange(grant).accountName)
    }
    @Test fun invalidGrantIsSanitizedAndNeverRetried() = runBlocking {
        server.enqueue(json("""{"error":"${grant.code}","message":"${grant.verifier}","code":"invalid_grant"}""", 400))
        try { api.exchange(grant); fail() } catch (e: SignInException) {
            assertTrue(e.message!!.contains("Start sign-in again"))
            assertFalse(e.toString().contains(grant.code)); assertFalse(e.toString().contains(grant.verifier)); assertNull(e.cause)
        }
        assertEquals(1, server.requestCount)
    }
    @Test fun ambiguousNetworkFailureDoesNotReplayExchange() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(success(Pkce.randomValue()))
        try { api.exchange(grant); fail() } catch (e: SignInException) {
            // Coroutine stack recovery can add a copy of our already-sanitized exception.
            assertTrue(e.cause == null || e.cause is SignInException)
            assertFalse(e.toString().contains(grant.code))
        }
        assertEquals(1, server.requestCount)
    }
    @Test fun serviceUnavailableWithRetryAfterDoesNotReplayExchange() = runBlocking {
        server.enqueue(json("{}", 503).setHeader("Retry-After", "0"))
        server.enqueue(success(Pkce.randomValue()))
        try { api.exchange(grant); fail() } catch (_: SignInException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun tokenAndRevokeDoNotFollowRedirects() = runBlocking {
        MockWebServer().use { other ->
            other.start()
            other.enqueue(success(Pkce.randomValue()))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal")))
            try { api.exchange(grant); fail() } catch (_: SignInException) { }
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal")))
            assertFalse(api.revoke(fakeCredential(origin)))
            assertEquals(0, other.requestCount)
        }
    }
    @Test fun browserCookiesAreNeitherReadNorStored() = runBlocking {
        var cookieCalls = 0
        val cookieJar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> { cookieCalls++; return emptyList() }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cookieCalls++ }
        }
        api = HttpNativeAuthApi(OkHttpClient.Builder().cookieJar(cookieJar).build())
        server.enqueue(success(Pkce.randomValue()).setHeader("Set-Cookie", "browser=synthetic"))
        api.exchange(grant)
        assertEquals(0, cookieCalls); assertNull(next().getHeader("Cookie"))
    }
    @Test fun invalidAndOversizedResponsesAreSanitized() = runBlocking {
        for (body in listOf("not json " + grant.code, "x".repeat(16_385), """{"access_token":"bad","token_type":"Bearer","expires_in":1}""")) {
            server.enqueue(json(body))
            try { api.exchange(grant); fail() } catch (e: SignInException) {
                assertFalse(e.toString().contains(grant.code)); assertNull(e.cause)
            }
        }
    }
    @Test fun revokeUsesOnlyBearerAndRecognizesAlreadyExpiredOrUnsupported() = runBlocking {
        val credential = fakeCredential(origin)
        for ((code, confirmed) in listOf(200 to true, 401 to true, 404 to false)) {
            server.enqueue(json("{}", code))
            assertEquals(confirmed, api.revoke(credential))
            val request = next()
            assertEquals("/_access/app/revoke", request.path); assertEquals("POST", request.method)
            assertEquals("Bearer ${credential.token}", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie")); assertEquals("{}", request.body.readUtf8())
        }
    }
    @Test fun api401ClearsTokenAndLatchesAllLaterRequestsWithoutReadingErrorBody() = runBlocking {
        val store = CredentialStore(FakeCredentialPersistence(), FakeCredentialCipher())
        val value = fakeCredential(origin)
        store.install(value)
        val repository = HttpAgentRepository(origin, store)
        server.enqueue(json("""{"error":"${value.token}"}""", 401))
        try { repository.agents(); fail() } catch (e: ApiException) {
            assertEquals("sign_in_required", e.problem.code); assertEquals(false, e.problem.retryable)
            assertFalse(e.toString().contains(value.token))
        }
        assertNull(store.tokenFor(origin)); assertTrue(repository.authenticationRequired.value)
        try { repository.conversations(); fail() } catch (e: ApiException) { assertEquals(401, e.status) }
        assertEquals(1, server.requestCount)
    }
    @Test fun stream401DoesNotReconnectOrRepeatPost() = runBlocking {
        val store = CredentialStore(FakeCredentialPersistence(), FakeCredentialCipher())
        store.install(fakeCredential(origin))
        val repository = HttpAgentRepository(origin, store, reconnectDelayMs = 0)
        server.enqueue(json("{}", 401))
        try { repository.follow("conversation", null, SendRequest("conversation", "work", ModelChoice())).toList(); fail() }
        catch (e: ApiException) { assertEquals(401, e.status); assertTrue(e.directSendRejected) }
        assertEquals(1, server.requestCount); assertNull(store.current())
    }
    @Test fun exchangeCredentialFeedsExistingApiTransport() = runBlocking {
        val value = Pkce.randomValue()
        server.enqueue(success(value)); server.enqueue(json("""{"agents":[]}"""))
        val store = CredentialStore(FakeCredentialPersistence(), FakeCredentialCipher())
        store.install(api.exchange(grant))
        HttpAgentRepository(origin, store).agents()
        next()
        assertEquals("Bearer $value", next().getHeader("Authorization"))
    }
}
