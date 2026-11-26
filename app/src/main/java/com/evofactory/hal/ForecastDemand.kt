package com.evofactory.hal

internal data class ForecastDemand(val hourly: Boolean = false, val daily: Boolean = false) {
    operator fun plus(other: ForecastDemand) = ForecastDemand(hourly || other.hourly, daily || other.daily)
    val types get() = listOfNotNull("hourly".takeIf { hourly }, "daily".takeIf { daily })
}

internal fun forecastDemand(vararg modes: ForecastDisplay, todaySummary: Boolean = false): ForecastDemand =
    ForecastDemand(modes.any { it.showsHourly }, modes.any { it.showsDaily } || todaySummary && modes.any { it != ForecastDisplay.Off })
