package com.automusicshorts.acquisitionsmoke

import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class GithubBootstrapException(val reason: String) : Exception(reason)

internal class GithubCredentials(
    val access: String,
    val refresh: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
)

internal class GithubChallenge(
    val userCode: String,
    val deviceCode: String,
    val expiresAt: Long,
    val interval: Long,
)

internal class GithubBootstrap(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val epoch: () -> Long = { System.currentTimeMillis() / 1000 },
    private val monotonic: () -> Long = { SystemClock.elapsedRealtime() / 1000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it * 1000) },
) {
    fun begin(): GithubChallenge {
        val response = login("/device/code")
        val device = response.optString("device_code")
        val user = response.optString("user_code")
        val ttl = number(response, "expires_in")
        val interval = number(response, "interval")
        requireGithub(response.optString("verification_uri") == DEVICE_PAGE &&
            DEVICE_CODE.matches(device) && USER_CODE.matches(user) && ttl in 1..1800 && interval in 1..60,
            "GITHUB_DEVICE_CHALLENGE_INVALID")
        return GithubChallenge(user, device, monotonic() + ttl, interval)
    }

    fun authorize(challenge: GithubChallenge, cancelled: () -> Boolean): GithubCredentials {
        var interval = challenge.interval
        while (monotonic() + interval < challenge.expiresAt) {
            sleep(interval)
            if (cancelled() || monotonic() >= challenge.expiresAt) break
            val response = login("/oauth/access_token",
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to challenge.deviceCode)
            when (response.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> {
                    interval = maxOf(interval + 5, number(response, "interval").coerceIn(0, 120))
                    continue
                }
                "" -> return credentials(response).also { validateScope(it.access) }
                else -> throw GithubBootstrapException("GITHUB_DEVICE_AUTHORIZATION_FAILED")
            }
        }
        throw GithubBootstrapException("GITHUB_DEVICE_AUTHORIZATION_ENDED")
    }

    fun refresh(previous: GithubCredentials): GithubCredentials {
        requireGithub(previous.refreshExpiresAt > epoch() && REFRESH_TOKEN.matches(previous.refresh),
            "GITHUB_SIGN_IN_REQUIRED")
        val response = login("/oauth/access_token", "grant_type" to "refresh_token", "refresh_token" to previous.refresh)
        if (response.optString("error").isNotEmpty()) throw GithubBootstrapException("GITHUB_SIGN_IN_REQUIRED")
        return credentials(response).also {
            requireGithub(it.refresh != previous.refresh, "GITHUB_REFRESH_NOT_ROTATED")
        }
    }

    fun connect(access: String, pairing: String, cached: BootstrapDescriptor?): BootstrapDescriptor {
        requireGithub(PAIRING.matches(pairing), "INVALID_BOOTSTRAP_PAIRING")
        validateScope(access)
        var state = targetState(access)
        var startedAt = if (state == "Available") 0L else epoch()
        var previousEnvelope: String? = null
        var startRequested = false
        val end = monotonic() + 120
        while (monotonic() < end) {
            when (state) {
                "Shutdown" -> {
                    if (!startRequested) {
                        previousEnvelope = readEnvelope(access)
                        startedAt = epoch()
                        request(API, "$SPACE_PATH/start", "POST", access, expected = setOf(200, 304), parse = false)
                        startRequested = true
                    }
                }
                "Available" -> {
                    val value = readEnvelope(access)
                    try {
                        val descriptor = BootstrapEnvelope.open(value, pairing, REPO_ID, SPACE_ID,
                            epoch(), cached?.generation?.minus(1) ?: 0)
                        if (cached != null && descriptor.generation == cached.generation &&
                            (descriptor.url != cached.url || descriptor.issuedAt != cached.issuedAt ||
                                descriptor.expiresAt != cached.expiresAt)) {
                            throw GithubBootstrapException("CONNECTION_GENERATION_CONFLICT")
                        }
                        if (startedAt == 0L || (value != previousEnvelope && descriptor.issuedAt >= startedAt &&
                            descriptor.generation > (cached?.generation ?: 0))) {
                            return descriptor
                        }
                    } catch (e: BootstrapEnvelopeException) {
                        if (e.message !in setOf("CONNECTION_NOT_CURRENT", "CONNECTION_REPLAY")) throw e
                    }
                }
                "Queued", "Starting", "Provisioning", "ShuttingDown" -> Unit
                else -> throw GithubBootstrapException("GITHUB_CODESPACE_UNAVAILABLE")
            }
            sleep(3)
            state = targetState(access)
        }
        throw GithubBootstrapException("CLOUD_CONNECTION_NOT_READY")
    }

    private fun readEnvelope(access: String): String {
        val response = request(API, VARIABLE_PATH, access = access)
        requireGithub(response.optString("name") == VARIABLE_NAME, "GITHUB_VARIABLE_MISMATCH")
        return response.optString("value")
    }

    private fun targetState(access: String): String {
        val space = request(API, SPACE_PATH, access = access)
        val repo = space.optJSONObject("repository")
        requireGithub(number(space, "id") == SPACE_ID && space.optString("name") == SPACE &&
            space.optJSONObject("owner")?.optString("login") == ACCOUNT &&
            repo != null && number(repo, "id") == REPO_ID && repo.optString("full_name") == REPO &&
            space.optJSONObject("machine")?.let { number(it, "cpus") } == 2L, "GITHUB_CODESPACE_TARGET_MISMATCH")
        return space.optString("state")
    }

    private fun validateScope(access: String) {
        requireGithub(request(API, "/user", access = access).optString("login") == ACCOUNT, "GITHUB_ACCOUNT_MISMATCH")
        val list = request(API, "/user/installations?per_page=2", access = access)
        val install = list.optJSONArray("installations")?.takeIf { it.length() == 1 }?.optJSONObject(0)
        requireGithub(number(list, "total_count") == 1L && install != null, "GITHUB_INSTALLATION_MISMATCH")
        checkNotNull(install)
        val permissions = install.optJSONObject("permissions")
        requireGithub(number(install, "id") == INSTALL_ID && number(install, "app_id") == APP_ID &&
            install.optJSONObject("account")?.optString("login") == ACCOUNT &&
            install.optString("repository_selection") == "selected" && install.isNull("suspended_at") &&
            permissions?.keys()?.asSequence()?.toSet() == PERMISSIONS.keys &&
            PERMISSIONS.all { permissions?.optString(it.key) == it.value }, "GITHUB_INSTALLATION_MISMATCH")
        val repos = request(API, "/user/installations/$INSTALL_ID/repositories?per_page=2", access = access)
        val repo = repos.optJSONArray("repositories")?.takeIf { it.length() == 1 }?.optJSONObject(0)
        requireGithub(number(repos, "total_count") == 1L && repo != null &&
            number(repo, "id") == REPO_ID && repo.optString("full_name") == REPO, "GITHUB_REPOSITORY_MISMATCH")
    }

    private fun credentials(response: JSONObject): GithubCredentials {
        val access = response.optString("access_token")
        val refresh = response.optString("refresh_token")
        val accessTtl = number(response, "expires_in")
        val refreshTtl = number(response, "refresh_token_expires_in")
        requireGithub(response.optString("token_type").equals("bearer", true) && ACCESS_TOKEN.matches(access) &&
            REFRESH_TOKEN.matches(refresh) && accessTtl in 1..86400 && refreshTtl in 1..31536000,
            "GITHUB_TOKEN_RESPONSE_INVALID")
        val now = epoch()
        return GithubCredentials(access, refresh, now + accessTtl, now + refreshTtl)
    }

    private fun login(path: String, vararg form: Pair<String, String>) =
        request(LOGIN, path, "POST", form = listOf("client_id" to CLIENT_ID) + form)

    private fun request(
        origin: String,
        path: String,
        method: String = "GET",
        access: String? = null,
        form: List<Pair<String, String>> = emptyList(),
        expected: Set<Int> = setOf(200),
        parse: Boolean = true,
    ): JSONObject {
        if (access != null) requireGithub(origin == API && ACCESS_TOKEN.matches(access), "GITHUB_SIGN_IN_REQUIRED")
        val connection = try { open(URL(origin + path)) } catch (_: IOException) {
            throw GithubBootstrapException("GITHUB_NETWORK_UNAVAILABLE")
        }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", if (access == null) "application/json" else "application/vnd.github+json")
            if (access != null) {
                connection.setRequestProperty("Authorization", "Bearer $access")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            if (form.isNotEmpty()) {
                val bytes = form.joinToString("&") { (key, value) -> "$key=" + URLEncoder.encode(value, "UTF-8") }.toByteArray()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            requireGithub(status in expected, "GITHUB_HTTP_$status")
            if (!parse) return JSONObject()
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(2048)
                while (true) {
                    val length = reader.read(buffer)
                    if (length < 0) break
                    requireGithub(result.length + length <= 65_536, "GITHUB_RESPONSE_TOO_LARGE")
                    result.append(buffer, 0, length)
                }
                result.toString()
            }
            return try { JSONObject(response) } catch (_: org.json.JSONException) {
                throw GithubBootstrapException("GITHUB_RESPONSE_INVALID")
            }
        } catch (_: IOException) {
            throw GithubBootstrapException("GITHUB_NETWORK_UNAVAILABLE")
        } finally {
            connection.disconnect()
        }
    }

    private fun number(obj: JSONObject, key: String): Long = when (val raw = obj.opt(key)) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> -1
    }

    private fun requireGithub(condition: Boolean, reason: String) {
        if (!condition) throw GithubBootstrapException(reason)
    }

    companion object {
        const val DEVICE_PAGE = "https://github.com/login/device"
        private const val CLIENT_ID = "Iv23lis0Bos4N6nIoPOV"
        private const val ACCOUNT = "rhltmd"
        private const val APP_ID = 5631381L
        private const val INSTALL_ID = 179569762L
        private const val REPO_ID = 1357480822L
        private const val SPACE_ID = 53142444L
        private const val REPO = "$ACCOUNT/Auto-Music-Shorts-Generator"
        private const val SPACE = "unhallowed-casket-q7wjqqx7gp75h4jrv"
        private const val API = "https://api.github.com"
        private const val LOGIN = "https://github.com/login"
        private const val SPACE_PATH = "/user/codespaces/$SPACE"
        private const val VARIABLE_NAME = "MUSIC_SHORTS_QA_CONNECTION_V1"
        private const val VARIABLE_PATH = "/repos/$REPO/actions/variables/$VARIABLE_NAME"
        private val PERMISSIONS = mapOf("metadata" to "read", "codespaces" to "read",
            "actions_variables" to "write", "codespaces_lifecycle_admin" to "write")
        private val PAIRING = Regex("[A-Za-z0-9_-]{43,128}")
        private val DEVICE_CODE = Regex("[A-Za-z0-9_-]{16,256}")
        private val USER_CODE = Regex("[A-Z0-9]{4}-[A-Z0-9]{4}")
        private val ACCESS_TOKEN = Regex("ghu_[A-Za-z0-9_]{12,252}")
        private val REFRESH_TOKEN = Regex("ghr_[A-Za-z0-9_]{12,252}")
    }
}
