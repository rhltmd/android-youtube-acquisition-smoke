package com.automusicshorts.acquisitionsmoke

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

internal class GithubPairing(context: Context) {
    private val settings = context.getSharedPreferences("github-bootstrap", Context.MODE_PRIVATE)
    private val pairing = PairingStore(context)

    fun enabled(): Boolean = settings.contains("credentials")

    fun signIn(bootstrap: GithubBootstrap, challenge: GithubChallenge, cancelled: () -> Boolean) {
        val credentials = bootstrap.authorize(challenge, cancelled)
        if (!cancelled()) synchronized(lock) { save(credentials) }
    }

    fun signOut() = synchronized(lock) {
        check(settings.edit().remove("credentials").commit())
    }

    fun resolve(token: String): String = synchronized(lock) {
        val bootstrap = GithubBootstrap()
        var credentials = credentials()
        val now = System.currentTimeMillis() / 1000
        if (credentials.accessExpiresAt - now <= 60) {
            credentials = bootstrap.refresh(credentials).also(::save)
        }
        val previous = previous(token)
        val descriptor = bootstrap.connect(credentials.access, token, previous)
        check(settings.edit().putString("descriptor", pairing.encrypt(JSONObject()
            .put("pairing", fingerprint(token)).put("url", descriptor.url).put("generation", descriptor.generation)
            .put("issued_at", descriptor.issuedAt).put("expires_at", descriptor.expiresAt).toString())).commit())
        pairing.save(descriptor.url, token)
        descriptor.url
    }

    private fun credentials(): GithubCredentials = try {
        val raw = settings.getString("credentials", null) ?: error("Missing credentials")
        val value = JSONObject(pairing.decrypt(raw))
        GithubCredentials(value.getString("access"), value.getString("refresh"),
            value.getLong("access_expires"), value.getLong("refresh_expires"))
    } catch (_: Exception) {
        throw GithubBootstrapException("GITHUB_SIGN_IN_REQUIRED")
    }

    private fun save(value: GithubCredentials) {
        val data = JSONObject().put("access", value.access).put("refresh", value.refresh)
            .put("access_expires", value.accessExpiresAt).put("refresh_expires", value.refreshExpiresAt)
        check(settings.edit().putString("credentials", pairing.encrypt(data.toString())).commit())
    }

    private fun previous(token: String): BootstrapDescriptor? {
        val raw = settings.getString("descriptor", null) ?: return null
        return try {
            val value = JSONObject(pairing.decrypt(raw))
            if (value.getString("pairing") != fingerprint(token)) return null
            BootstrapDescriptor(value.getString("url"), value.getLong("generation"), value.getLong("issued_at"),
                value.getLong("expires_at"))
        } catch (_: Exception) {
            throw GithubBootstrapException("GITHUB_CONNECTION_CACHE_INVALID")
        }
    }

    private fun fingerprint(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object { val lock = Any() }
}
