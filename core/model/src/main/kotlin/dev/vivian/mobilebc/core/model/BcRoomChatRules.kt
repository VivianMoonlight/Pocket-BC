package dev.vivian.mobilebc.core.model

enum class BcRoomChatRejectionReason {
    SelfStateUnavailable,
    AccountStateUnavailable,
    OwnerRulesUnavailable,
    TalkBlockedByOwner,
    TalkMutedByForbiddenWord,
    ForbiddenWord,
    EmoteBlockedByOwner,
    WhisperBlockedByOwner,
    SpeechStateUnavailable,
}

data class BcRoomChatRejection(
    val reason: BcRoomChatRejectionReason,
    val forbiddenWord: String? = null,
)

class BcRoomChatRejectedException(val rejection: BcRoomChatRejection) :
    IllegalStateException(rejection.reason.name)
