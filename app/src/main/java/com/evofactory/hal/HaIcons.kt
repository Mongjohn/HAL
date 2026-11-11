package com.evofactory.hal

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// Material Design Icons (pictogrammers.com, Apache 2.0), the icons of Home Assistant: the font
// is res/font/mdi.ttf, and assets/mdi_icons.txt lists each icon's name and character
private val MdiFont = FontFamily(Font(R.font.mdi))

@Volatile
private var mdiCharacters: Map<String, Int>? = null

private suspend fun loadMdiCharacters(context: Context): Map<String, Int> = mdiCharacters ?: withContext(Dispatchers.IO) {
    context.assets.open("mdi_icons.txt").bufferedReader().useLines { lines ->
        lines.associate { line -> line.substringBefore(' ') to line.substringAfter(' ').toInt(16) }
    }
}.also { mdiCharacters = it }

/**
 * A Material Design Icons icon by its Home Assistant name, e.g. "mdi:thermometer". Takes its
 * [size] even when the icon is unknown (e.g. from a custom icon set), so rows stay aligned; shows
 * [fallback] instead, if given.
 */
@Composable
fun MdiIcon(name: String, modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = Color.Unspecified, fallback: String? = null) {
    val context = LocalContext.current
    val characters by produceState(mdiCharacters, context) { value = loadMdiCharacters(context) }
    val character = (listOf(name) + listOfNotNull(fallback))
        .firstNotNullOfOrNull { characters?.get(it.removePrefix("mdi:").removePrefix("hass:")) }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (character != null) {
            val fontSize = with(LocalDensity.current) { size.toSp() }
            Text(
                String(Character.toChars(character)),
                style = TextStyle(fontFamily = MdiFont, fontSize = fontSize, lineHeight = fontSize),
                color = color,
            )
        }
    }
}

/** Home Assistant's icon for an entity that has none set: by its domain, and for sensors by type and state */
fun defaultHaIcon(domain: String, deviceClass: String?, state: String): String = when (domain) {
    "scene" -> "mdi:palette"
    "script" -> "mdi:script-text"
    "button", "input_button" -> "mdi:button-pointer"
    "weather" -> weatherIcon(state)
    "sensor" -> when (deviceClass) {
        "battery" -> batteryIcon(state)
        null -> "mdi:eye"
        else -> SensorIcons[deviceClass] ?: "mdi:eye"
    }
    "binary_sensor" -> binarySensorType(deviceClass).let { if (state == "on") it.onIcon else it.offIcon }
    in OnOffIcons -> OnOffIcons.getValue(domain).let { (on, off) -> if (state == "on") on else off }
    "cover" -> if (state == "open" || state == "opening") "mdi:window-shutter-open" else "mdi:window-shutter"
    "lock" -> if (state == "unlocked") "mdi:lock-open" else "mdi:lock"
    else -> DomainIcons[domain] ?: "mdi:bookmark"
}

// Entities from dashboards, by domain: when on, then off
private val OnOffIcons = mapOf(
    "light" to ("mdi:lightbulb" to "mdi:lightbulb-outline"),
    "switch" to ("mdi:toggle-switch" to "mdi:toggle-switch-off"),
    "input_boolean" to ("mdi:toggle-switch" to "mdi:toggle-switch-off"),
    "fan" to ("mdi:fan" to "mdi:fan-off"),
)

// From Home Assistant's icons for each domain
private val DomainIcons = mapOf(
    "alarm_control_panel" to "mdi:shield-home",
    "automation" to "mdi:robot",
    "button" to "mdi:gesture-tap-button",
    "calendar" to "mdi:calendar",
    "camera" to "mdi:video",
    "climate" to "mdi:thermostat",
    "counter" to "mdi:counter",
    "date" to "mdi:calendar-clock",
    "datetime" to "mdi:calendar-clock",
    "device_tracker" to "mdi:account",
    "event" to "mdi:eye-check",
    "humidifier" to "mdi:air-humidifier",
    "input_button" to "mdi:gesture-tap-button",
    "input_number" to "mdi:ray-vertex",
    "input_select" to "mdi:format-list-bulleted",
    "input_text" to "mdi:form-textbox",
    "lawn_mower" to "mdi:robot-mower",
    "media_player" to "mdi:cast",
    "number" to "mdi:ray-vertex",
    "person" to "mdi:account",
    "remote" to "mdi:remote",
    "select" to "mdi:format-list-bulleted",
    "siren" to "mdi:bullhorn",
    "sun" to "mdi:white-balance-sunny",
    "text" to "mdi:form-textbox",
    "timer" to "mdi:timer-outline",
    "todo" to "mdi:clipboard-list",
    "update" to "mdi:package-up",
    "vacuum" to "mdi:robot-vacuum",
    "valve" to "mdi:valve",
    "water_heater" to "mdi:thermometer",
    "zone" to "mdi:map-marker",
)

