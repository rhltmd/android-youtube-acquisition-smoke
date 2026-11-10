package com.automusicshorts.acquisitionsmoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class GithubBootstrapTest {
    private val access = "ghu_" + "A".repeat(32)
    private val pairing = "A".repeat(43)
    private val fresh = "v1.AAECAwQFBgcICQoLLKGAAZjHIsaaMKzQDL5mkKJ-aVxiyOZ3sef1r79Jwqtb8X6srVo4slvYKlzPUVkOv0RfGvHmQQSnRHrAen6xlkRgzmXAZu0Cs9orwSpsZqx0OdPZM6JyIvivl1ZCdtKxdh2sM7Wv3yapTycpOehOPQDMUexD6PwUtrgD3WbvRDDMUktbm33cA6iW9qr0ZyRpyA"
    private val old = "v1.DA0ODxAREhMUFRYX7PbGvdoMCZSZoRlTlEGju8JgNviYMfDCeESXB4S4f060sDUI5S8medYUAkHGbosBrmYWXxvyfr15jm2Rc362BQZkCtKmwEraXCuxWG0b8Bs-JQaaZO4k4SJNZScE39SKXPsafp_YrZhWxPRwQooXrnuAs7d4tVVVgBYCPlicnl5HtcRFeDY5pLJTixTZIFH-tQ"

    private class Reply(url: URL, private val response: String) : HttpURLConnection(url) {
        val form = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getOutputStream() = form
    }

    private val requests = mutableListOf<Reply>()
    private var monotonic = 0L

    private fun client(vararg replies: String): GithubBootstrap {
        val queue = ArrayDeque(replies.toList())
        return GithubBootstrap(
            open = { url -> Reply(url, queue.removeFirst()).also(requests::add) },
            epoch = { 1720000000L },
            monotonic = { monotonic },
            sleep = { monotonic += it },
        )
    }

    private fun space(state: String, cpus: Int = 2) =
        """{"name":"unhallowed-casket-q7wjqqx7gp75h4jrv","id":53142444,"owner":{"login":"rhltmd"},"repository":{"id":1357480822,"full_name":"rhltmd/Auto-Music-Shorts-Generator"},"machine":{"cpus":$cpus},"state":"$state"}"""

    private fun variable(value: String) = """{"name":"MUSIC_SHORTS_QA_CONNECTION_V1","value":"$value"}"""

    private val scope = arrayOf(
        """{"login":"rhltmd"}""",
        """{"total_count":1,"installations":[{"id":179569762,"app_id":5631381,"account":{"login":"rhltmd"},"repository_selection":"selected","suspended_at":null,"permissions":{"metadata":"read","codespaces":"read","actions_variables":"write","codespaces_lifecycle_admin":"write"}}]}""",
        """{"total_count":1,"repositories":[{"id":1357480822,"full_name":"rhltmd/Auto-Music-Shorts-Generator"}]}""",
    )

    @Test fun stoppedTargetStartsOnceAndIgnoresTheSameSecondPredecessor() {
        val bootstrap = client(*scope, space("Shutdown"), variable(old), "{}", space("Shutdown"), space("Queued"),
            space("Available"), variable(old), space("Available"), variable(fresh))
        assertEquals("https://music-qa-demo.trycloudflare.com", bootstrap.connect(access, pairing, null).url)
        val write = requests.single { it.requestMethod == "POST" }
        assertEquals("https://api.github.com", "https://" + write.url.host)
        assertTrue(write.url.path.endsWith("/start"))
        assertTrue(requests.all { !it.instanceFollowRedirects && it.getRequestProperty("Authorization") == "Bearer $access" })
    }

    @Test fun alreadyAvailableTargetReadsButDoesNotStart() {
        val result = client(*scope, space("Available"), variable(fresh)).connect(access, pairing, null)
        assertEquals(1720000000000000042L, result.generation)
        assertTrue(requests.all { it.requestMethod == "GET" })
    }

    @Test fun refusesDifferentMachineBeforeAnyWrite() {
        val error = assertThrows(GithubBootstrapException::class.java) {
            client(*scope, space("Shutdown", cpus = 4)).connect(access, pairing, null)
        }
        assertEquals("GITHUB_CODESPACE_TARGET_MISMATCH", error.reason)
        assertTrue(requests.all { it.requestMethod == "GET" })
    }

    @Test fun deviceRefreshRotatesWithoutSendingTheAppSecretOrAccessToken() {
        val refresh = "ghr_" + "B".repeat(32)
        val next = "ghr_" + "C".repeat(32)
        val bootstrap = client("""{"access_token":"$access","refresh_token":"$next","token_type":"bearer","expires_in":28800,"refresh_token_expires_in":15897600}""")
        val result = bootstrap.refresh(GithubCredentials(access, refresh, 1719999999, 1720001000))
        assertEquals(next, result.refresh)
        val request = requests.single()
        assertEquals("https://github.com/login/oauth/access_token", request.url.toString())
        val form = request.form.toString("UTF-8")
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=$refresh"))
        assertFalse(form.contains("client_secret"))
        assertNull(request.getRequestProperty("Authorization"))
    }
}
