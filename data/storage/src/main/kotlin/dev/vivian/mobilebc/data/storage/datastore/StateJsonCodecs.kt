package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.DefaultBcServerUrl
import org.json.JSONArray
import org.json.JSONObject

internal object StateJsonCodecs {
    fun parseProfiles(rawJson: String?): List<PersistedAccountProfile> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val accountName = item.optString(PROFILE_ACCOUNT_NAME)
                        .takeIf { it.isNotBlank() }
                        ?: continue
                    add(
                        PersistedAccountProfile(
                            accountName = accountName,
                            serverUrl = item.optString(PROFILE_SERVER_URL, DefaultBcServerUrl),
                            updatedAtMillis = item.optLong(PROFILE_UPDATED_AT, 0L),
                            legacyVersion = item.optString(PROFILE_VERSION),
                            legacyVersionConfirmedByUser = item.optBoolean(
                                PROFILE_VERSION_CONFIRMED_BY_USER,
                                false,
                            ),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun profilesToJson(profiles: List<PersistedAccountProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put(PROFILE_ACCOUNT_NAME, profile.accountName)
                    .put(PROFILE_SERVER_URL, profile.serverUrl)
                    .put(PROFILE_UPDATED_AT, profile.updatedAtMillis)
            )
        }
        return array.toString()
    }

    fun removeLegacyVersionFields(rawJson: String?): String? {
        if (rawJson.isNullOrBlank()) return rawJson
        return runCatching {
            val array = JSONArray(rawJson)
            var changed = false
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.has(PROFILE_VERSION)) {
                    item.remove(PROFILE_VERSION)
                    changed = true
                }
                if (item.has(PROFILE_VERSION_CONFIRMED_BY_USER)) {
                    item.remove(PROFILE_VERSION_CONFIRMED_BY_USER)
                    changed = true
                }
            }
            if (changed) array.toString() else rawJson
        }.getOrNull()
    }

    fun removeProfile(rawJson: String?, accountName: String): String? {
        if (rawJson.isNullOrBlank()) return rawJson
        return runCatching {
            val source = JSONArray(rawJson)
            val result = JSONArray()
            var changed = false
            for (index in 0 until source.length()) {
                val value = source.get(index)
                val profile = value as? JSONObject
                if (profile?.optString(PROFILE_ACCOUNT_NAME) == accountName) {
                    changed = true
                } else {
                    result.put(value)
                }
            }
            if (changed) result.toString() else rawJson
        }.getOrNull()
    }

    private const val PROFILE_ACCOUNT_NAME = "accountName"
    private const val PROFILE_SERVER_URL = "serverUrl"
    private const val PROFILE_VERSION = "version"
    private const val PROFILE_UPDATED_AT = "updatedAtMillis"
    private const val PROFILE_VERSION_CONFIRMED_BY_USER = "versionConfirmedByUser"
}
