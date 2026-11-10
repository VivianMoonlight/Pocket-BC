package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.*
import org.json.JSONArray
import org.json.JSONObject

internal fun BcNotificationSettings.toRulesJson(): String = JSONObject().apply {
    put("version", 1)
    put("room", suppressRoomMessagesWhenForeground)
    put("direct", suppressDirectMessagesWhenForeground)
    put("system", suppressSystemAlertsWhenForeground)
    put("systemLevel", systemLevel.name)
    put("matrix", JSONObject(matrix.mapValues { it.value.name }))
    put("rules", JSONArray().apply {
        rules.forEach { rule ->
            put(JSONObject().apply {
                put("id", rule.id)
                put("name", rule.name)
                put("enabled", rule.enabled)
                put("all", rule.matchAll)
                put("member", rule.memberNumber)
                put("identity", rule.identity?.name)
                put("type", rule.type?.name)
                put("keyword", rule.keyword)
                put("self", rule.involvesSelf)
                put("level", rule.level.name)
            })
        }
    })
}.toString()

internal fun readNotificationRules(json: String?, fallback: BcNotificationSettings): BcNotificationSettings {
    if (json == null) return fallback
    return runCatching {
        val root = JSONObject(json)
        require(root.getInt("version") == 1)
        val matrix = root.getJSONObject("matrix")
        val rules = root.getJSONArray("rules")
        fallback.copy(
            suppressRoomMessagesWhenForeground = root.getBoolean("room"),
            suppressDirectMessagesWhenForeground = root.getBoolean("direct"),
            suppressSystemAlertsWhenForeground = root.getBoolean("system"),
            systemLevel = BcNotificationLevel.valueOf(root.getString("systemLevel")),
            matrix = matrix.keys().asSequence().associateWith { BcNotificationLevel.valueOf(matrix.getString(it)) },
            rules = (0 until rules.length()).map { index ->
                val rule = rules.getJSONObject(index)
                BcNotificationRule(
                    id = rule.getString("id"),
                    name = rule.getString("name"),
                    enabled = rule.getBoolean("enabled"),
                    matchAll = rule.getBoolean("all"),
                    memberNumber = if (rule.has("member")) rule.getInt("member") else null,
                    identity = if (rule.has("identity")) BcNotificationIdentity.valueOf(rule.getString("identity")) else null,
                    type = if (rule.has("type")) BcMessageKind.valueOf(rule.getString("type")) else null,
                    keyword = rule.getString("keyword"),
                    involvesSelf = if (rule.has("self")) rule.getBoolean("self") else null,
                    level = BcNotificationLevel.valueOf(rule.getString("level")),
                )
            },
        )
    }.getOrDefault(fallback)
}
