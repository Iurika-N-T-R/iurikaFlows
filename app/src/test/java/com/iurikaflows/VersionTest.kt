package com.iurikaflows

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun compare() {
        assertTrue(isNewer("v1.2.10", "1.2.9"))
        assertTrue(isNewer("V0.0.5", "0.0.4"))
        assertTrue(isNewer("v1.1", "1.0.9"))
        assertFalse(isNewer("v1.2.1", "1.2.1"))
        assertFalse(isNewer("v1.2", "1.2.0"))
        assertFalse(isNewer("v0.9", "1.0"))
    }
}
