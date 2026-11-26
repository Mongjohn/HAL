package com.evofactory.hal

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebConfigurationTest {
    private val storage = mutableMapOf<String, Any?>()
    private val prefs = LauncherPrefs(preferences(storage))
    private val actions = mutableListOf<Pair<String, String>>()
    private var changes = 0
    private val api = WebConfiguration(prefs, { JSONObject() }, { changes++ }, { name, id -> actions += name to id })

    private fun read() = runBlocking { api.request(null) }

    @Test fun preparingEncodedDashboardSaveIsReadOnlyAndRestoreStillConsumesItsCheckpoint() {
        val old = DashboardLayouts(home = seedDashboardLayout(null, emptyList(), 1, false))
        val next = old.copy(home = old.home!!.copy(names = mapOf("sensor.one" to "New name")))
        val plan = prefs.snapshotDashboardSave(next, old, "home")
        assertFalse(storage.containsKey("dashboard_layouts"))
        prefs.saveDashboardLayouts(next, old, "home", encoded = plan.encode())
        assertEquals(next, prefs.dashboardLayouts)
        assertEquals(old.home, prefs.previousDashboardSurface("home"))
        val restore = prefs.snapshotDashboardSave(old, next, "home", restoring = true)
        prefs.saveDashboardLayouts(old, next, "home", restoring = true, encoded = restore.encode())
        assertEquals(old, prefs.dashboardLayouts)
        assertNull(prefs.previousDashboardSurface("home"))
    }

    @Test fun closingEditorDuringLoginVerificationDoesNotSaveAndRevokesNewLogin() = runBlocking {
        var active = true
        val revoked = mutableListOf<String>()
        val integration = object : ConfigurationIntegrations() {
            override suspend fun loginHa(url: String, username: String, password: String): HaLoginStep = HaLoginStep.LoggedIn("new-refresh")
            override suspend fun verifyHa(server: HaServer) { active = false }
            override suspend fun revokeHa(server: HaServer) { revoked += server.refreshToken }
        }
        val configuration = WebConfiguration(prefs, { JSONObject() }, { changes++ }, { _, _ -> }, integrations = integration)
        val revision = configuration.request(null).getString("revision")
        val body = JSONObject().put("revision", revision).put("section", "ha-login").put("values",
            JSONObject().put("method", "password").put("url", "http://ha.example:8123").put("username", "user").put("password", "secret"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { configuration.request(body) { active } } }
        assertEquals("", prefs.haRefreshToken)
        assertEquals(listOf("new-refresh"), revoked)
        assertEquals(0, changes)
    }

    @Test fun preferenceWrappersShareDecodesAndMarkerChangesDoNotReplaceConfiguration() {
        val shared = preferences(storage)
        val activity = LauncherPrefs(shared)
        val dream = LauncherPrefs(shared)
        activity.videoBackgrounds = VideoBackgroundSettings()
        val initial = activity.videoBackgrounds
        assertSame(initial, dream.videoBackgrounds)
        activity.lastDashboardArtwork = "hal-artwork:example"
        assertSame(initial, dream.videoBackgrounds)
        activity.videoBackgrounds = initial.copy(revision = 7)
        assertNotSame(initial, dream.videoBackgrounds)
        assertSame(activity.videoBackgrounds, dream.videoBackgrounds)
    }

    @Test fun aConcurrentPreferenceChangeDuringOffThreadDeviceProbeCannotCommitAStaleSave() = runBlocking {
        prefs.haToken = "old-token"
        var interfere = false
        val configuration = WebConfiguration(prefs, {
            if (interfere) prefs.haToken = "other-editor-token"
            JSONObject()
        }, { changes++ }, { _, _ -> })
        val revision = configuration.request(null).getString("revision")
        interfere = true
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { configuration.request(JSONObject().put("revision", revision).put("section", "weather")
                .put("values", JSONObject().put("dashboard", "daily").put("card", "daily").put("screensaver", "daily"))) }
        }
        assertEquals(ForecastDisplay.Off, prefs.videoBackgrounds.dashboardForecast)
        assertEquals("other-editor-token", prefs.haToken)
        assertEquals(0, changes)
    }

    @Test fun damagedCameraSettingsDoNotBlockConfigurationAndRemainAvailableForRepair() {
        val damaged = "[{\"url\":\"rtsp://private-camera/main\"}]"
        storage["cameras"] = damaged
        prefs.haToken = "keep-token"
        assertTrue(prefs.cameras.isEmpty())
        assertTrue(read().getJSONObject("sections").getJSONObject("cameras").getBoolean("damaged"))
        assertEquals(damaged, storage["cameras"])
        prefs.cameras = listOf(Camera("fixed", "Camera", "rtsp://camera/main"))
        assertFalse(prefs.cameraSettingsDamaged)
        assertEquals(damaged, storage["cameras_invalid_previous"])
        assertEquals("keep-token", prefs.haToken)
        assertEquals("fixed", prefs.cameras.single().id)
    }
    private fun save(section: String, value: JSONObject, revision: String = read().getString("revision")): JSONObject = runBlocking {
        api.request(JSONObject().put("revision", revision).put("section", section).put("values", value))
    }

    @Test fun readsNeverExposeSavedCredentialsAndStaleSavesDoNotOverwriteNewSettings() {
        prefs.haUrl = "http://ha.local:8123"; prefs.haToken = "private-ha-token"
        prefs.frigate = FrigateServer("https://frigate.local", "viewer", "private-password", "private-pin")
        prefs.cameras = listOf(Camera("cam", "Entré æøå", "rtsp://user:private-camera@camera.local/main", substreamUrl = "rtsps://user:private-sub@camera.local/sub"))
        val old = read()
        for (secret in listOf("private-ha-token", "private-password", "private-pin", "private-camera", "private-sub", "rtsp://")) assertFalse(old.toString().contains(secret))
        assertTrue(old.getJSONObject("sections").getJSONObject("cameras").getJSONArray("items").getJSONObject(0).getBoolean("urlSaved"))
        save("weather", JSONObject().put("dashboard", "off").put("card", "daily").put("screensaver", "hourly"))
        assertThrows(IllegalArgumentException::class.java) {
            save("weather", JSONObject().put("dashboard", "daily").put("card", "off").put("screensaver", "off"), old.getString("revision"))
        }
        assertEquals(ForecastDisplay.Off, prefs.videoBackgrounds.dashboardForecast)
        assertEquals(ForecastDisplay.Daily, prefs.videoBackgrounds.cardForecast)
        assertEquals(ForecastDisplay.Hourly, prefs.videoBackgrounds.screensaverForecast)
    }

    @Test fun legacyBasicAuthInIntegrationAddressesIsRedacted() {
        prefs.haUrl = "http://user:basic-secret@ha.local:8123"
        prefs.frigate = FrigateServer("https://viewer:other-secret@frigate.local:8971", "viewer", "stored")
        val json = read().toString()
        assertFalse(json.contains("basic-secret"))
        assertFalse(json.contains("other-secret"))
        assertEquals("http://ha.local:8123", publicIntegrationUrl(prefs.haUrl))
    }

    @Test fun uploadedArtworkDoesNotRequireAStreamingCountryAndKeepsSubscriptions() {
        save("artwork", JSONObject().put("enabled", true).put("useLocal", true).put("region", "").put("services", JSONArray().put("Netflix")))
        assertTrue(prefs.artworkSettings.useLocal)
        assertEquals(setOf("Netflix"), prefs.artworkSettings.services)
    }

    @Test fun aSecretReplacementInvalidatesAnExistingBrowserRevision() {
        prefs.haToken = "one"
        val revision = read().getString("revision")
        prefs.haToken = "two"
        assertNotEquals(revision, read().getString("revision"))
    }

    @Test fun privateRevisionDistinguishesCredentialBoundariesAndLegacyUrlPasswords() {
        prefs.haToken = "ab"; prefs.haRefreshToken = "c"
        val first = read().getString("revision")
        prefs.haToken = "a"; prefs.haRefreshToken = "bc"
        assertNotEquals(first, read().getString("revision"))
        prefs.haUrl = "http://user:one@ha.local:8123"
        val second = read().getString("revision")
        prefs.haUrl = "http://user:two@ha.local:8123"
        assertNotEquals(second, read().getString("revision"))
    }

    @Test fun loginCannotOverwriteAnImportDuringVerificationAndRevokesTheAbandonedLogin() {
        prefs.haUrl = "http://ha.local:8123"
        val revoked = mutableListOf<String>()
        val integration = object : ConfigurationIntegrations() {
            override suspend fun loginHa(url: String, username: String, password: String): HaLoginStep =
                HaLoginStep.LoggedIn("newly-issued")
            override suspend fun verifyHa(server: HaServer) {
                // Import uses a different editor endpoint and can run while this network call is suspended.
                prefs.haToken = "imported-token"; prefs.haRefreshToken = ""
            }
            override suspend fun revokeHa(server: HaServer) { revoked += server.refreshToken }
        }
        val api = WebConfiguration(prefs, { JSONObject() }, { changes++ }, { _, _ -> }, integrations = integration)
        val revision = runBlocking { api.request(null) }.getString("revision")
        val values = JSONObject().put("url", prefs.haUrl).put("method", "password").put("username", "user").put("password", "password")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.request(JSONObject().put("revision", revision).put("section", "ha-login").put("values", values)) }
        }
        assertEquals("imported-token", prefs.haToken)
        assertEquals("", prefs.haRefreshToken)
        assertEquals(listOf("newly-issued"), revoked)
        assertEquals(0, changes)
    }

    @Test fun frigateCannotOverwriteAnImportDuringVerification() {
        prefs.frigate = FrigateServer("http://frigate.local", "viewer", "old-password")
        val imported = FrigateServer("http://new-frigate.local", "other", "imported-password")
        val integration = object : ConfigurationIntegrations() {
            override suspend fun verifyFrigate(server: FrigateServer): String { prefs.frigate = imported; return "presented-pin" }
        }
        val api = WebConfiguration(prefs, { JSONObject() }, { changes++ }, { _, _ -> }, integrations = integration)
        val revision = runBlocking { api.request(null) }.getString("revision")
        val values = JSONObject().put("url", "http://frigate.local").put("username", "viewer").put("password", "updated-password")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.request(JSONObject().put("revision", revision).put("section", "frigate").put("values", values)) }
        }
        assertEquals(imported, prefs.frigate)
        assertEquals(0, changes)
    }

    @Test fun cameraEditsPreserveBlankSavedStreamsAndAllowAnExplicitSubstreamRemoval() {
        val camera = Camera("cam", "Before", "rtsps://user:secret@camera.local/main", substreamUrl = "rtsp://camera.local/sub")
        prefs.cameras = listOf(camera)
        val section = read().getJSONObject("sections").getJSONObject("cameras")
        section.getJSONArray("items").getJSONObject(0).put("name", "Køkken æøå").put("clearSub", true)
        save("cameras", section)
        assertEquals(camera.url, prefs.cameras.single().url)
        assertEquals("", prefs.cameras.single().substreamUrl)
        assertEquals("Køkken æøå", prefs.cameras.single().name)
        assertEquals(1, changes)
    }

    @Test fun invalidCameraBatchDoesNotPartiallyWriteAndHiddenSelectionIsValidated() {
        prefs.cameras = listOf(Camera("original", "Existing", "rtsp://camera.local/main"))
        val section = read().getJSONObject("sections").getJSONObject("cameras")
        section.put("showHome", false)
        section.getJSONArray("items").getJSONObject(0).put("url", "https://camera.local/page")
        assertThrows(IllegalArgumentException::class.java) { save("cameras", section) }
        assertTrue(prefs.showHomeCamera)
        assertEquals("rtsp://camera.local/main", prefs.cameras.single().url)
        assertEquals(0, changes)
    }

    @Test fun appearanceChangesKeepTheVideoLibraryAndRejectFractionalOpacityBeforeWriting() {
        prefs.videoBackgrounds = VideoBackgroundSettings(videos = listOf(BackgroundVideo("v", "Film", "https://example.org/video.mp4")))
        val appearance = read().getJSONObject("sections").getJSONObject("appearance")
        appearance.put("theme", "Calm").put("baseOpacity", 20).put("focusedOpacity", 90).put("unfocusedOpacity", 10).put("popupOpacity", 70).put("popupColumns", 2)
        save("appearance", appearance)
        assertEquals(LauncherTheme.Calm, prefs.appearanceTheme)
        assertEquals(20, prefs.dashboardCardOpacity)
        assertEquals(90, prefs.videoBackgrounds.focusedCardOpacity)
        assertEquals(10, prefs.videoBackgrounds.unfocusedCardOpacity)
        assertEquals(70, prefs.videoBackgrounds.popupCardOpacity)
        assertEquals(2, prefs.videoBackgrounds.popupCardColumns)
        assertEquals(1, prefs.videoBackgrounds.videos.size)
        val revision = prefs.videoBackgrounds.revision
        appearance.put("theme", "Glass").put("focusedOpacity", 42.5)
        assertThrows(IllegalArgumentException::class.java) { save("appearance", appearance) }
        assertEquals(LauncherTheme.Calm, prefs.appearanceTheme)
        assertEquals(revision, prefs.videoBackgrounds.revision)
    }

    @Test fun combinedForecastsSaveIndependentlyForEachSurface() {
        save("weather", JSONObject().put("dashboard", "both").put("card", "both").put("screensaver", "hourly"))
        assertEquals(ForecastDisplay.Both, prefs.videoBackgrounds.dashboardForecast)
        assertEquals(ForecastDisplay.Both, prefs.videoBackgrounds.cardForecast)
        assertEquals(ForecastDisplay.Hourly, prefs.videoBackgrounds.screensaverForecast)
    }

    @Test fun invalidPopupColumnCountDoesNotPartiallyWriteAppearance() {
        val appearance = read().getJSONObject("sections").getJSONObject("appearance")
        appearance.put("theme", "Calm").put("popupColumns", 3)
        assertThrows(IllegalArgumentException::class.java) { save("appearance", appearance) }
        assertEquals(LauncherTheme.Glass, prefs.appearanceTheme)
        assertEquals(1, prefs.videoBackgrounds.popupCardColumns)
        assertEquals(0, changes)
    }

    @Test fun homeHelperRequiresConsentAndDoesNotChangeAndroidPermissions() {
        val launcher = JSONObject().put("catchHomeKey", true).put("reopenAfterHome", false)
        assertThrows(IllegalArgumentException::class.java) { save("launcher", launcher) }
        assertFalse(prefs.catchHomeKey)
        save("launcher", launcher.put("consent", true))
        assertTrue(prefs.catchHomeKey)
        assertTrue(prefs.homeOverrideAccepted)
        assertTrue(actions.isEmpty())
    }

    @Test fun selectedTvActionsAreValidatedAndDoNotEnableAHiddenSettingsPath() {
        save("action", JSONObject().put("name", "accessibility"))
        assertEquals(listOf("accessibility" to ""), actions)
        assertThrows(IllegalArgumentException::class.java) { save("action", JSONObject().put("name", "camera-test").put("cameraId", "missing")) }
        assertThrows(IllegalArgumentException::class.java) { save("action", JSONObject().put("name", "arbitrary-intent")) }
    }

    @Test fun artworkAndHeaderActionValidationKeepsExistingChoicesOnFailure() {
        prefs.artworkSettings = ArtworkSettings(true, "DK", setOf("Netflix"), false)
        val artwork = JSONObject().put("enabled", true).put("useLocal", false).put("region", "not a country").put("services", JSONArray().put("Netflix"))
        assertThrows(IllegalArgumentException::class.java) { save("artwork", artwork) }
        assertEquals("DK", prefs.artworkSettings.region)
        save("homeassistant", JSONObject().put("topBar", JSONArray().put("scene.evening").put("script.home")))
        assertEquals(listOf("scene.evening", "script.home"), prefs.haTopBar)
        assertThrows(IllegalArgumentException::class.java) { save("homeassistant", JSONObject().put("topBar", JSONArray().put("light.room"))) }
        assertEquals(2, prefs.haTopBar.size)
    }

    @Test fun cameraUrlValidationRetainsSecureRtspAndRejectsMalformedAddresses() {
        assertTrue(validCameraStream("rtsps://user:pass@192.168.1.10:322/main"))
        assertTrue(validCameraStream("rtsp://[::1]:554/main"))
        for (bad in listOf("https://camera.local/main", "rtsp:///main", "rtsp://camera.local:99999/main", "rtsp://camera.local/main#fragment")) assertFalse(bad, validCameraStream(bad))
    }

    private fun preferences(values: MutableMap<String, Any?>): SharedPreferences {
        fun <T> stub(type: Class<T>, invoke: (Any, String, Array<out Any?>) -> Any?): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args -> invoke(proxy, method.name, args ?: emptyArray()) })!!
        return stub(SharedPreferences::class.java) { _, method, args -> when (method) {
            "contains" -> values.containsKey(args[0])
            "getString", "getBoolean", "getInt", "getLong", "getStringSet" -> values[args[0]] ?: args[1]
            "edit" -> {
                val writes = mutableMapOf<String, Any?>()
                stub(SharedPreferences.Editor::class.java) { editor, operation, params -> when (operation) {
                    "putString", "putBoolean", "putInt", "putLong", "putStringSet" -> editor.also { writes[params[0] as String] = params[1] }
                    "remove" -> editor.also { writes[params[0] as String] = null }
                    "apply", "commit" -> { writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; if (operation == "commit") true else null }
                    else -> error("Unexpected edit $operation")
                } }
            }
            else -> error("Unexpected read $method")
        } }
    }
}
