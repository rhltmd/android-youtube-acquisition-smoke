package com.automusicshorts.acquisitionsmoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QaBuildIdentityTest {
    @Test fun qaApplicationDoesNotReplaceEitherExistingPrototype() {
        assertEquals("com.automusicshorts.acquisitionsmoke.audio.e2eqa", BuildConfig.APPLICATION_ID)
        assertNotEquals("com.automusicshorts.acquisitionsmoke.audio", BuildConfig.APPLICATION_ID)
        assertNotEquals("com.automusicshorts.acquisitionsmoke", BuildConfig.APPLICATION_ID)
    }

    @Test fun qaVersionIsClearlyIdentifiable() {
        assertEquals("0.2.0-e2e-qa", BuildConfig.VERSION_NAME)
    }
}
