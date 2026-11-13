package com.evofactory.hal

private val StatefulControlDomains = setOf(
    "lock", "cover", "valve", "humidifier", "remote", "siren", "climate", "media_player", "vacuum",
)

internal fun isHaStatefulControl(id: String) =
    isHaToggle(id) || id.substringBefore('.') in StatefulControlDomains

/** The service for OK on an entity row, based on its latest state and advertised capabilities. */
internal fun haPrimaryService(id: String, state: String?, features: Int): String? {
    if (state.isNullOrEmpty() || state == "unknown" || state == "unavailable") return null
    if (isHaToggle(id)) return if (state == "on" || state == "off") "toggle" else null

    fun supports(feature: Int) = features and feature != 0
    val domain = id.substringBefore('.')
    return when (domain) {
        "lock" -> when (state) {
            "locked" -> "unlock"
            "unlocked" -> "lock"
            else -> null
        }
        // CoverEntityFeature and ValveEntityFeature share the OPEN and CLOSE bits.
        "cover", "valve" -> when {
            state == "closed" && supports(1) -> "open_$domain"
            state == "open" && supports(2) -> "close_$domain"
            else -> null
        }
        "humidifier", "remote" -> when (state) {
            "on" -> "turn_off"
            "off" -> "turn_on"
            else -> null
        }
        // Feature bits below follow the corresponding Home Assistant entity feature enums.
        "siren" -> when {
            state == "on" && supports(2) -> "turn_off"
            state == "off" && supports(1) -> "turn_on"
            else -> null
        }
        "climate" -> when {
            state == "off" && supports(256) -> "turn_on"
            state != "off" && supports(128) -> "turn_off"
            else -> null
        }
        "media_player" -> when {
            state == "playing" && supports(1) -> "media_pause"
            state == "paused" && supports(16384) -> "media_play"
            state in setOf("off", "standby") && supports(128) -> "turn_on"
            state == "idle" && supports(16384) -> "media_play"
            state in setOf("on", "idle") && supports(256) -> "turn_off"
            else -> null
        }
        "vacuum" -> when {
            state == "cleaning" && supports(16) -> "return_to_base"
            state == "cleaning" && supports(8) -> "stop"
            state in setOf("docked", "idle", "paused") && supports(8192) -> "start"
            else -> null
        }
        else -> null
    }
}
