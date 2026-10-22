package com.automusicshorts.acquisitionsmoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileJobClientTest {
    @Test fun projectRoutesUseHttpsAndServerRoot() {
        val id = "a".repeat(32)
        for (action in listOf("start", "status", "review", "download")) {
            assertEquals("https://example.trycloudflare.com/mobile-projects/$id/$action",
                mobileJobEndpoint(" https://example.trycloudflare.com/ ", id, action).toString())
        }
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
