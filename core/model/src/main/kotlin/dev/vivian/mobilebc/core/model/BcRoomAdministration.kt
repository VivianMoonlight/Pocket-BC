package dev.vivian.mobilebc.core.model

data class BcRoomBasicSettings(
    val name: String = "",
    val description: String = "",
    val memberLimit: Int = 10,
    val background: String = "MainHall",
    val language: String = "EN",
    val visibility: List<String> = listOf("All"),
    val access: List<String> = listOf("All"),
)

enum class BcRoomLanguage(val wireValue: String) {
    English("EN"),
    German("DE"),
    French("FR"),
    Spanish("ES"),
    Chinese("CN"),
    Russian("RU"),
    Ukrainian("UA"),
    ;

    companion object {
        fun fromWire(value: String): BcRoomLanguage? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class BcRoomVisibility(val wireValues: List<String>) {
    Public(listOf("All")),
    AdministratorsAndWhitelist(listOf("Admin", "Whitelist")),
    Administrators(listOf("Admin")),
    Unlisted(emptyList()),
    ;

    companion object {
        fun fromWire(values: List<String>): BcRoomVisibility? =
            entries.firstOrNull { sameRoomRoles(values, it.wireValues) }
    }
}

enum class BcRoomAccess(val wireValues: List<String>) {
    Public(listOf("All")),
    AdministratorsAndWhitelist(listOf("Admin", "Whitelist")),
    Administrators(listOf("Admin")),
    ;

    companion object {
        fun fromWire(values: List<String>): BcRoomAccess? =
            entries.firstOrNull { sameRoomRoles(values, it.wireValues) }
    }
}

enum class BcRoomSpace(val wireValue: String) {
    Female(""),
    Mixed("X"),
    Male("M"),
    Asylum("Asylum"),
}

enum class BcRoomBasicField {
    Name,
    Description,
    MemberLimit,
    Background,
    Language,
    Visibility,
    Access,
}

data class BcRoomEditBase(
    val roomName: BcRoomName,
    val roomPresenceEpoch: Long,
    val settings: BcRoomBasicSettings,
)

data class BcRoomAdministration(
    val base: BcRoomEditBase,
    val adminMemberNumbers: List<Int>,
)

data class BcRoomCreateCommand(
    val settings: BcRoomBasicSettings,
    val space: BcRoomSpace = BcRoomSpace.Mixed,
)

data class BcRoomUpdateCommand(
    val base: BcRoomEditBase,
    val settings: BcRoomBasicSettings,
    val editedFields: Set<BcRoomBasicField> = changedBcRoomBasicFields(base.settings, settings),
)

enum class BcRoomMutationRejectionReason {
    InvalidName,
    DescriptionTooLong,
    InvalidMemberLimit,
    BackgroundCatalogUnavailable,
    UnknownBackground,
    UnsupportedLanguage,
    UnsupportedVisibility,
    UnsupportedAccess,
    NothingToUpdate,
    ConnectionUnavailable,
    AccountUnavailable,
    AlreadyInRoom,
    RoomUnavailable,
    RoomChanged,
    NotAdministrator,
    SettingsUnavailable,
    EditConflict,
    Busy,
    PendingResponse,
    ServerRejected,
}

data class BcRoomMutationRejection(
    val reason: BcRoomMutationRejectionReason,
    val field: BcRoomBasicField? = null,
    val serverCode: String? = null,
)

class BcRoomMutationRejectedException(val rejection: BcRoomMutationRejection) :
    IllegalStateException(rejection.reason.name)

enum class BcRoomMutationUnknownReason {
    TimedOut,
    Disconnected,
    Cancelled,
    InvalidResponse,
}

class BcRoomMutationOutcomeUnknownException(
    val reason: BcRoomMutationUnknownReason,
    val serverCode: String? = null,
) : IllegalStateException(reason.name)

sealed interface BcRoomCommandValidation<out T> {
    data class Valid<T>(val command: T) : BcRoomCommandValidation<T>
    data class Invalid(val rejection: BcRoomMutationRejection) : BcRoomCommandValidation<Nothing>
}

fun validateBcRoomCreate(
    command: BcRoomCreateCommand,
    validBackgroundNames: Set<String>,
): BcRoomCommandValidation<BcRoomCreateCommand> = when (
    val validation = validateRoomSettings(command.settings, BcRoomBasicField.entries.toSet(), validBackgroundNames)
) {
    is BcRoomCommandValidation.Valid -> BcRoomCommandValidation.Valid(command.copy(settings = validation.command))
    is BcRoomCommandValidation.Invalid -> validation
}

fun validateBcRoomUpdate(
    command: BcRoomUpdateCommand,
    validBackgroundNames: Set<String>,
): BcRoomCommandValidation<BcRoomUpdateCommand> {
    if (command.editedFields.isEmpty()) {
        return BcRoomCommandValidation.Invalid(
            BcRoomMutationRejection(BcRoomMutationRejectionReason.NothingToUpdate),
        )
    }
    return when (val validation = validateRoomSettings(command.settings, command.editedFields, validBackgroundNames)) {
        is BcRoomCommandValidation.Valid -> {
            val changes = command.editedFields.intersect(changedBcRoomBasicFields(command.base.settings, validation.command))
            if (changes.isEmpty()) {
                BcRoomCommandValidation.Invalid(BcRoomMutationRejection(BcRoomMutationRejectionReason.NothingToUpdate))
            } else {
                BcRoomCommandValidation.Valid(command.copy(settings = validation.command, editedFields = changes))
            }
        }
        is BcRoomCommandValidation.Invalid -> validation
    }
}

fun changedBcRoomBasicFields(
    before: BcRoomBasicSettings,
    after: BcRoomBasicSettings,
): Set<BcRoomBasicField> = BcRoomBasicField.entries.filterTo(mutableSetOf()) { field ->
    when (field) {
        BcRoomBasicField.Name -> before.name != after.name
        BcRoomBasicField.Description -> before.description != after.description
        BcRoomBasicField.MemberLimit -> before.memberLimit != after.memberLimit
        BcRoomBasicField.Background -> before.background != after.background
        BcRoomBasicField.Language -> before.language != after.language
        BcRoomBasicField.Visibility -> before.visibility != after.visibility
        BcRoomBasicField.Access -> before.access != after.access
    }
}

fun BcRoomUpdateCommand.conflictingFields(current: BcRoomBasicSettings): Set<BcRoomBasicField> =
    changedBcRoomBasicFields(base.settings, current).intersect(editedFields)

private fun validateRoomSettings(
    settings: BcRoomBasicSettings,
    fields: Set<BcRoomBasicField>,
    validBackgroundNames: Set<String>,
): BcRoomCommandValidation<BcRoomBasicSettings> {
    var normalized = settings
    for (field in BcRoomBasicField.entries) {
        if (field !in fields) continue
        val reason = when (field) {
            BcRoomBasicField.Name -> {
                val value = settings.name.trimBcRoomInput()
                normalized = normalized.copy(name = value)
                BcRoomMutationRejectionReason.InvalidName.takeUnless {
                    value.length in 1..20 && value.all { it in '\u0020'..'\u007E' }
                }
            }
            BcRoomBasicField.Description -> {
                val value = settings.description.trimBcRoomInput()
                normalized = normalized.copy(description = value)
                BcRoomMutationRejectionReason.DescriptionTooLong.takeIf { value.length > 300 }
            }
            BcRoomBasicField.MemberLimit ->
                BcRoomMutationRejectionReason.InvalidMemberLimit.takeUnless { settings.memberLimit in 2..20 }
            BcRoomBasicField.Background -> when {
                validBackgroundNames.isEmpty() -> BcRoomMutationRejectionReason.BackgroundCatalogUnavailable
                settings.background !in validBackgroundNames -> BcRoomMutationRejectionReason.UnknownBackground
                else -> null
            }
            BcRoomBasicField.Language ->
                BcRoomMutationRejectionReason.UnsupportedLanguage.takeIf { BcRoomLanguage.fromWire(settings.language) == null }
            BcRoomBasicField.Visibility -> {
                val mode = BcRoomVisibility.fromWire(settings.visibility)
                if (mode == null) BcRoomMutationRejectionReason.UnsupportedVisibility else {
                    normalized = normalized.copy(visibility = mode.wireValues)
                    null
                }
            }
            BcRoomBasicField.Access -> {
                val mode = BcRoomAccess.fromWire(settings.access)
                if (mode == null) BcRoomMutationRejectionReason.UnsupportedAccess else {
                    normalized = normalized.copy(access = mode.wireValues)
                    null
                }
            }
        }
        if (reason != null) return BcRoomCommandValidation.Invalid(BcRoomMutationRejection(reason, field))
    }
    return BcRoomCommandValidation.Valid(normalized)
}

private fun sameRoomRoles(actual: List<String>, expected: List<String>): Boolean =
    actual.size == expected.size && expected.all { it in actual }

private fun String.trimBcRoomInput(): String = trim { character ->
    when (character) {
        in '\u0009'..'\u000D', in '\u2000'..'\u200A',
        '\u0020', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> false
    }
}
