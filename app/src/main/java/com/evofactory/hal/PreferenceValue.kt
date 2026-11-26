package com.evofactory.hal

/** Decode only when the raw persisted value changes; no observer, IO or duplicate authority. */
internal class PreferenceValue<T>(private val decode: (String?) -> T) {
    private var initialized = false
    private var raw: String? = null
    private var result: T? = null
    @Synchronized fun read(next: String?): T {
        if (!initialized || raw != next) {
            val decoded = decode(next)
            raw = next
            result = decoded
            initialized = true
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}

internal data class CameraSettingsRead(val cameras: List<Camera>, val damaged: Boolean = false)

/** Snapshot checkpoint choices on main; encoding can run off-main before a revision-checked commit. */
internal data class DashboardSave(val layout: DashboardLayouts, val checkpoints: Map<String, DashboardLayouts>) {
    fun encode(): Map<String, String> = checkpoints.mapValues { it.value.toJson().toString() } +
        ("dashboard_layouts" to layout.toJson().toString())
}

internal fun readCameraSettings(raw: String?): CameraSettingsRead = if (raw == null) CameraSettingsRead(emptyList()) else
    try { CameraSettingsRead(camerasFromJson(raw)) }
    catch (_: org.json.JSONException) { CameraSettingsRead(emptyList(), damaged = true) }

/** Shared codecs follow the Android preference storage identity, never an Activity/context. */
internal class PreferenceDecoders {
    val cameras = PreferenceValue(::readCameraSettings)
    val video = PreferenceValue { raw -> runCatching {
        raw?.let { videoBackgroundSettingsFromJson(org.json.JSONObject(it)) }
    }.getOrNull() ?: VideoBackgroundSettings() }
    val apps = PreferenceValue { raw -> runCatching { installedAppsFromJson(raw.orEmpty()) }.getOrDefault(emptyList()) }
    val layouts = mutableMapOf<String, PreferenceValue<DashboardLayouts?>>()

    companion object {
        private val instances = mutableListOf<Pair<java.lang.ref.WeakReference<android.content.SharedPreferences>, PreferenceDecoders>>()
        @Synchronized fun forStorage(storage: android.content.SharedPreferences): PreferenceDecoders {
            instances.removeAll { it.first.get() == null }
            return instances.firstOrNull { it.first.get() === storage }?.second
                ?: PreferenceDecoders().also { instances += java.lang.ref.WeakReference(storage) to it }
        }
    }
}
