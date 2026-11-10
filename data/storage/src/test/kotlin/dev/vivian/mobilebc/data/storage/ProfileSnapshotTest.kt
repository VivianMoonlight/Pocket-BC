package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ProfileSnapshotTest {
    private fun profile(time: Long? = 200) = BcContactProfile(20, "Target", firstSeenAtMillis = 1,
        lastSeenAtMillis = time ?: 300, snapshotUpdatedAtMillis = time,
        ownerRelation = BcProfileRelation("Owner", 30),
        loverRelations = listOf(BcProfileRelation("Lover", 40), BcProfileRelation("NPC")),
        portrait = time?.let { BcProfilePortrait("R133", "Female3DCG", listOf("Kneel"),
            listOf(BcCharacterItem("Cloth", "Shirt", "[\"Red\"]", propertyJson = "{\"Type\":1}")), it) })

    @Test fun rawNameSurvivesNicknameObservationsAndRelationsKeepStageAndStart() {
        val relation = BcProfileRelation("Lover", 40, 2, 12345L)
        val saved = profile().copy(characterName = "OriginalName", nickname = "Nickname", loverRelations = listOf(relation)).toEntity(10, null)
        val observation = BcContactProfile(20, "New nickname", firstSeenAtMillis = 1, lastSeenAtMillis = 500)
        val restored = observation.toEntity(10, saved).toContactProfile()
        assertEquals("OriginalName", restored.characterName)
        assertEquals(relation, restored.loverRelations!!.single())
        val legacy = decodeProfileOwner("""{"name":"Owner","memberNumber":30}""")!!
        assertNull(legacy.stage)
        assertNull(legacy.startEpochMillis)
    }

    @Test fun nameMigrationPreservesExistingProfiles() {
        val statements = mutableListOf<String>()
        val database = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args -> check(method.name == "execSQL"); statements += args!![0] as String; null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        MIGRATION_17_18.migrate(database)
        assertEquals(listOf("ALTER TABLE contact_profiles ADD COLUMN characterName TEXT"), statements)
    }

    @Test fun roundTripAndObservationPreserveLatestOutfitAndRelations() {
        val saved = profile().toEntity(10, null)
        val observed = BcContactProfile(20, "Target", firstSeenAtMillis = 1, lastSeenAtMillis = 500).toEntity(10, saved)
        val stale = profile(100).toEntity(10, observed).toContactProfile()
        assertEquals(profile().portrait, stale.portrait)
        assertEquals(profile().loverRelations, stale.loverRelations)
        assertEquals(profile().ownerRelation, stale.ownerRelation)
        val changed = profile(600).copy(portrait = profile(600).portrait!!.copy(appearance = emptyList()),
            ownerRelation = null, loverRelations = emptyList()).toEntity(10, observed).toContactProfile()
        assertTrue(changed.portrait!!.appearance.isEmpty())
        assertTrue(changed.loverRelations!!.isEmpty())
        assertNull(changed.ownerRelation)
    }

    @Test fun malformedSnapshotDoesNotLoseTextAndLegacyNamesRemainAvailable() {
        val legacy = profile().copy(description = "Bio", ownerName = "Legacy owner", loverNames = listOf("Legacy lover"))
            .toEntity(10, null).copy(ownerRelationJson = "bad", loverRelationsJson = "bad", portraitJson = "bad").toContactProfile()
        assertEquals("Bio", legacy.description)
        assertEquals("Legacy owner", legacy.ownerName)
        assertEquals(listOf("Legacy lover"), legacy.loverNames)
        assertNull(legacy.portrait)
        assertNull(legacy.loverRelations)
    }

    @Test fun snapshotsUseTheAccountAndTargetCompositeKey() {
        val first = profile().toEntity(10, null)
        val second = profile().toEntity(11, null)
        assertEquals(first.memberNumber, second.memberNumber)
        assertNotEquals(first.ownerMemberNumber, second.ownerMemberNumber)
    }

    @Test fun migrationAddsOnlyNullableSnapshotColumns() {
        val statements = mutableListOf<String>()
        val database = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args -> check(method.name == "execSQL"); statements += args!![0] as String; null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        MIGRATION_16_17.migrate(database)
        assertEquals(listOf("ownerRelationJson", "loverRelationsJson", "portraitJson").map {
            "ALTER TABLE contact_profiles ADD COLUMN `$it` TEXT"
        }, statements)
    }
}
