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

    @Test fun parsesTheApkAsset() {
        val json = """{"tag_name":"v0.2.0","assets":[{"name":"notes.txt","browser_download_url":"x"},
            {"name":"dropyk.apk","browser_download_url":"https://github.com/Syvyj/dropyk-android/releases/download/v0.2.0/dropyk.apk"}]}"""
        assertEquals(AppRelease("0.2.0", "https://github.com/Syvyj/dropyk-android/releases/download/v0.2.0/dropyk.apk"), Update.parse(json))
        assertNull(Update.parse("""{"tag_name":"v0.2.0","assets":[]}"""))
    }
}
