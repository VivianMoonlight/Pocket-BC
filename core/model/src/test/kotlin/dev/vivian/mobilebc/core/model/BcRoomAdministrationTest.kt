package dev.vivian.mobilebc.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcRoomAdministrationTest {
    @Test
    fun createTrimsNameAndAcceptsOneAndTwentyCharacters() {
        for (name in listOf("A", "A".repeat(20))) {
            val command = validCreate(settings.copy(name = " \t$name\r\n "))
            assertEquals(name, command.settings.name)
        }
    }

    @Test
    fun nameAllowsPrintableAsciiIncludingInteriorSpaces() {
        for (character in '\u0020'..'\u007E') {
            val name = "A${character}Z"
            assertEquals(name, validCreate(settings.copy(name = name)).settings.name)
        }
    }

    @Test
    fun nameRejectsEmptyLongNonAsciiAndInteriorControlCharacters() {
        val invalidNames = listOf(
            "",
            " \t\r\n ",
            "A".repeat(21),
            "房间",
            "Café",
            "A\u0000Z",
            "A\tZ",
            "A\nZ",
            "A\u001CZ",
            "A\u007FZ",
        )
        for (name in invalidNames) {
            assertInvalid(
                validateBcRoomCreate(BcRoomCreateCommand(settings.copy(name = name)), backgrounds),
                BcRoomMutationRejectionReason.InvalidName,
                BcRoomBasicField.Name,
            )
        }
    }

    @Test
    fun trimMatchesJavascriptForBomAndPreservesFileSeparator() {
        val command = validCreate(
            settings.copy(name = "\uFEFF Room \uFEFF", description = "\uFEFF Description \uFEFF"),
        )
        assertEquals("Room", command.settings.name)
        assertEquals("Description", command.settings.description)

        assertInvalid(
            validateBcRoomCreate(
                BcRoomCreateCommand(settings.copy(name = "\u001CRoom\u001C")),
                backgrounds,
            ),
            BcRoomMutationRejectionReason.InvalidName,
            BcRoomBasicField.Name,
        )
        assertEquals(
            "\u001CDescription\u001C",
            validCreate(settings.copy(description = "\u001CDescription\u001C")).settings.description,
        )
    }

    @Test
    fun descriptionLimitCountsUtf16CodeUnitsAfterTrimming() {
        val emoji = "\uD83D\uDE00"
        val boundary = emoji.repeat(150)
        assertEquals(boundary, validCreate(settings.copy(description = " \n$boundary\t ")).settings.description)
        assertInvalid(
            validateBcRoomCreate(
                BcRoomCreateCommand(settings.copy(description = emoji.repeat(151))),
                backgrounds,
            ),
            BcRoomMutationRejectionReason.DescriptionTooLong,
            BcRoomBasicField.Description,
        )
    }

    @Test
    fun descriptionAllowsEmptyAndWhitespaceOnlyInput() {
        for (description in listOf("", " \t\r\n\uFEFF ")) {
            assertEquals("", validCreate(settings.copy(description = description)).settings.description)
        }
    }

    @Test
    fun memberLimitAcceptsInclusiveBoundsAndRejectsValuesOutsideThem() {
        for (limit in listOf(2, 20)) {
            assertEquals(limit, validCreate(settings.copy(memberLimit = limit)).settings.memberLimit)
        }
        for (limit in listOf(Int.MIN_VALUE, 1, 21, Int.MAX_VALUE)) {
            assertInvalid(
                validateBcRoomCreate(BcRoomCreateCommand(settings.copy(memberLimit = limit)), backgrounds),
                BcRoomMutationRejectionReason.InvalidMemberLimit,
                BcRoomBasicField.MemberLimit,
            )
        }
    }

    @Test
    fun backgroundRequiresAnExactEntryInTheAvailableCatalog() {
        for (background in backgrounds) {
            assertEquals(background, validCreate(settings.copy(background = background)).settings.background)
        }
        for (background in listOf("mainhall", " MainHall ", "Unknown", "")) {
            assertInvalid(
                validateBcRoomCreate(BcRoomCreateCommand(settings.copy(background = background)), backgrounds),
                BcRoomMutationRejectionReason.UnknownBackground,
                BcRoomBasicField.Background,
            )
        }
        assertInvalid(
            validateBcRoomCreate(BcRoomCreateCommand(settings), emptySet()),
            BcRoomMutationRejectionReason.BackgroundCatalogUnavailable,
            BcRoomBasicField.Background,
        )
    }

    @Test
    fun languagesRecognizeOnlyTheNativeCaseSensitiveCodes() {
        val expected = mapOf(
            "EN" to BcRoomLanguage.English,
            "DE" to BcRoomLanguage.German,
            "FR" to BcRoomLanguage.French,
            "ES" to BcRoomLanguage.Spanish,
            "CN" to BcRoomLanguage.Chinese,
            "RU" to BcRoomLanguage.Russian,
            "UA" to BcRoomLanguage.Ukrainian,
        )
        assertEquals(expected.keys, BcRoomLanguage.entries.map { it.wireValue }.toSet())
        for ((code, language) in expected) {
            assertEquals(language, BcRoomLanguage.fromWire(code))
            assertEquals(code, validCreate(settings.copy(language = code)).settings.language)
        }
        for (code in listOf("en", " EN ", "ZH", "Unknown", "")) {
            assertNull(BcRoomLanguage.fromWire(code))
            assertInvalid(
                validateBcRoomCreate(BcRoomCreateCommand(settings.copy(language = code)), backgrounds),
                BcRoomMutationRejectionReason.UnsupportedLanguage,
                BcRoomBasicField.Language,
            )
        }
    }

    @Test
    fun visibilityAndAccessAcceptNativeModesAndCanonicalizeRoleOrder() {
        for (visibility in BcRoomVisibility.entries) {
            assertEquals(visibility, BcRoomVisibility.fromWire(visibility.wireValues.reversed()))
            assertEquals(
                visibility.wireValues,
                validCreate(settings.copy(visibility = visibility.wireValues.reversed())).settings.visibility,
            )
        }
        for (access in BcRoomAccess.entries) {
            assertEquals(access, BcRoomAccess.fromWire(access.wireValues.reversed()))
            assertEquals(
                access.wireValues,
                validCreate(settings.copy(access = access.wireValues.reversed())).settings.access,
            )
        }
        assertEquals(BcRoomVisibility.Unlisted, BcRoomVisibility.fromWire(emptyList()))
        assertNull(BcRoomAccess.fromWire(emptyList()))
        assertInvalid(
            validateBcRoomCreate(BcRoomCreateCommand(settings.copy(access = emptyList())), backgrounds),
            BcRoomMutationRejectionReason.UnsupportedAccess,
            BcRoomBasicField.Access,
        )
    }

    @Test
    fun visibilityAndAccessRejectDuplicatesExtraRolesAndUnknownRoles() {
        val unsupportedRoles = listOf(
            listOf("All", "All"),
            listOf("Admin", "Admin"),
            listOf("Admin", "Whitelist", "Whitelist"),
            listOf("All", "Admin"),
            listOf("Admin", "Whitelist", "Unknown"),
            listOf("Whitelist"),
            listOf("Unknown"),
            listOf("all"),
        )
        for (roles in unsupportedRoles) {
            assertNull(BcRoomVisibility.fromWire(roles))
            assertNull(BcRoomAccess.fromWire(roles))
            assertInvalid(
                validateBcRoomCreate(BcRoomCreateCommand(settings.copy(visibility = roles)), backgrounds),
                BcRoomMutationRejectionReason.UnsupportedVisibility,
                BcRoomBasicField.Visibility,
            )
            assertInvalid(
                validateBcRoomCreate(BcRoomCreateCommand(settings.copy(access = roles)), backgrounds),
                BcRoomMutationRejectionReason.UnsupportedAccess,
                BcRoomBasicField.Access,
            )
        }
    }

    @Test
    fun updateValidatesOnlyEditedFieldsAndPreservesUnrecognizedSettings() {
        val rawSettings = settings.copy(
            name = "\u001C Raw room ",
            description = " old ",
            memberLimit = 1,
            background = "FutureBackground",
            language = "FutureLanguage",
            visibility = listOf("FutureVisibility"),
            access = listOf("FutureAccess"),
        )
        val command = BcRoomUpdateCommand(
            base = base.copy(settings = rawSettings),
            settings = rawSettings.copy(description = "\uFEFF New description \n"),
        )
        val validated = valid(validateBcRoomUpdate(command, emptySet()))
        assertEquals(setOf(BcRoomBasicField.Description), validated.editedFields)
        assertEquals(rawSettings.copy(description = "New description"), validated.settings)
        assertEquals(command.base, validated.base)
    }

    @Test
    fun updateCanonicalizesOnlyExplicitlyEditedValues() {
        val draft = settings.copy(
            name = " Updated room ",
            description = " Unedited description ",
            visibility = listOf("Whitelist", "Admin"),
            access = listOf("Whitelist", "Admin"),
        )
        val editedFields = setOf(BcRoomBasicField.Name, BcRoomBasicField.Visibility)
        val command = BcRoomUpdateCommand(base, draft, editedFields)
        val validated = valid(validateBcRoomUpdate(command, backgrounds))
        assertEquals(
            draft.copy(name = "Updated room", visibility = listOf("Admin", "Whitelist")),
            validated.settings,
        )
        assertEquals(editedFields, validated.editedFields)
    }

    @Test
    fun updateRejectsInvalidValuesWhenTheirFieldsAreEdited() {
        val invalidEdits = listOf(
            Triple(settings.copy(name = ""), BcRoomBasicField.Name, BcRoomMutationRejectionReason.InvalidName),
            Triple(
                settings.copy(description = "A".repeat(301)),
                BcRoomBasicField.Description,
                BcRoomMutationRejectionReason.DescriptionTooLong,
            ),
            Triple(
                settings.copy(memberLimit = 21),
                BcRoomBasicField.MemberLimit,
                BcRoomMutationRejectionReason.InvalidMemberLimit,
            ),
            Triple(
                settings.copy(background = "Unknown"),
                BcRoomBasicField.Background,
                BcRoomMutationRejectionReason.UnknownBackground,
            ),
            Triple(
                settings.copy(language = "en"),
                BcRoomBasicField.Language,
                BcRoomMutationRejectionReason.UnsupportedLanguage,
            ),
            Triple(
                settings.copy(visibility = listOf("Unknown")),
                BcRoomBasicField.Visibility,
                BcRoomMutationRejectionReason.UnsupportedVisibility,
            ),
            Triple(
                settings.copy(access = emptyList()),
                BcRoomBasicField.Access,
                BcRoomMutationRejectionReason.UnsupportedAccess,
            ),
        )
        for ((draft, field, reason) in invalidEdits) {
            assertInvalid(validateBcRoomUpdate(BcRoomUpdateCommand(base, draft), backgrounds), reason, field)
        }
        assertInvalid(
            validateBcRoomUpdate(BcRoomUpdateCommand(base, settings.copy(background = "Beach")), emptySet()),
            BcRoomMutationRejectionReason.BackgroundCatalogUnavailable,
            BcRoomBasicField.Background,
        )
    }

    @Test
    fun updateRejectsAnEmptyEditedFieldSet() {
        assertInvalid(
            validateBcRoomUpdate(BcRoomUpdateCommand(base, settings), backgrounds),
            BcRoomMutationRejectionReason.NothingToUpdate,
        )
        assertInvalid(
            validateBcRoomUpdate(BcRoomUpdateCommand(base, settings.copy(name = "Changed"), emptySet()), backgrounds),
            BcRoomMutationRejectionReason.NothingToUpdate,
        )
    }

    @Test
    fun updateRejectsAChangeThatNormalizesBackToItsBase() {
        assertInvalid(
            validateBcRoomUpdate(
                BcRoomUpdateCommand(base, settings.copy(name = "\uFEFFRoom ")),
                backgrounds,
            ),
            BcRoomMutationRejectionReason.NothingToUpdate,
        )
        assertInvalid(
            validateBcRoomUpdate(
                BcRoomUpdateCommand(base, settings, setOf(BcRoomBasicField.Name)),
                backgrounds,
            ),
            BcRoomMutationRejectionReason.NothingToUpdate,
        )
    }

    @Test
    fun updateRemovesUnchangedDeclaredFieldsAfterNormalization() {
        val command = BcRoomUpdateCommand(
            base,
            settings.copy(name = "\uFEFFRoom ", description = " New description "),
            setOf(BcRoomBasicField.Name, BcRoomBasicField.Description, BcRoomBasicField.Visibility),
        )
        val validated = valid(validateBcRoomUpdate(command, backgrounds))
        assertEquals(settings.copy(description = "New description"), validated.settings)
        assertEquals(setOf(BcRoomBasicField.Description), validated.editedFields)
    }

    @Test
    fun updateDerivesEachOfTheSevenEditableFields() {
        val edits = mapOf(
            BcRoomBasicField.Name to settings.copy(name = "Changed"),
            BcRoomBasicField.Description to settings.copy(description = "Changed"),
            BcRoomBasicField.MemberLimit to settings.copy(memberLimit = 20),
            BcRoomBasicField.Background to settings.copy(background = "Beach"),
            BcRoomBasicField.Language to settings.copy(language = "CN"),
            BcRoomBasicField.Visibility to settings.copy(visibility = emptyList()),
            BcRoomBasicField.Access to settings.copy(access = listOf("Admin")),
        )
        assertEquals(
            listOf("Name", "Description", "MemberLimit", "Background", "Language", "Visibility", "Access"),
            BcRoomBasicField.entries.map { it.name },
        )
        assertEquals(emptySet<BcRoomBasicField>(), changedBcRoomBasicFields(settings, settings))
        for ((field, draft) in edits) {
            assertEquals(setOf(field), changedBcRoomBasicFields(settings, draft))
            assertEquals(setOf(field), BcRoomUpdateCommand(base, draft).editedFields)
        }
        val allChanged = settings.copy(
            name = "Changed",
            description = "Changed",
            memberLimit = 20,
            background = "Beach",
            language = "CN",
            visibility = emptyList(),
            access = listOf("Admin"),
        )
        assertEquals(edits.keys, BcRoomUpdateCommand(base, allChanged).editedFields)
    }

    @Test
    fun updateConflictsOnlyWhenAnEditedFieldChangedSinceTheBase() {
        val command = BcRoomUpdateCommand(base, settings.copy(description = "My change"))
        val otherChanges = settings.copy(
            name = "Another name",
            memberLimit = 20,
            background = "Beach",
            language = "CN",
            visibility = emptyList(),
            access = listOf("Admin"),
        )
        assertEquals(emptySet<BcRoomBasicField>(), command.conflictingFields(settings))
        assertEquals(emptySet<BcRoomBasicField>(), command.conflictingFields(otherChanges))
        assertEquals(
            setOf(BcRoomBasicField.Description),
            command.conflictingFields(otherChanges.copy(description = "Another administrator's change")),
        )
        assertEquals(setOf(BcRoomBasicField.Description), command.conflictingFields(command.settings))
    }

    private fun validCreate(settings: BcRoomBasicSettings): BcRoomCreateCommand =
        valid(validateBcRoomCreate(BcRoomCreateCommand(settings), backgrounds))

    private fun <T> valid(result: BcRoomCommandValidation<T>): T = when (result) {
        is BcRoomCommandValidation.Valid -> result.command
        is BcRoomCommandValidation.Invalid -> throw AssertionError("Expected valid command, got ${result.rejection}")
    }

    private fun assertInvalid(
        result: BcRoomCommandValidation<*>,
        reason: BcRoomMutationRejectionReason,
        field: BcRoomBasicField? = null,
    ) {
        assertEquals(BcRoomCommandValidation.Invalid(BcRoomMutationRejection(reason, field)), result)
    }

    private val settings = BcRoomBasicSettings(name = "Room")
    private val backgrounds = setOf("MainHall", "Beach")
    private val base = BcRoomEditBase(BcRoomName("Room"), roomPresenceEpoch = 7, settings = settings)
}
