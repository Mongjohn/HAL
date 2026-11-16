package com.evofactory.hal

import org.json.JSONArray
import org.json.JSONObject

private const val FILE_FORMAT = "hal-settings"

/** A Home Assistant entity, with its name for showing in the list when importing */
internal data class NamedEntity(val id: String, val name: String)

/** What can be exported or imported. The login, if any, comes with its username. */
internal data class SettingsFile(
    val cameras: List<Camera> = emptyList(),
    val login: HaServer? = null,
    val username: String = "",
    val sensors: List<NamedEntity> = emptyList(),
    val actions: List<NamedEntity> = emptyList(),
    // The order of the sensors and actions together on the Assistant tab, and the actions also in
    // the top bar. Null in files from earlier versions: the sensors, then the actions, of which the
    // first ones were the top bar's.
    val order: List<String>? = null,
    val topBar: List<String>? = null,
    val frigate: FrigateServer? = null,
    // Older files stored app selection separately, before the Home Apps card.
    val appTiles: List<NamedEntity> = emptyList(),
    val dashboards: DashboardLayouts? = null,
) {
    /** Keep the settings selected in the browser. */
    fun only(keys: Set<String>) = copy(
        cameras = cameras.filter { "camera:${it.id}" in keys },
        login = login?.takeIf { LOGIN_KEY in keys },
        sensors = sensors.filter { "sensor:${it.id}" in keys },
        actions = actions.filter { "action:${it.id}" in keys },
        order = order?.filter { "sensor:$it" in keys || "action:$it" in keys },
        topBar = topBar?.filter { "action:$it" in keys },
        frigate = frigate?.takeIf { FRIGATE_KEY in keys },
        dashboards = dashboards?.takeIf { DASHBOARDS_KEY in keys },
        appTiles = appTiles.takeIf { APP_TILES_KEY in keys }.orEmpty(),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("format", FILE_FORMAT)
        .put("version", 1)
        .put("cameras", JSONArray(camerasToJson(cameras)))
        .apply {
            login?.let {
                put(
                    "homeAssistantLogin",
                    JSONObject().put("url", it.url).put("token", it.token).put("refreshToken", it.refreshToken).put("username", username),
                )
            }
        }
        .put("sensors", entitiesToJson(sensors))
        .put("actions", entitiesToJson(actions))
        .apply {
            dashboards?.let { put("dashboards", it.toJson()) }
            order?.let { put("order", JSONArray(it)) }
            topBar?.let { put("topBar", JSONArray(it)) }
            frigate?.let {
                put(
                    "frigate",
                    JSONObject().put("url", it.url).put("username", it.username).put("password", it.password).put("certPin", it.certPin),
                )
            }
        }
        .put("appTiles", entitiesToJson(appTiles))
}

private const val DASHBOARDS_KEY = "dashboards"
private const val LOGIN_KEY = "login"
private const val FRIGATE_KEY = "frigate"
private const val APP_TILES_KEY = "appTiles"

private fun entitiesToJson(entities: List<NamedEntity>) =
    JSONArray(entities.map { JSONObject().put("id", it.id).put("name", it.name) })

private fun entitiesFromJson(array: JSONArray?) = List(array?.length() ?: 0) { i ->
    array!!.getJSONObject(i).let { NamedEntity(it.getString("id"), it.optString("name", it.getString("id"))) }
}

/** Null if this isn't a settings file */
internal fun settingsFromJson(root: JSONObject): SettingsFile? = runCatching {
    if (root.optString("format") != FILE_FORMAT || root.optInt("version") != 1) return null
    val dashboards = root.optJSONObject("dashboards")
    if (root.has("dashboards") && dashboards == null) return null
    if (dashboards != null && listOf("home", "assistant").any { dashboards.has(it) && dashboards.optJSONObject(it) == null }) {
        return null
    }
    val login = root.optJSONObject("homeAssistantLogin")
    val frigate = root.optJSONObject("frigate")
    SettingsFile(
        cameras = root.optJSONArray("cameras")?.let { camerasFromJson(it.toString()) }.orEmpty(),
        login = login?.let { HaServer(it.getString("url"), it.optString("token"), it.optString("refreshToken")) },
        username = login?.optString("username").orEmpty(),
        sensors = entitiesFromJson(root.optJSONArray("sensors")),
        actions = entitiesFromJson(root.optJSONArray("actions")),
        order = root.optJSONArray("order")?.let { array -> List(array.length()) { array.getString(it) } },
        topBar = root.optJSONArray("topBar")?.let { array -> List(array.length()) { array.getString(it) } },
        frigate = frigate?.let { FrigateServer(it.getString("url"), it.optString("username"), it.optString("password"), it.optString("certPin")) },
        dashboards = dashboards?.let { DashboardLayouts.fromJson(it) },
        appTiles = entitiesFromJson(root.optJSONArray("appTiles")),
    )
}.getOrNull()

/** Browser selection metadata deliberately excludes passwords, tokens and camera URLs. */
internal fun settingsChoices(file: SettingsFile): JSONArray {
    fun item(key: String, title: String, sensitive: Boolean = false) =
        JSONObject().put("key", key).put("title", title).put("sensitive", sensitive)
    val groups = listOf(
        "Cameras" to file.cameras.map { item("camera:${it.id}", it.name, true) },
        "Home Assistant login" to listOfNotNull(file.login?.let { item(LOGIN_KEY, "Home Assistant login", true) }),
        "HAL dashboards" to listOfNotNull(file.dashboards?.let { item(DASHBOARDS_KEY, "Dashboard layouts") }),
        "Home Assistant sensors" to file.sensors.map { item("sensor:${it.id}", it.name) },
        "Home Assistant actions" to file.actions.map { item("action:${it.id}", it.name) },
        "Frigate login" to listOfNotNull(file.frigate?.let { item(FRIGATE_KEY, "Frigate login", true) }),
        "App tiles" to listOfNotNull(file.appTiles.takeIf { it.isNotEmpty() }?.let { item(APP_TILES_KEY, "App selection and order") }),
    )
    return JSONArray(groups.filter { it.second.isNotEmpty() }.map { (title, items) ->
        JSONObject().put("title", title).put("items", JSONArray(items))
    })
}

internal fun selectedSettings(file: SettingsFile, keys: JSONArray): SettingsFile {
    val choices = settingsChoices(file)
    val allowed = (0 until choices.length()).flatMap { index ->
        val items = choices.getJSONObject(index).getJSONArray("items")
        (0 until items.length()).map { items.getJSONObject(it).getString("key") }
    }.toSet()
    require(keys.length() in 1..allowed.size) { "Choose at least one setting." }
    val selected = (0 until keys.length()).map { keys.getString(it) }.toSet()
    require(selected.all { it in allowed }) { "The settings selection changed. Reload it and try again." }
    return file.only(selected)
}
