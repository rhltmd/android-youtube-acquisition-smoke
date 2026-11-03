package com.automusicshorts.acquisitionsmoke

import android.content.Context
import org.json.JSONObject
import java.io.File

/** QA-only private adb import. No exported receiver, discovery URL or embedded secret. */
internal object QaConnection {
    @Synchronized fun refreshPairing(context: Context): Boolean {
        if (!BuildConfig.DEBUG || !BuildConfig.APPLICATION_ID.endsWith(".automation")) return false
        val manifest = File(context.filesDir, "qa-connection.json")
        if (!manifest.isFile) return false
        // Malformed/stale imports never erase a working pairing.
        return runCatching {
            require(manifest.length() in 1..8192)
            val data = JSONObject(manifest.readText())
            require(data.keys().asSequence().toSet() == setOf("cloud_url", "token", "generation"))
            val generation = data.getLong("generation")
            require(generation > 0)
            val settings = context.getSharedPreferences("qa-connection-import", Context.MODE_PRIVATE)
            if (generation <= settings.getLong("generation", 0)) return false
            val url = data.getString("cloud_url")
            mobileJobEndpoint(url, "0".repeat(32), "status") // Strict existing server-root validation.
            val token = data.getString("token")
            require(token.isNotEmpty() && token.length <= 1024 && token.none { it.isWhitespace() })
            PairingStore(context).save(url.trim().trimEnd('/'), token)
            // Drain the existing store's async write before committing its generation.
            check(context.getSharedPreferences("cloud-pairing", Context.MODE_PRIVATE).edit().commit())
            check(settings.edit().putLong("generation", generation).commit())
            true
        }.getOrDefault(false)
    }
}
