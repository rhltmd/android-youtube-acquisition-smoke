package com.automusicshorts.acquisitionsmoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
    fun httpFailuresAreSeparatedForTheUi() {
        assertEquals("AUTH_FAILURE", classifyHttpStatus(401))
        assertEquals("CLOUD_VALIDATION_FAILURE", classifyHttpStatus(422))
        assertEquals("PROJECT_CREATION_FAILURE", classifyHttpStatus(500))
        assertEquals("UPLOAD_FAILURE", classifyHttpStatus(409))
    }
}
