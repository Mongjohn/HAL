package com.evofactory.hal

internal enum class HaStateTone { Neutral, Active, Good, Caution, Alert, Blue, Cyan, Purple, Teal }

internal data class HaStateCue(
    val icon: String,
    val tone: HaStateTone = HaStateTone.Neutral,
    val iconOnly: Boolean = true,
)

internal fun haStateCue(domain: String, deviceClass: String?, state: String?): HaStateCue? {
    when (state) {
        null, "", "unknown" -> return HaStateCue("help-circle-outline")
        "unavailable" -> return HaStateCue("cloud-off-outline")
    }
    if (domain == "lock" || (domain == "binary_sensor" && deviceClass == "lock")) {
        val lockState = if (domain == "binary_sensor") when (state) {
            "off" -> "locked"
            "on" -> "unlocked"
            else -> state
        } else state
        return when (lockState) {
            "locked" -> HaStateCue("lock", HaStateTone.Good)
            "unlocked" -> HaStateCue("lock-open", HaStateTone.Alert)
            "locking", "unlocking", "opening" -> HaStateCue("lock-clock", HaStateTone.Caution)
            "jammed" -> HaStateCue("lock-alert", HaStateTone.Alert)
            else -> HaStateCue("help-circle-outline", iconOnly = false)
        }
    }
    if (domain == "binary_sensor" && state in setOf("on", "off")) {
        val on = state == "on"
        val tone = when (deviceClass) {
            "battery", "carbon_monoxide", "gas", "glass_break", "heat", "moisture", "problem", "safety", "smoke", "sound", "tamper" ->
                if (on) HaStateTone.Alert else HaStateTone.Good
            "connectivity" -> if (on) HaStateTone.Good else HaStateTone.Caution
            else -> if (on) HaStateTone.Active else HaStateTone.Neutral
        }
        val icon = if (deviceClass == "glass_break" && on) "alert-circle-outline" else defaultHaIcon(domain, deviceClass, state)
        return HaStateCue(icon, tone)
    }
    if (domain in ReadingDomains && deviceClass != "enum") return null

    val specific = when (domain) {
        "alarm_control_panel" -> when {
            state == "disarmed" -> HaStateCue("shield-off-outline")
            state == "triggered" -> HaStateCue("shield-alert-outline", HaStateTone.Alert)
            state.startsWith("armed_") -> HaStateCue("shield-lock-outline", HaStateTone.Good)
            state in setOf("arming", "disarming", "pending") -> HaStateCue("progress-clock", HaStateTone.Caution)
            else -> null
        }
        "cover" -> when (state) {
            "open", "closed" -> HaStateCue(defaultHaIcon(domain, deviceClass, state), if (state == "open") HaStateTone.Purple else HaStateTone.Neutral)
            "opening", "closing" -> HaStateCue("progress-clock", HaStateTone.Purple)
            else -> null
        }
        "person", "device_tracker" -> when (state) {
            "home" -> HaStateCue("home-import-outline", HaStateTone.Good)
            "not_home" -> HaStateCue("home-export-outline")
            else -> HaStateCue("map-marker", HaStateTone.Blue, iconOnly = false)
        }
        else -> null
    }
    return specific ?: when (state) {
        "on", "off" -> {
            val on = state == "on"
            val icon = when (domain) {
                "light", "switch", "input_boolean", "fan" -> defaultHaIcon(domain, deviceClass, state)
                else -> if (on) "checkbox-marked-circle-outline" else "circle-outline"
            }
            val tone = when {
                !on -> HaStateTone.Neutral
                domain == "fan" -> HaStateTone.Cyan
                domain == "humidifier" -> HaStateTone.Blue
                domain == "siren" -> HaStateTone.Alert
                domain == "update" -> HaStateTone.Caution
                else -> HaStateTone.Active
            }
            HaStateCue(icon, tone)
        }
        "playing", "running", "active" -> HaStateCue(
            "play-circle-outline", if (domain == "media_player") HaStateTone.Blue else HaStateTone.Active,
        )
        "paused" -> HaStateCue("pause-circle-outline")
        "idle", "standby", "stopped" -> HaStateCue("stop-circle-outline")
        "heating", "heat" -> HaStateCue("fire", HaStateTone.Caution)
        "cooling", "cool" -> HaStateCue("snowflake", HaStateTone.Blue)
        "fan_only" -> HaStateCue("fan", HaStateTone.Cyan)
        "dry" -> HaStateCue("water-off", HaStateTone.Caution)
        "auto" -> HaStateCue("autorenew", HaStateTone.Good)
        "heat_cool" -> HaStateCue("autorenew", HaStateTone.Active)
        "opening", "closing", "arming", "disarming", "pending", "returning", "buffering" ->
            HaStateCue("progress-clock", HaStateTone.Active)
        "open" -> HaStateCue("circle-outline", HaStateTone.Active)
        "closed", "docked", "complete", "completed" -> HaStateCue("checkbox-marked-circle-outline", HaStateTone.Good)
        "cleaning" -> HaStateCue("robot-vacuum", HaStateTone.Teal)
        "charging" -> HaStateCue("battery-charging", HaStateTone.Active)
        "above_horizon" -> HaStateCue("white-balance-sunny", HaStateTone.Active)
        "below_horizon" -> HaStateCue("weather-night")
        "error", "fault", "failed" -> HaStateCue("alert-circle-outline", HaStateTone.Alert)
        else -> HaStateCue("dots-horizontal-circle-outline", iconOnly = false)
    }
}

private val ReadingDomains = setOf(
    "sensor", "number", "input_number", "counter", "text", "input_text", "select", "input_select",
    "date", "datetime", "time", "input_datetime", "zone", "todo", "event",
)
