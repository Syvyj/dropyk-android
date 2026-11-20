package dev.syvyj.dropyk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test fun versions() {
        assertTrue(Update.newer("0.2.0", "0.1.0"))
        assertTrue(Update.newer("0.1.10", "0.1.9"))
        assertTrue(Update.newer("1.0", "0.9.9"))
        assertFalse(Update.newer("0.1.0", "0.1.0"))
        assertFalse(Update.newer("0.1.0", "0.2.0"))
        assertFalse(Update.newer("0.2.0-beta1", "0.2.0"))
    }

    @Test fun readsTheReleaseFromWhereLatestLeads() {
        assertEquals(
            AppRelease("0.5.2", "https://github.com/Syvyj/dropyk-android/releases/download/v0.5.2/dropyk.apk"),
            Update.parse("https://github.com/Syvyj/dropyk-android/releases/tag/v0.5.2"),
        )
        assertNull(Update.parse("https://github.com/Syvyj/dropyk-android/releases"))
    }
}
