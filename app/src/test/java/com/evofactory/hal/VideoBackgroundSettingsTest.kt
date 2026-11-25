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
        val both = VideoBackgroundSettings(videos = listOf(video.copy(background = true, screensaver = true)),
            playlists = listOf(VideoPlaylist("on", "On", listOf(video.id), true)))
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
        val settings = VideoBackgroundSettings(videos = listOf(video.copy(id = "disabled"), dashboard, idle, both),
            playlists = listOf(VideoPlaylist("on", "On", listOf("disabled", dashboard.id, idle.id, both.id), true)))
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
            shuffle = true, switchSeconds = 75, dashboardDim = 42, screensaverDim = 81,
            playlists = listOf(VideoPlaylist("existing-videos", "Existing videos", listOf(video.id), true)))
        for (timeout in listOf(0, 1440, -1, "obsolete")) {
            val old = original.toJson().put("schema", 2).put("idleMinutes", timeout)
            val migrated = videoBackgroundSettingsFromJson(old)
            assertEquals(original, migrated)
            assertFalse(migrated.toJson().has("idleMinutes"))
            assertEquals(4, migrated.toJson().getInt("schema"))
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

    @Test fun enabledPlaylistsSelectAUnionWithoutReorderingOrDuplicatingVideos() {
        val a = video.copy(id = "a", background = true, screensaver = true)
        val b = video.copy(id = "b", screensaver = true)
        val c = video.copy(id = "c", background = true)
        val settings = VideoBackgroundSettings(videos = listOf(a, b, c), playlists = listOf(
            VideoPlaylist("first", "First", listOf("b", "a"), true),
            VideoPlaylist("second", "Second", listOf("a", "c"), true)))
        assertEquals(listOf(a, b), settings.videosFor(true))
        assertEquals(listOf(a, c), settings.videosFor(false))
        assertEquals(settings, videoBackgroundSettingsFromJson(settings.toJson()))
        assertTrue(settings.copy(playlists = settings.playlists.map { it.copy(enabled = false) }).videosFor(false).isEmpty())
        assertFalse(backgroundVideoWanted(settings.copy(playlists = emptyList()), false, true, false))
    }

    @Test fun oldLibrariesMigrateToAnEnabledPlaylistButFreshLibrariesNeedExplicitSelection() {
        val old = VideoBackgroundSettings(videos = listOf(video.copy(background = true))).toJson()
            .put("schema", 3).put("idleMinutes", 17)
        old.remove("playlists")
        val migrated = videoBackgroundSettingsFromJson(old)
        assertEquals(listOf(video.id), migrated.playlists.single().videoIds)
        assertTrue(migrated.playlists.single().enabled)
        assertEquals(1, migrated.videosFor(false).size)
        assertTrue(VideoBackgroundSettings(videos = migrated.videos).videosFor(false).isEmpty())
    }

    @Test fun oldPopupForecastInheritsThePreviousDashboardChoiceThenBecomesIndependent() {
        val json = VideoBackgroundSettings(dashboardForecast = ForecastDisplay.Daily).toJson().also { it.remove("cardForecast") }
        val migrated = videoBackgroundSettingsFromJson(json)
        assertEquals(ForecastDisplay.Daily, migrated.cardForecast)
        val changed = migrated.copy(dashboardForecast = ForecastDisplay.Off, cardForecast = ForecastDisplay.Hourly)
        assertEquals(changed, videoBackgroundSettingsFromJson(changed.toJson()))
        assertThrows(IllegalArgumentException::class.java) { videoBackgroundSettingsFromJson(json.put("cardForecast", "weekly")) }
    }

    @Test fun bothForecastModeRoundTripsForEverySurface() {
        val settings = VideoBackgroundSettings(dashboardForecast = ForecastDisplay.Both, cardForecast = ForecastDisplay.Both, screensaverForecast = ForecastDisplay.Both)
        assertEquals(settings, videoBackgroundSettingsFromJson(settings.toJson()))
    }

    @Test fun popupOpacityMigratesFromTheOldFocusedValueAndThenChangesIndependently() {
        val json = VideoBackgroundSettings(focusedCardOpacity = 80, unfocusedCardOpacity = 20).toJson().also { it.remove("popupCardOpacity") }
        val old = videoBackgroundSettingsFromJson(json)
        assertEquals(80, old.popupCardOpacity)
        val changed = old.copy(focusedCardOpacity = 40, popupCardOpacity = 90)
        assertEquals(changed, videoBackgroundSettingsFromJson(changed.toJson()))
        assertEquals(null, videoBackgroundSettingsFromJson(changed.toJson().put("popupCardOpacity", JSONObject.NULL)).popupCardOpacity)
        assertEquals(40, changed.focusedCardOpacity)
        assertEquals(20, changed.unfocusedCardOpacity)
    }

    @Test fun optionalForecastModesAreIndependentAndValidated() {
        val settings = VideoBackgroundSettings(dashboardForecast = ForecastDisplay.Hourly, screensaverForecast = ForecastDisplay.Daily)
        assertEquals(settings, videoBackgroundSettingsFromJson(settings.toJson()))
        val old = settings.toJson().also { it.remove("dashboardForecast"); it.remove("screensaverForecast") }
        assertEquals(ForecastDisplay.Off, videoBackgroundSettingsFromJson(old).dashboardForecast)
        assertEquals(ForecastDisplay.Off, videoBackgroundSettingsFromJson(old).screensaverForecast)
        for (key in listOf("dashboardForecast", "screensaverForecast")) {
            assertThrows(IllegalArgumentException::class.java) { videoBackgroundSettingsFromJson(settings.toJson().put(key, "weekly")) }
            assertThrows(IllegalArgumentException::class.java) { videoBackgroundSettingsFromJson(settings.toJson().put(key, JSONObject.NULL)) }
        }
    }

    @Test fun playlistMembershipAndIndependentOpacityAreValidated() {
        val settings = VideoBackgroundSettings(videos = listOf(video), playlists = listOf(VideoPlaylist("one", "One", listOf(video.id), true)),
            focusedCardOpacity = 90, unfocusedCardOpacity = 10)
        assertEquals(settings, videoBackgroundSettingsFromJson(settings.toJson()))
        assertEquals(settings.copy(focusedCardOpacity = null), videoBackgroundSettingsFromJson(settings.copy(focusedCardOpacity = null).toJson()))
        for (ids in listOf(listOf("missing"), listOf(video.id, video.id))) {
            assertThrows(IllegalArgumentException::class.java) { videoBackgroundSettingsFromJson(settings.copy(
                playlists = listOf(VideoPlaylist("one", "One", ids))).toJson()) }
        }
        for (key in listOf("focusedCardOpacity", "unfocusedCardOpacity", "popupCardOpacity")) for (value in listOf(-1, 101, 50.5)) {
            assertThrows(IllegalArgumentException::class.java) { videoBackgroundSettingsFromJson(settings.toJson().put(key, value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { videoBackgroundSettingsFromJson(settings.copy(
            playlists = List(25) { VideoPlaylist("p$it", "Playlist", emptyList()) }).toJson()) }
        assertThrows(IllegalArgumentException::class.java) { videoBackgroundSettingsFromJson(settings.copy(
            playlists = listOf(VideoPlaylist("same", "One", emptyList()), VideoPlaylist("same", "Two", emptyList()))).toJson()) }
    }
}
