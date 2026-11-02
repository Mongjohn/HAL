package com.evofactory.homeboard

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private const val HOURS_SHOWN = 6

/**
 * A weather entity: the current conditions and temperature, today's high, low and rain (from the
 * daily forecast), then the next hours (from the hourly forecast). Each part is left out when
 * this weather service doesn't give it.
 */
@Composable
fun WeatherReading(entity: HaEntity?, hourly: List<HaForecast>?, daily: List<HaForecast>?) {
    val condition = entity?.state
    val today = daily?.firstOrNull { it.time?.toLocalDate() == LocalDate.now() }
    // From the current hour on
    val now = ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS)
    val hours = hourly.orEmpty().filter { it.time?.isBefore(now) == false }.take(HOURS_SHOWN)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val rainUnit = entity?.attributes?.optString("precipitation_unit").orEmpty().ifEmpty { "mm" }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MdiIcon(weatherIcon(condition), size = 32.dp)
            Text(entity?.temperature?.let(::degrees) ?: "—", style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    weatherLabel(condition) ?: entity?.name ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (today != null) {
                    // e.g. "14° / 6° · rain 30 %" or "14° / 6° · no rain"; nothing about rain if the service doesn't say
                    val range = listOfNotNull(today.temperature, today.lowTemperature).joinToString(" / ", transform = ::degrees)
                    val rainToday = rain(today, rainUnit)?.let { "rain $it" }
                        ?: "no rain".takeIf { today.rainChance != null || today.rain != null }
                    Text(
                        listOfNotNull(range.ifEmpty { null }, rainToday).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (hours.isNotEmpty()) {
            val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
            val hourFormat = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "ha")
            // The rain line only when some hour has rain, so dry days take less room
            val rains = hours.map { rain(it, rainUnit) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                hours.forEachIndexed { index, hour ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(hour.time?.format(hourFormat).orEmpty(), style = MaterialTheme.typography.labelSmall, color = dim)
                        MdiIcon(weatherIcon(hour.condition), size = 20.dp)
                        Text(hour.temperature?.let(::degrees) ?: "—", style = MaterialTheme.typography.bodyMedium)
                        if (rains.any { it != null }) {
                            Text(rains[index].orEmpty(), style = MaterialTheme.typography.labelSmall, color = dim)
                        }
                    }
                }
            }
        }
    }
}

private fun degrees(value: Double) = "${value.roundToInt()}°"

/** Chance of rain, e.g. "30 %", or else the amount, e.g. "0.4 mm"; null when dry */
private fun rain(forecast: HaForecast, unit: String): String? {
    forecast.rainChance?.let { return if (it > 0) "${it.roundToInt()} %" else null }
    val amount = forecast.rain ?: return null
    return if (amount > 0) "${"%.1f".format(amount)} $unit" else null
}
