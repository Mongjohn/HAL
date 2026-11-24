package com.evofactory.hal

import androidx.compose.ui.graphics.Color

/** HA converts color/temperature modes to rgb_color; never reuse an off light's stale color. */
internal fun reportedLightColor(entity: HaEntity?): Color? {
    if (entity?.domain != "light" || entity.state != "on") return null
    if (entity.attributes.optString("color_mode") in setOf("onoff", "brightness", "white")) return null
    val rgb = entity.attributes.optJSONArray("rgb_color") ?: return null
    if (rgb.length() != 3) return null
    val channels = (0..2).map { rgb.optDouble(it) }
    if (channels.any { !it.isFinite() || it !in 0.0..255.0 }) return null
    return Color(channels[0].toInt(), channels[1].toInt(), channels[2].toInt())
}

/** Only on, rule-visible lights contribute. Mixed colors use the normal on indicator. */
internal fun summaryLightColor(entities: List<HaEntity>, defaultOn: Color): Color? {
    val on = entities.filter { it.domain == "light" && it.state == "on" }
    if (on.isEmpty()) return null
    val colors = on.map { reportedLightColor(it) ?: defaultOn }.distinct()
    return colors.singleOrNull() ?: defaultOn
}
