package com.karewinkcloud.agentweb.client.data

import org.junit.Assert.*
import org.junit.Test

class CredentialStoreTest {
    @Test fun onlyCiphertextPersistsAndRestartRemainsOriginBound() {
        val persistence = FakeCredentialPersistence()
        val cipher = FakeCredentialCipher()
        val store = CredentialStore(persistence, cipher)
        val value = fakeCredential()
        store.install(value)
        assertFalse(persistence.value!!.ciphertext.contains(value.token))
        assertFalse(persistence.value!!.ciphertext.contains(value.accountName!!))
        val restored = CredentialStore(persistence, cipher)
        assertEquals(value.token, restored.tokenFor(value.origin))
        assertNull(restored.tokenFor("https://other.test"))
        assertEquals(value.accountName, restored.status.value.accountName)
        assertEquals(value.deviceName, restored.status.value.deviceName)
        assertFalse(value.toString().contains(value.token))
        assertFalse(restored.status.value.toString().contains(value.token))
    }
    @Test fun lostKeystoreKeyWipesCiphertextAndRequestsFreshSignIn() {
        val persistence = FakeCredentialPersistence()
        val cipher = FakeCredentialCipher()
        CredentialStore(persistence, cipher).install(fakeCredential())
        cipher.eraseKey()
        val restored = CredentialStore(persistence, cipher)
        assertNull(persistence.value)
        assertFalse(restored.status.value.signedIn)
        assertTrue(restored.status.value.message!!.contains("Sign in again"))
    }
    @Test fun tamperedGcmCiphertextIsNeverReturned() {
        val persistence = FakeCredentialPersistence()
        val cipher = FakeCredentialCipher()
        CredentialStore(persistence, cipher).install(fakeCredential())
        persistence.value = EncryptedCredential(persistence.value!!.iv, "invalid")
        assertNull(CredentialStore(persistence, cipher).current())
        assertNull(cipher.key)
    }
    @Test fun expiredCredentialErasesKeyAndEncryptedPayload() {
        var now = 1L
        val persistence = FakeCredentialPersistence()
        val cipher = FakeCredentialCipher()
        val store = CredentialStore(persistence, cipher) { now }
        val value = fakeCredential(expires = 2)
        store.install(value); now = 2
        assertNull(store.tokenFor(value.origin))
        assertNull(persistence.value); assertNull(cipher.key)
    }
    @Test fun stale401CannotEraseNewTokenOrAnotherOrigin() {
        val store = CredentialStore(FakeCredentialPersistence(), FakeCredentialCipher())
        val old = fakeCredential(); val fresh = fakeCredential()
        store.install(old); store.install(fresh)
        store.unauthorized(old.origin, old.token)
        assertEquals(fresh.token, store.tokenFor(fresh.origin))
        store.unauthorized("https://other.test", fresh.token)
        assertEquals(fresh.token, store.tokenFor(fresh.origin))
        store.unauthorized(fresh.origin, fresh.token)
        assertNull(store.tokenFor(fresh.origin))
        assertTrue(store.status.value.message!!.contains("Sign in again"))
    }
    @Test fun persistenceAndEncryptionFailuresFailClosed() {
        for (encryptFails in listOf(false, true)) {
            val persistence = FakeCredentialPersistence()
            val cipher = FakeCredentialCipher()
            val store = CredentialStore(persistence, cipher)
            store.install(fakeCredential())
            cipher.failEncrypt = encryptFails; persistence.failWrite = !encryptFails
            try { store.install(fakeCredential()); fail() } catch (e: SignInException) { assertNull(e.cause) }
            assertNull(store.current()); assertNull(persistence.value); assertNull(cipher.key)
        }
    }
    @Test fun explicitClearWipesMetadataAndCannotRestore() {
        val persistence = FakeCredentialPersistence(); val cipher = FakeCredentialCipher()
        val store = CredentialStore(persistence, cipher)
        store.install(fakeCredential()); store.clear()
        assertNull(store.status.value.accountName)
        assertNull(CredentialStore(persistence, cipher).current())
    }
}
