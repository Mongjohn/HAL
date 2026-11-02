package com.evofactory.homeboard

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

/** Home Assistant's icon for an entity that has none set: by its domain, and for sensors by type */
fun defaultHaIcon(domain: String, deviceClass: String?, state: String): String = when (domain) {
    "scene" -> "mdi:palette"
    "script" -> "mdi:script-text"
    "weather" -> weatherIcon(state)
    "sensor" -> when (deviceClass) {
        "battery" -> batteryIcon(state)
        null -> "mdi:eye"
        else -> SensorIcons[deviceClass] ?: "mdi:eye"
    }
    else -> "mdi:bookmark"
}

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
