package com.evofactory.hal

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import java.util.Locale

internal enum class ForecastDisplay(val value: String, val label: String) {
    Off("off", "Off"), Hourly("hourly", "Hourly"), Daily("daily", "5-day"), Both("both", "Hourly + 5-day");

    val showsHourly get() = this == Hourly || this == Both
    val showsDaily get() = this == Daily || this == Both
}

private const val HOURS_SHOWN = 6

/** Current weather with an optional, separately divided forecast. */
@Composable
fun WeatherReading(entity: HaEntity?, hourly: List<HaForecast>?, daily: List<HaForecast>?) {
    val condition = entity?.state
    val today = daily?.firstOrNull { it.time?.toLocalDate() == LocalDate.now() }
    val mode = LocalDashboardForecast.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val rainUnit = entity?.attributes?.optString("precipitation_unit").orEmpty().ifEmpty { "mm" }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MdiIcon(
                weatherIcon(condition),
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = weatherLabel(condition) ?: "Weather condition unavailable"
                },
                size = 32.dp,
            )
            Text(entity?.temperature?.let(::degrees) ?: "—", style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entity?.name ?: "Weather",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (today != null && mode != ForecastDisplay.Off) {
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
        ExtendedWeatherForecast(hourly, daily, mode, precipitationUnit = rainUnit)
    }
}

/** Five distinct local dates, starting today when supplied; never invent missing forecast days. */
internal fun fiveDayForecast(daily: List<HaForecast>?, today: LocalDate): List<HaForecast> = daily.orEmpty()
    .filter { it.time?.toLocalDate()?.let { date -> !date.isBefore(today) } == true }
    .sortedBy { it.time }.distinctBy { it.time?.toLocalDate() }.take(5)

internal fun hourlyForecast(hourly: List<HaForecast>?, now: ZonedDateTime): List<HaForecast> = hourly.orEmpty()
    .filter { it.time?.isBefore(now.truncatedTo(ChronoUnit.HOURS)) == false }
    .sortedBy { it.time }.distinctBy { it.time?.toInstant() }.take(HOURS_SHOWN)

@Composable
internal fun ExtendedWeatherForecast(hourly: List<HaForecast>?, daily: List<HaForecast>?,
    mode: ForecastDisplay = LocalDashboardForecast.current, compact: Boolean = false, precipitationUnit: String = "mm") {
    if (mode == ForecastDisplay.Off) return
    val current by produceVisibleState(ZonedDateTime.now()) {
        while (true) { value = ZonedDateTime.now(); kotlinx.coroutines.delay(60_000) }
    }
    val hours = hourlyForecast(hourly, current)
    val days = fiveDayForecast(daily, current.toLocalDate())
    val showHours = mode.showsHourly && hours.isNotEmpty()
    val showDays = mode.showsDaily && days.isNotEmpty()
    if (!showHours && !showDays) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(color.copy(alpha = 0.25f)))
        if (showHours) HourlyForecastRow(hours, compact, precipitationUnit)
        if (showHours && showDays) Box(Modifier.fillMaxWidth().height(1.dp).background(color.copy(alpha = 0.12f)))
        if (showDays) DailyForecastRow(days, current.toLocalDate(), compact)
    }
}

@Composable
private fun HourlyForecastRow(hours: List<HaForecast>, compact: Boolean, precipitationUnit: String) {
    val format = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "ha")
    val colors = MaterialTheme.colorScheme
    val rains = hours.map { rain(it, precipitationUnit) }
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "Hourly forecast" }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        hours.forEachIndexed { index, hour ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(hour.time?.format(format).orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                MdiIcon(weatherIcon(hour.condition), size = if (compact) 18.dp else 22.dp, color = colors.onSurfaceVariant)
                Text(hour.temperature?.let(::degrees) ?: "—", style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                if (rains.any { it != null }) Text(rains[index].orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DailyForecastRow(days: List<HaForecast>, now: LocalDate, compact: Boolean) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val format = DateTimeFormatter.ofPattern("EEE", locale)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "Daily forecast" }, horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
        days.forEach { day ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (day.time?.toLocalDate() == now) "Today" else day.time?.format(format).orEmpty(),
                    style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MdiIcon(weatherIcon(day.condition), size = if (compact) 18.dp else 22.dp,
                    color = color,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = weatherLabel(day.condition).orEmpty() })
                Text(day.temperature?.let(::degrees) ?: "—", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Text(day.lowTemperature?.let(::degrees) ?: "—", style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
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
