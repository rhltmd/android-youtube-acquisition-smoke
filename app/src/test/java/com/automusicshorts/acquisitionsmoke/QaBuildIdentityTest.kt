package com.automusicshorts.acquisitionsmoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QaBuildIdentityTest {
    @Test fun qaApplicationDoesNotReplaceEitherExistingPrototype() {
        val suffix = when {
            BuildConfig.FLAVOR == "automation" -> ".automation"
            BuildConfig.VERSION_NAME.endsWith("-bootstrap") -> ".bootstrapqa"
            else -> ""
        }
        assertEquals("com.automusicshorts.acquisitionsmoke.audio.e2eqa" + suffix, BuildConfig.APPLICATION_ID)
        assertNotEquals("com.automusicshorts.acquisitionsmoke.audio", BuildConfig.APPLICATION_ID)
        assertNotEquals("com.automusicshorts.acquisitionsmoke", BuildConfig.APPLICATION_ID)
    }

    @Test fun qaVersionIsClearlyIdentifiable() {
        val suffix = when {
            BuildConfig.FLAVOR == "automation" -> "-automation"
            BuildConfig.APPLICATION_ID.endsWith(".bootstrapqa") -> "-bootstrap"
            else -> ""
        }
        assertEquals("0.2.0-e2e-qa" + suffix, BuildConfig.VERSION_NAME)
    }
}
