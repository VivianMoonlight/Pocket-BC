package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcSender
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactProfileProjectionTest {
    @Test
    fun lianUpdatePreservesBcProfileAndSurvivesOlderRoomSnapshot() {
        val existing = profile("Alice", 300).copy(
            labelColor = "#12ab34", description = "BC bio", avatarUrl = "https://i.imgur.com/old.png",
        ).toEntity(9999, null)
        val update = profile("Alice").copy(
            avatarUrl = "https://i.imgur.com/new.png", signature = "New",
            lastSeenAtMillis = 500, lianUpdatedAtMillis = 500,
        ).toEntity(9999, existing)
        assertEquals("#12ab34", update.labelColor)
        assertEquals("BC bio", update.description)
        assertEquals("https://i.imgur.com/new.png", update.avatarUrl)
        val staleRoom = profile("Alice", 400).toEntity(9999, update)
        assertEquals("https://i.imgur.com/new.png", staleRoom.avatarUrl)
        assertEquals("New", staleRoom.signature)
        val removed = profile("Alice").copy(
            avatarUrl = "", signature = "", lianUpdatedAtMillis = 600, lastSeenAtMillis = 600,
        ).toEntity(9999, staleRoom)
        assertEquals("", removed.avatarUrl)
        assertEquals("", removed.signature)
    }

    @Test
    fun lianTimestampMigrationDoesNotRewriteExistingProfiles() {
        val statements = mutableListOf<String>()
        val database = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            check(method.name == "execSQL")
            statements += args!![0] as String
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        MIGRATION_15_16.migrate(database)
        assertEquals(listOf("ALTER TABLE contact_profiles ADD COLUMN lianUpdatedAtMillis INTEGER"), statements)
    }

    @Test
    fun appearanceFieldsSurviveObservationsAndStaleSnapshotsButCanBeCleared() {
        val saved = profile("Alice", 300).copy(
            labelColor = "#12ab34",
            avatarUrl = "https://example.com/avatar.png",
            signature = "Hello",
        ).toEntity(9999, null)
        val observed = profile("Alice").copy(lastSeenAtMillis = 400).toEntity(9999, saved)
        val stale = profile("Alice", 200).copy(labelColor = "#ffffff").toEntity(9999, observed)
        val restored = stale.toContactProfile()
        assertEquals("#12ab34", restored.labelColor)
        assertEquals("https://example.com/avatar.png", restored.avatarUrl)
        assertEquals("Hello", restored.signature)
        val cleared = profile("Alice", 500).toEntity(9999, stale).toContactProfile()
        assertNull(cleared.labelColor)
        assertNull(cleared.avatarUrl)
        assertNull(cleared.signature)
    }

    @Test
    fun migrationAddsOptionalProfileFieldsWithoutDroppingExistingData() {
        val statements = mutableListOf<String>()
        val database = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            check(method.name == "execSQL")
            statements += args!![0] as String
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        MIGRATION_14_15.migrate(database)
        assertEquals(listOf(
            "ALTER TABLE contact_profiles ADD COLUMN labelColor TEXT",
            "ALTER TABLE contact_profiles ADD COLUMN avatarUrl TEXT",
            "ALTER TABLE contact_profiles ADD COLUMN signature TEXT",
        ), statements)
    }

    @Test
    fun sendingToKnownContactDoesNotReplaceTheirNameWithMemberNumber() {
        val existing = profile(name = "Alice").toEntity(9999, null)
        val outgoing = BcMessage(
            id = BcMessageId("outgoing"),
            conversationId = BcConversationId("message:1001"),
            address = BcMessageAddress.Direct(1001),
            sender = BcSender(9999, "Self", isSelf = true),
            kind = BcMessageKind.Beep,
            targetMemberNumber = 1001,
            text = "hello",
            timestamp = Instant.ofEpochMilli(300),
        )

        val restored = outgoing.toContactProfiles(9999).single().toEntity(9999, existing).toContactProfile()

        assertEquals("Alice", restored.name)
        assertEquals(300L, restored.lastSeenAtMillis)
    }

    @Test
    fun newerCompleteSnapshotCanClearRelationshipsAndOptionalFields() {
        val existing = profile(name = "Alice", snapshotAt = 200).copy(
            nickname = "Ally",
            ownerName = "Owner",
            loverNames = listOf("Lover"),
            title = "Title",
            description = "Description",
        ).toEntity(9999, null)

        val restored = profile(name = "Alice", snapshotAt = 300).toEntity(9999, existing).toContactProfile()

        assertNull(restored.nickname)
        assertNull(restored.ownerName)
        assertNull(restored.title)
        assertNull(restored.description)
        assertTrue(restored.loverNames.isEmpty())
        assertEquals(300L, restored.snapshotUpdatedAtMillis)
    }

    @Test
    fun olderCompleteSnapshotDoesNotOverwriteTheNewerProfile() {
        val existing = profile(name = "Alice", snapshotAt = 300).copy(ownerName = "New owner").toEntity(9999, null)
        val stale = profile(name = "Old name", snapshotAt = 200).copy(ownerName = "Old owner", nickname = "Old nickname")

        val restored = stale.toEntity(9999, existing).toContactProfile()

        assertEquals("Alice", restored.name)
        assertEquals("New owner", restored.ownerName)
        assertNull(restored.nickname)
        assertEquals(300L, restored.snapshotUpdatedAtMillis)
    }

    @Test
    fun messageObservationPreservesCompleteProfileFields() {
        val existing = profile(name = "Alice", snapshotAt = 200).copy(
            nickname = "Ally", ownerName = "Owner", loverNames = listOf("Lover"),
        ).toEntity(9999, null)

        val restored = profile(name = "Alice renamed").copy(lastSeenAtMillis = 300).toEntity(9999, existing).toContactProfile()

        assertEquals("Alice renamed", restored.name)
        assertEquals("Ally", restored.nickname)
        assertEquals("Owner", restored.ownerName)
        assertEquals(listOf("Lover"), restored.loverNames)
        assertEquals(200L, restored.snapshotUpdatedAtMillis)
    }

    private fun profile(name: String, snapshotAt: Long? = null) = BcContactProfile(
        memberNumber = 1001,
        name = name,
        firstSeenAtMillis = 100,
        lastSeenAtMillis = snapshotAt ?: 200,
        snapshotUpdatedAtMillis = snapshotAt,
    )
}