/** A binary sensor's state in words, by what it detects, e.g. "Open" for a door; null if neither on nor off */
fun binarySensorLabel(deviceClass: String?, state: String): String? = when (state) {
    "on" -> binarySensorType(deviceClass).on
    "off" -> binarySensorType(deviceClass).off
    else -> null
}

private class BinarySensorType(val offIcon: String, val onIcon: String, val off: String, val on: String)

private fun binarySensorType(deviceClass: String?) = BinarySensorTypes[deviceClass] ?: DefaultBinarySensor

private val DefaultBinarySensor = BinarySensorType("mdi:radiobox-blank", "mdi:checkbox-marked-circle", "Off", "On")

// From Home Assistant's binary sensor icons and states (homeassistant/components/binary_sensor/
// icons.json and strings.json): icon when off, then on, and the state in words when off, then on
private val BinarySensorTypes = mapOf(
    "battery" to BinarySensorType("mdi:battery", "mdi:battery-outline", "Normal", "Low"),
    "battery_charging" to BinarySensorType("mdi:battery", "mdi:battery-charging", "Not charging", "Charging"),
    "carbon_monoxide" to BinarySensorType("mdi:smoke-detector", "mdi:smoke-detector-alert", "Clear", "Detected"),
    "cold" to BinarySensorType("mdi:thermometer", "mdi:snowflake", "Normal", "Cold"),
    "connectivity" to BinarySensorType("mdi:close-network-outline", "mdi:check-network-outline", "Disconnected", "Connected"),
    "door" to BinarySensorType("mdi:door-closed", "mdi:door-open", "Closed", "Open"),
    "garage_door" to BinarySensorType("mdi:garage", "mdi:garage-open", "Closed", "Open"),
    "gas" to BinarySensorType("mdi:check-circle", "mdi:alert-circle", "Clear", "Detected"),
    "glass_break" to BinarySensorType("mdi:glass-fragile", "mdi:glass-fragile", "Clear", "Glass break detected"),
    "heat" to BinarySensorType("mdi:thermometer", "mdi:fire", "Normal", "Hot"),
    "light" to BinarySensorType("mdi:brightness-5", "mdi:brightness-7", "No light", "Light detected"),
    "lock" to BinarySensorType("mdi:lock", "mdi:lock-open", "Locked", "Unlocked"),
    "moisture" to BinarySensorType("mdi:water-off", "mdi:water", "Dry", "Wet"),
    "motion" to BinarySensorType("mdi:motion-sensor-off", "mdi:motion-sensor", "Clear", "Detected"),
    "moving" to BinarySensorType("mdi:octagon", "mdi:arrow-right", "Not moving", "Moving"),
    "occupancy" to BinarySensorType("mdi:home-outline", "mdi:home", "Clear", "Detected"),
    "opening" to BinarySensorType("mdi:square", "mdi:square-outline", "Closed", "Open"),
    "plug" to BinarySensorType("mdi:power-plug-off", "mdi:power-plug", "Unplugged", "Plugged in"),
    "power" to BinarySensorType("mdi:power-plug-off", "mdi:power-plug", "Off", "On"),
    "presence" to BinarySensorType("mdi:home-outline", "mdi:home", "Away", "Home"),
    "problem" to BinarySensorType("mdi:check-circle", "mdi:alert-circle", "OK", "Problem"),
    "running" to BinarySensorType("mdi:stop", "mdi:play", "Not running", "Running"),
    "safety" to BinarySensorType("mdi:check-circle", "mdi:alert-circle", "Safe", "Unsafe"),
    "smoke" to BinarySensorType("mdi:smoke-detector-variant", "mdi:smoke-detector-variant-alert", "Clear", "Detected"),
    "sound" to BinarySensorType("mdi:music-note-off", "mdi:music-note", "Clear", "Detected"),
    "tamper" to BinarySensorType("mdi:check-circle", "mdi:alert-circle", "Clear", "Tampering detected"),
    "update" to BinarySensorType("mdi:package", "mdi:package-up", "Up-to-date", "Update available"),
    "vibration" to BinarySensorType("mdi:crop-portrait", "mdi:vibrate", "Clear", "Detected"),
    "window" to BinarySensorType("mdi:window-closed", "mdi:window-open", "Closed", "Open"),
)

