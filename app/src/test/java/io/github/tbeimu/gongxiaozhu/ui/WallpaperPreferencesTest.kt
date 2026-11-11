package io.github.tbeimu.gongxiaozhu.ui

import org.junit.Assert.*
import org.junit.Test

class WallpaperPreferencesTest {
    @Test fun migrationPreservesAnExistingPhotoWithoutAddingBlur() {
        val migrated = WallpaperPreferences.migrated(true)
        assertEquals(WallpaperMode.PHOTO, migrated.mode)
        assertEquals(0, migrated.photoBlurDp)
        assertEquals("wallpaper.jpg", migrated.photoName)
        assertEquals(WallpaperPreferences(), WallpaperPreferences.migrated(false))
    }

    @Test fun allModesPresetsAndBlurLimitsRoundTrip() {
        for (mode in WallpaperMode.entries) for (preset in WallpaperPreset.entries) for (blur in listOf(0, 12, 24)) {
            val preferences = WallpaperPreferences(mode = mode, preset = preset, photoBlurDp = blur,
                photoName = "wallpaper-00000000-0000-0000-0000-000000000000.jpg")
            assertEquals(preferences, WallpaperPreferences.decode(WallpaperPreferences.encode(preferences)))
        }
    }

    @Test fun restoringDefaultRetainsPhotoAndItsBlur() {
        val photo = WallpaperPreferences(mode = WallpaperMode.PHOTO, preset = WallpaperPreset.PINK,
            photoBlurDp = 7, photoName = "wallpaper.jpg")
        val restored = photo.restoredDefault()
        assertEquals(WallpaperMode.PRESET, restored.mode)
        assertEquals(WallpaperPreset.BLUE_CYAN, restored.preset)
        assertEquals(photo.photoName, restored.photoName)
        assertEquals(7, restored.photoBlurDp)
    }

    @Test fun invalidSettingsAndPathsCannotAddressOtherPrivateFiles() {
        for (path in listOf("../accounts.json", "accounts.json", "D:/private.jpg", "/private.jpg", "wallpaper.jpg/../secret")) {
            assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(photoName = path).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(photoBlurDp = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(photoBlurDp = 25).validate() }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(schemaVersion = 2).validate() }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(mode = WallpaperMode.PHOTO).validate() }
    }

    @Test fun malformedOversizedAndUnknownConfigurationIsRejected() {
        for (value in listOf("not json", "{\"mode\":\"UNKNOWN\"}", "{\"photoBlurDp\":1.5}", "{\"schemaVersion\":2}")) {
            assertThrows(Exception::class.java) { WallpaperPreferences.decode(value.encodeToByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences.decode(ByteArray(16 * 1024 + 1)) }
        assertThrows(Exception::class.java) { WallpaperPreferences.decode(byteArrayOf(0xff.toByte())) }
    }
}
