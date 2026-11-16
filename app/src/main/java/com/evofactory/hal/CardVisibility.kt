package com.evofactory.hal

import org.json.JSONArray
import org.json.JSONObject

private val visibilityEntityId = Regex("[a-z0-9_]+\\.[a-z0-9_]+")

sealed interface CardVisibility {
    fun entityIds(): List<String>
    fun matches(stateOf: (String) -> String?): Boolean?
    fun toJson(): JSONObject

    data class State(
        val entity: String,
        val states: List<String>,
        val excluded: Boolean,
        val homeAssistantComparison: Boolean = false,
    ) : CardVisibility {
        private val references = states.filter(visibilityEntityId::matches).takeIf { homeAssistantComparison }.orEmpty()

        override fun entityIds() = listOf(entity) + references

        override fun matches(stateOf: (String) -> String?): Boolean? {
            val state = stateOf(entity) ?: if (homeAssistantComparison) "unknown" else return null
            return (state in states || references.any { stateOf(it) == state }) != excluded
        }

        override fun toJson(): JSONObject = JSONObject().put("condition", "state").put("entity", entity)
            .put(if (excluded) "state_not" else "state", JSONArray(states)).apply {
                if (homeAssistantComparison) put("homeAssistantComparison", true)
            }
    }

    data class Group(val operation: Operation, val conditions: List<CardVisibility>) : CardVisibility {
        override fun entityIds() = conditions.flatMap { it.entityIds() }

        override fun matches(stateOf: (String) -> String?): Boolean? {
            val results = conditions.map { it.matches(stateOf) }
            return when (operation) {
                Operation.And, Operation.Not -> {
                    val all = when {
                        false in results -> false
                        null in results -> null
                        else -> true
                    }
                    if (operation == Operation.Not) all?.not() else all
                }
                Operation.Or -> when {
                    true in results -> true
                    null in results -> null
                    else -> false
                }
            }
        }

        override fun toJson(): JSONObject = JSONObject().put("condition", operation.key)
            .put("conditions", JSONArray(conditions.map { it.toJson() }))
    }

    enum class Operation(val key: String) { And("and"), Or("or"), Not("not") }
}

internal fun CardVisibility?.isVisible(entities: Map<String, HaEntity>): Boolean =
    this == null || matches { entities[it]?.state } == true

data class ItemVisibility(val rule: CardVisibility, val keepSpace: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("rule", rule.toJson()).put("keepSpace", keepSpace)
}

internal fun parseCardVisibility(json: JSONObject, fromHomeAssistant: Boolean = false, depth: Int = 0): CardVisibility {
    require(depth <= 8) { "Visibility rules can be nested up to 8 levels." }
    val type = if (fromHomeAssistant && !json.has("condition")) "state" else json.optString("condition")
    if (type == "state") {
        require(listOf("attribute", "for", "match").none(json::has)) { "Unsupported state condition option." }
        val entity = (json.opt("entity_id") ?: json.opt("entity")) as? String
        require(entity != null && entity.length <= 255 && visibilityEntityId.matches(entity)) {
            "Enter a valid entity ID for each visibility rule."
        }
        val excluded = json.has("state_not")
        require(excluded != json.has("state")) { "Choose one state comparison for each visibility rule." }
        val value = json.get(if (excluded) "state_not" else "state")
        val states = if (value is JSONArray) {
            require(value.length() in 1..32) { "Use between 1 and 32 state values per rule." }
            List(value.length()) { value.get(it) }
        } else listOf(value)
        require(states.all { it is String && it.length in 1..255 }) { "Enter a state for each visibility rule." }
        return CardVisibility.State(entity, states.filterIsInstance<String>(), excluded,
            fromHomeAssistant || json.optBoolean("homeAssistantComparison"))
    }
    val operation = CardVisibility.Operation.entries.firstOrNull { it.key == type }
        ?: throw IllegalArgumentException("Unsupported visibility condition: $type")
    val children = json.get("conditions")
    val rules = if (fromHomeAssistant && children is JSONObject) listOf(children) else {
        require(children is JSONArray && children.length() in 1..32) { "Each visibility group needs between 1 and 32 rules." }
        List(children.length()) { children.getJSONObject(it) }
    }
    return CardVisibility.Group(operation, rules.map { parseCardVisibility(it, fromHomeAssistant, depth + 1) })
}
