package com.evofactory.hal

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DashboardEditingTest {
    private fun layout(id: String, focused: Boolean = false, rule: CardVisibility? = null) = DashboardLayout(
        listOf(DashboardPage(id, 2, listOf(DashboardCard(
            SensorCard(id, id, listOf("sensor.temperature"), visibility = rule),
            0, 0, 1, 2, focusOnHome = focused,
        )))),
    )

    @Test fun surfaceRestorePreservesOtherLayoutAndResolvesUniqueFocus() {
        val originalHome = layout("home-old", focused = true)
        val editedHome = layout("home-new")
        val editedAssistant = layout("assistant-new", focused = true)
        val current = DashboardLayouts(editedAssistant, editedHome)
        val restored = current.withSurface("home", originalHome)
        assertEquals("home-old", restored.home!!.pages.single().title)
        assertEquals("assistant-new", restored.assistant!!.pages.single().title)
        assertFalse(restored.assistant.hasHomeFocus)
        assertTrue(restored.home.hasHomeFocus)
        assertEquals(editedAssistant.pages.single().cards.single().card.ids,
            restored.assistant.pages.single().cards.single().card.ids)
        val noFocus = current.withSurface("home", layout("home-restored"))
        assertSame(editedAssistant, noFocus.assistant)
        assertThrows(IllegalArgumentException::class.java) { current.withSurface("other", originalHome) }
    }

    @Test fun restoreHistoryMigratesAndTracksEachSurfacesLatestApply() {
        val legacyHome = layout("old Home")
        val legacyAssistant = layout("old Assistant")
        val seededHome = layout("seeded Home")
        val newHome = layout("new Home")
        val newAssistant = layout("new Assistant")
        val data = mutableMapOf("dashboard_layouts_previous" to DashboardLayouts(legacyAssistant, legacyHome).toJson().toString())
        fun prefs() = LauncherPrefs(stringPreferences(data))

        assertEquals("old Home", prefs().previousDashboardSurface("home")?.pages?.single()?.title)
        assertEquals("old Assistant", prefs().previousDashboardSurface("assistant")?.pages?.single()?.title)

        prefs().saveDashboardLayouts(DashboardLayouts(home = newHome), DashboardLayouts(home = seededHome), "home")
        assertNull(prefs().dashboardLayouts.assistant)
        assertEquals("seeded Home", prefs().previousDashboardSurface("home")?.pages?.single()?.title)
        assertEquals("old Assistant", prefs().previousDashboardSurface("assistant")?.pages?.single()?.title)

        val both = DashboardLayouts(newAssistant, newHome)
        prefs().saveDashboardLayouts(both, DashboardLayouts(legacyAssistant, newHome), "assistant")
        val restored = both.withSurface("home", requireNotNull(prefs().previousDashboardSurface("home")))
        prefs().saveDashboardLayouts(restored, both, "home", restoring = true)
        assertNull(prefs().previousDashboardSurface("home"))
        assertEquals("seeded Home", prefs().dashboardLayouts.home?.pages?.single()?.title)
        assertEquals("new Assistant", prefs().dashboardLayouts.assistant?.pages?.single()?.title)
        assertEquals("old Assistant", prefs().previousDashboardSurface("assistant")?.pages?.single()?.title)

        prefs().saveDashboardLayouts(restored.withSurface("assistant", layout("imported")), restored)
        assertNull(prefs().previousDashboardSurface("assistant"))
    }

    @Test fun appearanceThemeDefaultsToGlassAndPersistsDark() {
        val data = mutableMapOf("theme" to "Classic")
        fun prefs() = LauncherPrefs(stringPreferences(data))
        assertEquals(LauncherTheme.Glass, prefs().appearanceTheme)
        prefs().appearanceTheme = LauncherTheme.GlassDark
        assertEquals(LauncherTheme.GlassDark, prefs().appearanceTheme)
        prefs().appearanceTheme = LauncherTheme.Calm
        assertEquals(LauncherTheme.Calm, prefs().appearanceTheme)
        data["appearance_theme"] = "unsupported"
        assertEquals(LauncherTheme.Glass, prefs().appearanceTheme)
    }

    private fun stringPreferences(values: MutableMap<String, String>): SharedPreferences {
        fun <T> stub(type: Class<T>, invoke: (Any, String, Array<out Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                invoke(proxy, method.name, args ?: emptyArray())
            })!!

        return stub(SharedPreferences::class.java) { _, method, args ->
            when (method) {
                "contains" -> values.containsKey(args[0] as String)
                "getString" -> values[args[0] as String] ?: args[1]
                "edit" -> {
                    val writes = mutableMapOf<String, String?>()
                    stub(SharedPreferences.Editor::class.java) { editor, operation, params ->
                        when (operation) {
                            "putString" -> editor.also { writes[params[0] as String] = params[1] as String }
                            "remove" -> editor.also { writes[params[0] as String] = null }
                            "apply" -> writes.forEach { (key, value) ->
                                if (value == null) values.remove(key) else values[key] = value
                            }
                            else -> error("Unexpected preferences edit: $operation")
                        }
                    }
                }
                else -> error("Unexpected preferences read: $method")
            }
        }
    }

    @Test fun homeDestinationRequiresVisibleTargetAndDoesNotSelectAssistantForMissingState() {
        val hidden = CardVisibility.State("sensor.mode", listOf("home"), excluded = false)
        val layouts = DashboardLayouts(assistant = layout("assistant", true, hidden), home = layout("home"))
        assertNull(layouts.visibleHomeFocusSurface(emptyMap()))
        assertEquals("assistant", layouts.visibleHomeFocusSurface(mapOf("sensor.mode" to entity("home"))))
        assertNull(layouts.visibleHomeFocusSurface(mapOf("sensor.mode" to entity("away"))))
        assertEquals("home", DashboardLayouts(home = layout("home", true)).visibleHomeFocusSurface(emptyMap()))
        assertNull(DashboardLayouts(home = layout("home")).visibleHomeFocusSurface(emptyMap()))
    }

    private fun entity(state: String) = HaEntity("sensor.mode", state, JSONObject())

    @Test fun settingsChoicesNeverContainSecretsAndSelectionRoundTripsUnicode() {
        val file = SettingsFile(
            login = HaServer("https://private.example", "secret-token", "secret-refresh"),
            username = "private-user",
            dashboards = DashboardLayouts(home = layout("Entré æøå")),
            sensors = listOf(NamedEntity("sensor.temperature", "Kældergang")),
        )
        val choices = settingsChoices(file).toString()
        assertFalse(choices.contains("secret"))
        assertFalse(choices.contains("private"))
        val selected = selectedSettings(file, JSONArray(listOf("dashboards", "sensor:sensor.temperature")))
        assertNull(selected.login)
        val restored = requireNotNull(settingsFromJson(JSONObject(selected.toJson().toString())))
        assertEquals("Entré æøå", restored.dashboards!!.home!!.pages.single().title)
        assertEquals("Kældergang", restored.sensors.single().name)
        assertThrows(IllegalArgumentException::class.java) { selectedSettings(file, JSONArray(listOf("invented"))) }
        assertNull(settingsFromJson(file.toJson().put("version", 2)))
        assertNull(settingsFromJson(file.toJson().put("dashboards", "invalid")))
        assertNull(settingsFromJson(file.toJson().put("dashboards", JSONObject()
            .put("version", 1).put("home", JSONObject()))))
    }
}
