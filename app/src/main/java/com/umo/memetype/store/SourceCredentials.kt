package com.umo.memetype.store

import android.content.Context
import android.content.SharedPreferences
import com.umo.memetype.source.AuthScheme
import com.umo.memetype.source.AuthStatus
import com.umo.memetype.source.Credentials
import com.umo.memetype.source.MemeSource

/**
 * Credentials per source (username/password or API key), in the app-private preferences file
 * `source_auth` — the same level of protection Mihon-style extensions give their logins.
 * Never logged, never sent anywhere but the source's own endpoints.
 */
class SourceCredentials(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(sourceId: String): Credentials = Credentials(
        username = prefs.getString("$sourceId.username", null)?.ifBlank { null },
        password = prefs.getString("$sourceId.password", null)?.ifBlank { null },
        apiKey = prefs.getString("$sourceId.apiKey", null)?.ifBlank { null }
    )

    fun set(sourceId: String, credentials: Credentials) {
        prefs.edit()
            .putString("$sourceId.username", credentials.username)
            .putString("$sourceId.password", credentials.password)
            .putString("$sourceId.apiKey", credentials.apiKey)
            .apply()
    }

    fun clear(sourceId: String) {
        prefs.edit()
            .remove("$sourceId.username")
            .remove("$sourceId.password")
            .remove("$sourceId.apiKey")
            .apply()
    }

    fun status(source: MemeSource): AuthStatus {
        val c = get(source.id)
        return when (val scheme = source.auth) {
            AuthScheme.None -> AuthStatus.NotNeeded
            is AuthScheme.ApiKey -> if (c.apiKey != null) AuthStatus.Ready(scheme.label) else AuthStatus.Missing
            is AuthScheme.UsernamePassword ->
                if (c.username != null && c.password != null) AuthStatus.Ready(c.username) else AuthStatus.Missing
        }
    }

    companion object {
        const val FILE = "source_auth"
    }
}
