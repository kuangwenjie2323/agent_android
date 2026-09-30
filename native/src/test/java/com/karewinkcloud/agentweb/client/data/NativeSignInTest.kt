package com.karewinkcloud.agentweb.client.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NativeSignInTest {
    private val origin = "https://agent.karewinkcloud.com"
    private fun rejected(flow: NativeSignInFlow, value: String) {
        try { flow.consume(value, origin); fail("Callback must be rejected") }
        catch (e: SignInException) { assertFalse(e.message!!.contains(value)); assertNull(e.cause) }
    }
    @Test fun pkceUsesRfc7636S256Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun pkceAndStateAreFreshCanonical256BitValues() {
        val values = (1..100).map { Pkce.randomValue() }
        assertEquals(100, values.toSet().size)
        assertTrue(values.all { Pkce.isOpaque(it) && Regex("[A-Za-z0-9_-]{43}").matches(it) })
        assertFalse(Pkce.isOpaque("!".repeat(43)))
        assertFalse(Pkce.matches(values[0], values[1]))
        assertTrue(Pkce.matches(values[0], values[0]))
    }
    @Test fun authorizationIsExactlyBoundAndVerifierNeverEntersBrowser() {
        val flow = NativeSignInFlow()
        val url = flow.begin(origin, "  Pixel 📱\n10\u202e ").toHttpUrl()
        assertEquals("/_access/app/authorize", url.encodedPath)
        assertEquals(setOf("client_id", "redirect_uri", "code_challenge", "code_challenge_method", "state", "device_name"), url.queryParameterNames)
        assertEquals(NATIVE_CLIENT_ID, url.queryParameter("client_id"))
        assertEquals(NATIVE_REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("Pixel 10", url.queryParameter("device_name"))
        val grant = flow.consume("$NATIVE_REDIRECT_URI?code=${Pkce.randomValue()}&state=${url.queryParameter("state")}", origin)
        assertEquals(url.queryParameter("code_challenge"), Pkce.challenge(grant.verifier))
        assertFalse(url.toString().contains(grant.verifier))
        assertFalse(grant.toString().contains(grant.code))
    }
    @Test fun callbackSuccessIsSingleUse() {
        val flow = NativeSignInFlow()
        val state = flow.begin(origin, "Phone").toHttpUrl().queryParameter("state")
        val callback = "$NATIVE_REDIRECT_URI?state=$state&code=${Pkce.randomValue()}"
        assertEquals(origin, flow.consume(callback, origin).origin)
        rejected(flow, callback)
    }
    @Test fun wrongStateConsumesPendingAttempt() {
        val flow = NativeSignInFlow()
        val state = flow.begin(origin, "Phone").toHttpUrl().queryParameter("state")
        rejected(flow, "$NATIVE_REDIRECT_URI?state=${Pkce.randomValue()}&code=${Pkce.randomValue()}")
        rejected(flow, "$NATIVE_REDIRECT_URI?state=$state&code=${Pkce.randomValue()}")
    }
    @Test fun denialIsVerifiedAndSingleUse() {
        val flow = NativeSignInFlow()
        val state = flow.begin(origin, "Phone").toHttpUrl().queryParameter("state")
        val callback = "$NATIVE_REDIRECT_URI?state=$state&error=access_denied"
        try { flow.consume(callback, origin); fail() } catch (e: SignInException) { assertTrue(e.message!!.contains("cancelled")) }
        rejected(flow, callback)
    }
    @Test fun unexpectedUriDuplicateMissingAndMixedFieldsFailClosed() {
        val invalid = listOf(
            "com.karewinkcloud.agentweb.client://auth?state=STATE&code=CODE",
            "com.karewinkcloud.agentweb.client:///auth?state=STATE&code=CODE",
            "com.karewinkcloud.agentweb.client:/auth/extra?state=STATE&code=CODE",
            "com.karewinkcloud.agentweb.client:/%61uth?state=STATE&code=CODE",
            "https://evil.test/auth?state=STATE&code=CODE",
            "$NATIVE_REDIRECT_URI?state=STATE&code=CODE#fragment",
            "$NATIVE_REDIRECT_URI?state=STATE&code=CODE&state=STATE",
            "$NATIVE_REDIRECT_URI?state=STATE&code=CODE&%73tate=STATE",
            "$NATIVE_REDIRECT_URI?state=STATE&code=CODE&error=access_denied",
            "$NATIVE_REDIRECT_URI?state=STATE&code=CODE&extra=x",
            "$NATIVE_REDIRECT_URI?code=CODE", "$NATIVE_REDIRECT_URI?state=STATE&code=%ZZ",
        )
        invalid.forEach { value ->
            val flow = NativeSignInFlow()
            val state = flow.begin(origin, "Phone").toHttpUrl().queryParameter("state")!!
            rejected(flow, value.replace("STATE", state).replace("CODE", Pkce.randomValue()))
        }
    }
    @Test fun processLossCancellationExpiryAndOriginChangeRejectDelivery() {
        var now = 0L
        val flow = NativeSignInFlow { now }
        fun begin() = flow.begin(origin, "Phone").toHttpUrl().queryParameter("state")!!
        var state = begin()
        rejected(NativeSignInFlow(), "$NATIVE_REDIRECT_URI?state=$state&code=${Pkce.randomValue()}")
        flow.cancel()
        rejected(flow, "$NATIVE_REDIRECT_URI?state=$state&code=${Pkce.randomValue()}")
        state = begin(); now = 600_001
        rejected(flow, "$NATIVE_REDIRECT_URI?state=$state&code=${Pkce.randomValue()}")
        state = begin()
        try { flow.consume("$NATIVE_REDIRECT_URI?state=$state&code=${Pkce.randomValue()}", "https://other.test"); fail() }
        catch (_: SignInException) { }
    }
    @Test fun deviceNamesPreserveUnicodeAndBoundCodePoints() {
        assertEquals("Android phone", deviceLabel("📱\n\u202e"))
        assertEquals("我的 Phone (2)", deviceLabel("我的 Phone (2)"))
        assertEquals(64, deviceLabel("字".repeat(100)).length)
    }
}
