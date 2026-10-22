package com.automusicshorts.acquisitionsmoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MobileJobClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private class DownloadConnection(
        url: URL,
        private val bytes: ByteArray,
        private val length: Long = bytes.size.toLong(),
        private val type: String = "video/mp4",
        private val status: Int = 200,
    ) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = type
        override fun getHeaderFieldLong(name: String?, defaultValue: Long) =
            if (name == "Content-Length") length else defaultValue
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }

    @Test fun downloadUsesCustomAuthAndAtomicallyPublishesExactBytes() {
        val id = "a".repeat(32)
        val bytes = "synthetic-transfer-fixture".toByteArray()
        lateinit var connection: DownloadConnection
        val client = MobileJobClient("https://example.com", "test-only-token") { url ->
            DownloadConnection(url, bytes).also { connection = it }
        }
        val directory = temporary.newFolder()
        val file = client.download(id, directory)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("https://example.com/mobile-projects/$id/download", connection.url.toString())
        assertEquals("GET", connection.requestMethod)
        assertEquals("test-only-token", connection.getRequestProperty("X-Music-Shorts-Token"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
        assertFalse(File(directory, file.name + ".part").exists())
    }

    @Test fun incompleteDownloadPreservesPreviousVideoAndRemovesPartial() {
        val id = "a".repeat(32)
        val directory = temporary.newFolder()
        val existing = File(directory, "music-shorts-$id.mp4").apply { writeText("previous-video") }
        val connection = DownloadConnection(URL("https://example.com"), byteArrayOf(1, 2), length = 3)
        val client = MobileJobClient("https://example.com", "test-only-token") { connection }
        assertThrows(IllegalArgumentException::class.java) { client.download(id, directory) }
        assertEquals("previous-video", existing.readText())
        assertFalse(File(directory, existing.name + ".part").exists())
        assertTrue(connection.closed)
    }

    @Test fun htmlResponseAndUnknownOrExcessLengthAreNotPublished() {
        val id = "a".repeat(32)
        val directory = temporary.newFolder()
        val connections = listOf(
            DownloadConnection(URL("https://example.com"), byteArrayOf(1), type = "text/html"),
            DownloadConnection(URL("https://example.com"), byteArrayOf(1), length = -1),
            DownloadConnection(URL("https://example.com"), byteArrayOf(1), length = Long.MAX_VALUE),
            DownloadConnection(URL("https://example.com"), byteArrayOf(1, 2), length = 1),
        )
        for (connection in connections) {
            val client = MobileJobClient("https://example.com", "test-only-token") { connection }
            assertThrows(IllegalArgumentException::class.java) { client.download(id, directory) }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(connection.closed)
        }
    }

    @Test fun httpFailureDoesNotCreateCompletedVideo() {
        val directory = temporary.newFolder()
        val connection = DownloadConnection(URL("https://example.com"), byteArrayOf(), status = 401)
        val client = MobileJobClient("https://example.com", "test-only-token") { connection }
        assertThrows(CloudBridgeException::class.java) { client.download("a".repeat(32), directory) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(connection.closed)
    }

    @Test fun projectRoutesUseHttpsAndServerRoot() {
        val id = "a".repeat(32)
        for (action in listOf("start", "status", "review", "review-line", "download")) {
            assertEquals("https://example.trycloudflare.com/mobile-projects/$id/$action",
                mobileJobEndpoint(" https://example.trycloudflare.com/ ", id, action).toString())
        }
    }

    @Test fun explicitReviewEditsAcceptValidMillisecondRange() {
        assertEquals(100 to 900, reviewEditTimes("修正", "수정", " 100 ", "900"))
        assertEquals(0 to 900, reviewEditTimes("", "", "0", "900"))
    }

    @Test fun invalidReviewEditsAreRejectedBeforeNetworkRequest() {
        for (range in listOf("" to "900", "-1" to "900", "900" to "900", "901" to "900",
                             "0.5" to "900", "0" to "999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { reviewEditTimes("a", "b", range.first, range.second) }
        }
        assertThrows(IllegalArgumentException::class.java) { reviewEditTimes("x".repeat(2001), "b", "0", "900") }
    }

    @Test fun credentialsQueriesPathsAndInvalidIdsAreRejected() {
        val id = "a".repeat(32)
        for (base in listOf("http://example.com", "https://user:secret@example.com", "https://example.com/x",
                            "https://example.com?secret=x", "https://example.com#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { mobileJobEndpoint(base, id, "status") }
        }
        for (invalid in listOf("../escape", "", "a".repeat(31), "f".repeat(33))) {
            assertThrows(IllegalArgumentException::class.java) { mobileJobEndpoint("https://example.com", invalid, "status") }
        }
        assertThrows(IllegalArgumentException::class.java) { mobileJobEndpoint("https://example.com", id, "../source") }
    }
}
