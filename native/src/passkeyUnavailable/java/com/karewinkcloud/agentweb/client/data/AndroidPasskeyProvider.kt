package com.karewinkcloud.agentweb.client.data

import android.app.Activity

/** Offline distribution fallback. Replaced at build time when both AndroidX artifacts are cached. */
class AndroidPasskeyProvider(@Suppress("UNUSED_PARAMETER") activity: Activity) : PasskeyProvider {
    override suspend fun authenticate(requestJson: String): String = throw PasskeyException(PasskeyFailure.UNSUPPORTED)
}
