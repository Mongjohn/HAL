package com.evofactory.hal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class VideoBackgroundSettingsTest {
    private val video = BackgroundVideo("video_1", "Entré æøå", "https://example.org/movie.mp4?token=secret%2Bvalue")

    @Test fun libraryRoundTripPreservesOrderUnicodeTargetsAndQuery() {
        val settings = VideoBackgroundSettings(2,
            videos = listOf(video.copy(background = true, randomStart = true), video.copy(id = "second", title = "Second", screensaver = true)),
            shuffle = true, switchSeconds = 120, dashboardDim = 50, screensaverDim = 80)
        assertEquals(settings, videoBackgroundSettingsFromJson(settings.toJson()))
        assertEquals(VideoBackgroundSettings(), videoBackgroundSettingsFromJson(VideoBackgroundSettings().toJson()))
    }

    @Test fun acceptsOnlyUsableDirectHttpUrlsWithoutEmbeddedCredentials() {
        for (url in listOf("https://example.org/a.mp4", "http://192.168.1.20:8080/a.webm?key=x", "http://[::1]/a.mp4")) {
            assertTrue(url, validBackgroundVideoUrl(url))
        }
        for (url in listOf("file:///etc/file", "content://movie", "javascript:alert(1)", "ftp://example.org/a", "https://", "/relative.mp4",
            "https://user:password@example.org/a.mp4", "https://example.org:99999/a", "http://host/white space.mp4")) {
            assertFalse(url, validBackgroundVideoUrl(url))
        }
    }

    @Test fun rejectsDuplicateIdsAndOversizedLibraries() {
        val duplicate = VideoBackgroundSettings(videos = listOf(video, video)).toJson()
        assertThrows(IllegalArgumentException::class.java) { videoBackgroundSettingsFromJson(duplicate) }
        val oversized = VideoBackgroundSettings(videos = (0..50).map { video.copy(id = "id$it") }).toJson()
        assertThrows(IllegalArgumentException::class.java) { videoBackgroundSettingsFromJson(oversized) }
        assertThrows(IllegalArgumentException::class.java) {
            videoBackgroundSettingsFromJson(VideoBackgroundSettings(videos = listOf(video.copy(title = ""))).toJson())
        }
    }

    @Test fun randomStartsRequireSeekableFiniteKnownDurationAndKeepATail() {
        assertEquals(0L, backgroundVideoStart(-1, true, true, 0.5))
        assertEquals(0L, backgroundVideoStart(100_000, false, true, 0.5))
        assertEquals(0L, backgroundVideoStart(100_000, true, false, 0.5))
        assertEquals(45_000L, backgroundVideoStart(100_000, true, true, 0.5))
        assertTrue(backgroundVideoStart(100_000, true, true, 1.0) < 90_000)
        assertEquals(0L, backgroundVideoStart(1, true, true, 0.8))
    }

    @Test fun targetsAreIndependentAndOnlyTheForegroundRequestedSurfacePlays() {
        val both = VideoBackgroundSettings(videos = listOf(video.copy(background = true, screensaver = true)))
        assertTrue(backgroundVideoWanted(both, false, true, false))
        assertTrue(backgroundVideoWanted(both, true, true, false))
        assertFalse(backgroundVideoWanted(both, true, false, false))
        assertFalse(backgroundVideoWanted(both, false, true, true))
        assertFalse(backgroundVideoWanted(both.copy(videos = emptyList()), false, true, false))
        assertFalse(backgroundVideoWanted(both.copy(videos = listOf(video.copy(screensaver = true))), false, true, false))
        assertFalse(backgroundVideoWanted(both.copy(videos = listOf(video.copy(background = true))), true, true, false))
    }

    @Test fun legacyGlobalFlagsMigrateToEachVideoWithoutChangingChoices() {
        val json = JSONObject().put("revision", 7).put("background", true).put("screensaver", false).put("randomStart", true)
            .put("videos", VideoBackgroundSettings(videos = listOf(video)).toJson().getJSONArray("videos"))
        val migrated = videoBackgroundSettingsFromJson(json)
        assertEquals(7L, migrated.revision)
        assertEquals(listOf(video.copy(background = true, randomStart = true)), migrated.videos)
        assertEquals(migrated, videoBackgroundSettingsFromJson(migrated.toJson()))
    }

    @Test fun surfaceSelectionPreservesOrderAndPerVideoRandomChoice() {
        val dashboard = video.copy(background = true)
        val idle = video.copy(id = "idle", screensaver = true, randomStart = true)
        val both = video.copy(id = "both", background = true, screensaver = true)
        val settings = VideoBackgroundSettings(videos = listOf(video.copy(id = "disabled"), dashboard, idle, both))
        assertEquals(listOf(dashboard, both), settings.videosFor(false))
        assertEquals(listOf(idle, both), settings.videosFor(true))
    }

    @Test fun rejectsInvalidIntervalsAndDimming() {
        for ((key, values) in mapOf("switchSeconds" to listOf(-1, 1, 9, 86401, 10.5),
            "dashboardDim" to listOf(-1, 101), "screensaverDim" to listOf(-1, 101))) {
            for (value in values) assertThrows("$key=$value", IllegalArgumentException::class.java) {
                videoBackgroundSettingsFromJson(VideoBackgroundSettings().toJson().put(key, value))
            }
        }
        for (seconds in listOf(0, 10, 86400)) assertEquals(seconds,
            videoBackgroundSettingsFromJson(VideoBackgroundSettings(switchSeconds = seconds).toJson()).switchSeconds)
    }

    @Test fun retiredIdleTimeoutIsIgnoredAndDroppedWithoutLosingSavedSettings() {
        val original = VideoBackgroundSettings(revision = 12, videos = listOf(video.copy(screensaver = true)),
            shuffle = true, switchSeconds = 75, dashboardDim = 42, screensaverDim = 81)
        for (timeout in listOf(0, 1440, -1, "obsolete")) {
            val old = original.toJson().put("schema", 2).put("idleMinutes", timeout)
            val migrated = videoBackgroundSettingsFromJson(old)
            assertEquals(original, migrated)
            assertFalse(migrated.toJson().has("idleMinutes"))
            assertEquals(3, migrated.toJson().getInt("schema"))
        }
    }

    @Test fun dimmingPreservesDefaultGradientAndClearBlackEndpoints() {
        for ((base, reference) in listOf(0.82f to 66, 0.66f to 66, 0.88f to 66, 0.8524f to 72, 0.7212f to 72, 0.9016f to 72)) {
            assertEquals(base, backgroundShadeAlpha(base, reference, reference), 0.00001f)
            assertEquals(0f, backgroundShadeAlpha(base, 0, reference), 0.00001f)
            assertEquals(1f, backgroundShadeAlpha(base, 100, reference), 0.00001f)
            assertTrue(backgroundShadeAlpha(base, 30, reference) < backgroundShadeAlpha(base, 80, reference))
        }
    }

    @Test fun dreamHandoffKeepsMatchingPlaybackAndIgnoresLateLauncherDisposal() {
        val ownership = BackgroundVideoOwnership()
        val launcher = Any()
        val dream = Any()
        val settings = VideoBackgroundSettings(videos = listOf(video))
        assertTrue(ownership.acquire(launcher, settings, false))
        assertFalse(ownership.acquire(dream, settings, true))
        assertFalse(ownership.relinquish(launcher))
        assertTrue(ownership.relinquish(dream))
        // Return inside the bounded grace period keeps the same prepared session.
        assertFalse(ownership.acquire(launcher, settings, true))
        assertTrue(ownership.relinquish(launcher))
        ownership.released()
        assertTrue(ownership.acquire(dream, settings, false))
        assertTrue(ownership.acquire(launcher, settings.copy(videos = listOf(video.copy(id = "other"))), true))
    }
}
