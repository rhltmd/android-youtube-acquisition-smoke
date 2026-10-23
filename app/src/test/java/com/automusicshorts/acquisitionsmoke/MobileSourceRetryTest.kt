package com.automusicshorts.acquisitionsmoke

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobileSourceRetryTest {
    @get:Rule val temporary = TemporaryFolder()

    private class LostResponse(url: URL) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream() = body
        override fun getResponseCode(): Int = throw SocketTimeoutException("response lost")
    }

    @Test fun productionMultipartRetryReusesIdAndCustomAuthWithoutRedownload() {
        val media = temporary.newFile("song.webm").apply { writeText("synthetic media fixture") }
        val pending = PendingCloudUpload(media, "https://youtu.be/song", "test", 1000)
        val connections = mutableListOf<LostResponse>()
        val client = MobileUploadClient { url -> LostResponse(url).also { connections.add(it) } }
        repeat(2) {
            val error = assertThrows(CloudBridgeException::class.java) {
                client.upload("https://example.trycloudflare.com", "test-only-token", pending) { }
            }
            assertEquals("SERVER_UNREACHABLE", error.category)
        }
        for (connection in connections) {
            assertEquals("POST", connection.requestMethod)
            assertEquals("https://example.trycloudflare.com/mobile-source", connection.url.toString())
            assertEquals(pending.requestId, connection.getRequestProperty(MOBILE_SOURCE_REQUEST_ID_HEADER))
            assertEquals("test-only-token", connection.getRequestProperty(MOBILE_UPLOAD_TOKEN_HEADER))
            assertNull(connection.getRequestProperty("Authorization"))
            val body = connection.body.toString("UTF-8")
            assertTrue(body.contains("synthetic media fixture"))
            assertTrue(body.contains("name=\"sha256\""))
            assertFalse(body.contains("test-only-token"))
            assertTrue(connection.closed)
        }
        assertTrue(media.isFile)
    }

    @Test fun intentionalNewAcquisitionGetsNewIdAndInvalidIdNeverOpensConnection() {
        val media = temporary.newFile("song.webm").apply { writeText("fixture") }
        val first = PendingCloudUpload(media, "https://youtu.be/song", "test", 1000)
        val second = PendingCloudUpload(media, "https://youtu.be/song", "test", 1000)
        assertNotEquals(first.requestId, second.requestId)
        val client = MobileUploadClient { error("must not open network") }
        assertThrows(IllegalArgumentException::class.java) {
            client.upload("https://example.com", "test-only-token", first.copy(requestId = "../bad")) { }
        }
    }

    @Test fun backgroundKeepsItsExistingProjectMultipartAndCustomAuth() {
        val media = temporary.newFile("background.mp4").apply { writeText("video fixture") }
        lateinit var connection: LostResponse
        val client = MobileUploadClient { url -> LostResponse(url).also { connection = it } }
        assertThrows(CloudBridgeException::class.java) {
            client.uploadBackground("https://example.com", "test-only-token",
                PendingBackgroundUpload(media, "https://youtu.be/background", "a".repeat(32))) { }
        }
        assertEquals("https://example.com/mobile-background", connection.url.toString())
        assertEquals("test-only-token", connection.getRequestProperty(MOBILE_UPLOAD_TOKEN_HEADER))
        assertNull(connection.getRequestProperty(MOBILE_SOURCE_REQUEST_ID_HEADER))
        assertTrue(connection.body.toString("UTF-8").contains("a".repeat(32)))
    }
}
