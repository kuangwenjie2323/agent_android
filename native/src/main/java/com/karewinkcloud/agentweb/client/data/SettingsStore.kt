package com.karewinkcloud.agentweb.client.data

import android.content.Context
import androidx.core.content.edit
import com.karewinkcloud.agentweb.client.BuildConfig
import com.karewinkcloud.agentweb.client.core.ModelChoice
import java.security.KeyStore

data class Preferences(
    val origin: String = "https://agent.karewinkcloud.com", val theme: String = "system",
    val choice: ModelChoice = ModelChoice(), val hasToken: Boolean = false, val language: String = "system",
)

interface SettingsStorage : TokenProvider {
    val credentials: CredentialStore
    fun load(): Preferences
    fun save(origin: String, theme: String)
    fun saveLanguage(language: String)
    fun saveChoice(choice: ModelChoice)
}

class SettingsStore(context: Context) : SettingsStorage {
    private val prefs = context.getSharedPreferences("native-settings", Context.MODE_PRIVATE)
    override val credentials = CredentialStore(AndroidCredentialPersistence(context), AndroidCredentialCipher())
    init {
        // Manual bearer entry predates native device grants; require a fresh browser sign-in.
        if (prefs.contains("token-ciphertext") || prefs.contains("token-iv")) {
            prefs.edit().remove("token-ciphertext").remove("token-iv").commit()
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("agentweb-bearer-v1") }
        }
    }
    override fun load() = Preferences(
        prefs.getString("origin", null) ?: "https://agent.karewinkcloud.com",
        prefs.getString("theme", "system") ?: "system",
        ModelChoice(prefs.getString("agent", "").orEmpty(), prefs.getString("model", "").orEmpty(),
            prefs.getString("effort", null), prefs.getString("permission", "auto") ?: "auto"),
        credentials.status.value.origin == prefs.getString("origin", "https://agent.karewinkcloud.com"),
        prefs.getString("language", "system") ?: "system",
    )
    override fun save(origin: String, theme: String) {
        val normalized = normalizeBaseUrl(origin, BuildConfig.DEBUG)
        require(theme in setOf("system", "light", "dark")) { "Choose a valid appearance." }
        if (normalized != load().origin) credentials.clear()
        check(prefs.edit().putString("origin", normalized).putString("theme", theme).commit()) { "Unable to save settings." }
    }
    override fun saveLanguage(language: String) {
        require(language in setOf("system", "zh", "en"))
        check(prefs.edit().putString("language", language).commit())
    }
    override fun saveChoice(choice: ModelChoice) {
        prefs.edit {
            putString("agent", choice.agent); putString("model", choice.model)
            putString("effort", choice.effort); putString("permission", choice.permission)
        }
    }
    override fun tokenFor(origin: String) = if (origin == load().origin) credentials.tokenFor(origin) else null
    override fun unauthorized(origin: String, rejectedToken: String?) = credentials.unauthorized(origin, rejectedToken)
}
