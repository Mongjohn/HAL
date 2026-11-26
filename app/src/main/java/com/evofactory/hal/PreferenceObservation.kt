package com.evofactory.hal

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** Observe one typed section; unrelated marker/utility writes never execute its decoder. */
@Composable
internal fun <T> rememberPreferenceSection(prefs: LauncherPrefs, keys: Set<String>, read: () -> T): T {
    val state = remember(prefs, keys) { mutableStateOf(read()) }
    DisposableEffect(prefs, keys) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) state.value = read()
        }
        prefs.addChangeListener(listener)
        state.value = read()
        onDispose { prefs.removeChangeListener(listener) }
    }
    return state.value
}

internal val artworkPreferenceKeys = setOf("media_artwork", "artwork_region", "artwork_services", "local_artwork", "installed_apps", "hidden_list", "hidden")
internal val appPreferenceKeys = setOf("installed_apps", "order", "hidden_list", "hidden")
internal val haDisplayPreferenceKeys = setOf("ha_dashboard", "ha_bottom_row", "ha_section_columns", "ha_home_dashboard", "ha_home_columns") +
    listOf("ha_layout", "ha_home_layout").flatMap { prefix -> listOf("${prefix}_grouping", "${prefix}_alphabetical", "${prefix}_two_per_row") }
internal val haSelectionPreferenceKeys = setOf("ha_entities", "ha_top_bar", "ha_actions")
internal val cameraPreferenceKeys = setOf("cameras", "camera_selected", "camera_grid_live", "show_home_camera", "minimize_streams")
internal val appearancePreferenceKeys = setOf("dashboard_card_opacity", "appearance_theme", "apps_card_collapse", "cycle_ui_colors")
internal val launcherPreferenceKeys = setOf("show_free_memory", "catch_home_key", "reopen_after_home", "replace_home")
