package com.automusicshorts.acquisitionsmoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class CloudBridgeTest {
    @Test
    fun uploadUsesCodespacesSafeCustomTokenHeader() {
        assertEquals("X-Music-Shorts-Token", MOBILE_UPLOAD_TOKEN_HEADER)
    }

    @Test
    fun endpointRequiresHttpsAndAddsMobileSourcePath() {
        assertEquals(
            "https://example.app.github.dev/mobile-source",
            mobileSourceEndpoint(" https://example.app.github.dev/ ").toString(),
        )
        assertThrows(CloudBridgeException::class.java) {
            mobileSourceEndpoint("http://example.invalid")
        }
    }

    @Test
    fun backgroundSelectorUsesNoFormatIdOrFfmpegMerge() {
        val selector = MainActivity.BACKGROUND_FORMAT_SELECTOR
        assertTrue(selector.contains("bestvideo"))
        assertTrue(selector.contains("height<=1080"))
        assertFalse(selector.contains("+"))
        assertFalse(selector.split('/').any { it == "18" || it == "251" })
    }

    @Test
    fun backgroundEndpointIncludesProjectIdWithoutChangingSongEndpoint() {
        assertEquals(
            "https://example.trycloudflare.com/mobile-source",
            mobileSourceEndpoint("https://example.trycloudflare.com").toString(),
        )
        assertEquals(
            "https://example.trycloudflare.com/mobile-background",
            mobileBackgroundEndpoint(" https://example.trycloudflare.com/ ").toString(),
        )
        val file = File.createTempFile("background", ".mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val fields = backgroundUploadFields(
                PendingBackgroundUpload(
                    file = file,
                    youtubeUrl = "https://www.youtube.com/watch?v=background",
                    projectId = "a".repeat(32),
                ),
                "0".repeat(64),
            )
            assertEquals("a".repeat(32), fields["project_id"])
            assertEquals(file.length().toString(), fields["size"])
            assertEquals("0".repeat(64), fields["sha256"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun httpFailuresAreSeparatedForTheUi() {
        assertEquals("AUTH_FAILURE", classifyHttpStatus(401))
        assertEquals("CLOUD_VALIDATION_FAILURE", classifyHttpStatus(422))
        assertEquals("PROJECT_CREATION_FAILURE", classifyHttpStatus(500))
        assertEquals("UPLOAD_FAILURE", classifyHttpStatus(409))
    }
}
