package dev.vivian.mobilebc.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatePersistenceCompatibilityTest {
    @Test
    fun preferenceKeysKeepExistingNames() {
        val keys = file("datastore/PreferenceKeys.kt").readText()
        val dataStore = file("datastore/MobileBcStateDataStore.kt").readText()
        val credentials = file("datastore/DataStoreCredentialStore.kt").readText()

        assertTrue(dataStore.contains("name = \"mobile-bc-state\""))
        assertTrue(keys.contains("stringPreferencesKey(\"profilesJson\")"))
        assertTrue(keys.contains("stringPreferencesKey(\"lastAccountName\")"))
        assertTrue(keys.contains("stringPreferencesKey(\"desiredOnlineAccountName\")"))
        assertTrue(keys.contains("stringPreferencesKey(\"bcVersion\")"))
        assertTrue(keys.contains("booleanPreferencesKey(\"bcVersionConfirmedByUser\")"))
        assertTrue(keys.contains("booleanPreferencesKey(\"autoJoinRoomAfterReconnect\")"))
        assertTrue(keys.contains("stringPreferencesKey(\"lastRoomTarget:${'$'}accountName\")"))
        assertTrue(credentials.contains("stringPreferencesKey(\"password:${'$'}accountName\")"))
        assertTrue(credentials.contains("AndroidKeyStore"))
        assertTrue(credentials.contains("AES/GCM/NoPadding"))
        assertTrue(credentials.contains("withContext(ioDispatcher)"))
        assertTrue(credentials.contains("commitOrThrow"))
        assertFalse(credentials.contains(".getOrNull()"))
        assertFalse(credentials.contains("prefs[passwordKey(accountName)] = password.concatToString()"))
    }

    @Test
    fun profileJsonCodecWritesOnlyAccountScopedFields() {
        val profiles = listOf(
            PersistedAccountProfile(
                accountName = "alice",
                serverUrl = "https://server.example/",
                updatedAtMillis = 123L,
            ),
            PersistedAccountProfile(
                accountName = "bob",
                serverUrl = "https://server.example/",
                updatedAtMillis = 456L,
            ),
        )

        val json = StateJsonCodecs.profilesToJson(profiles)
        val parsed = StateJsonCodecs.parseProfiles(json)

        assertEquals(profiles, parsed)
        assertTrue(json.contains("accountName"))
        assertTrue(json.contains("serverUrl"))
        assertTrue(json.contains("updatedAtMillis"))
        assertFalse(json.contains("\"version\""))
        assertFalse(json.contains("versionConfirmedByUser"))
    }

    @Test
    fun malformedProfileJsonReturnsEmptyList() {
        assertTrue(StateJsonCodecs.parseProfiles(null).isEmpty())
        assertTrue(StateJsonCodecs.parseProfiles("").isEmpty())
        assertTrue(StateJsonCodecs.parseProfiles("not-json").isEmpty())
        assertFalse(StateJsonCodecs.profilesToJson(emptyList()).isBlank())
    }

    private fun file(relativePath: String) =
        java.io.File("src/main/kotlin/dev/vivian/mobilebc/data/storage", relativePath)
}