/** Icon of a weather condition, e.g. "partlycloudy", as Home Assistant shows it */
fun weatherIcon(condition: String?): String = WeatherConditions[condition]?.first ?: "mdi:weather-cloudy-alert"

/** A weather condition in words, e.g. "Partly cloudy"; null if unknown */
fun weatherLabel(condition: String?): String? = WeatherConditions[condition]?.second

// Home Assistant's weather conditions: icon and words
private val WeatherConditions = mapOf(
    "clear-night" to ("mdi:weather-night" to "Clear"),
    "cloudy" to ("mdi:weather-cloudy" to "Cloudy"),
    "exceptional" to ("mdi:alert-circle-outline" to "Exceptional"),
    "fog" to ("mdi:weather-fog" to "Fog"),
    "hail" to ("mdi:weather-hail" to "Hail"),
    "lightning" to ("mdi:weather-lightning" to "Lightning"),
    "lightning-rainy" to ("mdi:weather-lightning-rainy" to "Thunderstorm"),
    "partlycloudy" to ("mdi:weather-partly-cloudy" to "Partly cloudy"),
    "pouring" to ("mdi:weather-pouring" to "Pouring rain"),
    "rainy" to ("mdi:weather-rainy" to "Rain"),
    "snowy" to ("mdi:weather-snowy" to "Snow"),
    "snowy-rainy" to ("mdi:weather-snowy-rainy" to "Sleet"),
    "sunny" to ("mdi:weather-sunny" to "Sunny"),
    "windy" to ("mdi:weather-windy" to "Windy"),
    "windy-variant" to ("mdi:weather-windy-variant" to "Windy and cloudy"),
)

/** Battery level in steps of 10 %, as Home Assistant shows it */
private fun batteryIcon(state: String): String {
    val level = state.toDoubleOrNull() ?: return "mdi:battery-unknown"
    return when (val tens = (level / 10).roundToInt() * 10) {
        in Int.MIN_VALUE..0 -> "mdi:battery-outline"
        in 100..Int.MAX_VALUE -> "mdi:battery"
        else -> "mdi:battery-$tens"
    }
}

// From Home Assistant's sensor icons (homeassistant/components/sensor/icons.json)
private val SensorIcons = mapOf(
    "apparent_power" to "mdi:flash",
    "aqi" to "mdi:air-filter",
    "atmospheric_pressure" to "mdi:thermometer-lines",
    "carbon_dioxide" to "mdi:molecule-co2",
    "carbon_monoxide" to "mdi:molecule-co",
    "current" to "mdi:current-ac",
    "data_rate" to "mdi:transmission-tower",
    "data_size" to "mdi:database",
    "date" to "mdi:calendar",
    "distance" to "mdi:arrow-left-right",
    "duration" to "mdi:progress-clock",
    "energy" to "mdi:lightning-bolt",
    "energy_storage" to "mdi:car-battery",
    "frequency" to "mdi:sine-wave",
    "gas" to "mdi:meter-gas",
    "humidity" to "mdi:water-percent",
    "illuminance" to "mdi:brightness-5",
    "irradiance" to "mdi:sun-wireless",
    "moisture" to "mdi:water-percent",
    "monetary" to "mdi:cash",
    "nitrogen_dioxide" to "mdi:molecule",
    "nitrogen_monoxide" to "mdi:molecule",
    "nitrous_oxide" to "mdi:molecule",
    "ozone" to "mdi:molecule",
    "ph" to "mdi:ph",
    "pm1" to "mdi:molecule",
    "pm10" to "mdi:molecule",
    "pm25" to "mdi:molecule",
    "power" to "mdi:flash",
    "power_factor" to "mdi:angle-acute",
    "precipitation" to "mdi:weather-rainy",
    "precipitation_intensity" to "mdi:weather-pouring",
    "pressure" to "mdi:gauge",
    "reactive_power" to "mdi:flash",
    "signal_strength" to "mdi:wifi",
    "sound_pressure" to "mdi:ear-hearing",
    "speed" to "mdi:speedometer",
    "sulphur_dioxide" to "mdi:molecule",
    "temperature" to "mdi:thermometer",
    "timestamp" to "mdi:clock",
    "volatile_organic_compounds" to "mdi:molecule",
    "volatile_organic_compounds_parts" to "mdi:molecule",
    "voltage" to "mdi:sine-wave",
    "volume" to "mdi:car-coolant-level",
    "volume_storage" to "mdi:storage-tank",
    "water" to "mdi:water",
    "weight" to "mdi:weight",
    "wind_direction" to "mdi:compass-rose",
    "wind_speed" to "mdi:weather-windy",
)
