package com.karewinkcloud.agentweb.client.data

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.CancellationException

class AndroidPasskeyProvider(private val activity: Activity) : PasskeyProvider {
    override suspend fun authenticate(requestJson: String): String {
        try {
            val result = CredentialManager.create(activity).getCredential(activity,
                GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson))))
            return (result.credential as? PublicKeyCredential)?.authenticationResponseJson
                ?: throw PasskeyException(PasskeyFailure.INVALID)
        } catch (e: CancellationException) { throw e }
        catch (e: PasskeyException) { throw e }
        catch (_: NoCredentialException) { throw PasskeyException(PasskeyFailure.NO_CREDENTIAL) }
        catch (_: GetCredentialCancellationException) { throw PasskeyException(PasskeyFailure.CANCELLED) }
        catch (_: GetCredentialUnsupportedException) { throw PasskeyException(PasskeyFailure.UNSUPPORTED) }
        catch (_: GetCredentialProviderConfigurationException) { throw PasskeyException(PasskeyFailure.UNSUPPORTED) }
        catch (_: Exception) { throw PasskeyException(PasskeyFailure.PROVIDER) }
    }
}
