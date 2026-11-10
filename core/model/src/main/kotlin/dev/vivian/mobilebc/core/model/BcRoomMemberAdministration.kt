package dev.vivian.mobilebc.core.model

enum class BcRoomMemberAdminAction(val wireValue: String) {
    Kick("Kick"),
    Ban("Ban"),
    Promote("Promote"),
    Demote("Demote"),
}

data class BcRoomMemberAdminCommand(
    val roomName: BcRoomName,
    val roomPresenceEpoch: Long,
    val targetMemberNumber: Int,
    val targetPresenceEpoch: Long,
    val action: BcRoomMemberAdminAction,
    val targetIsAdministrator: Boolean,
)

enum class BcRoomMemberAdminObservation {
    TargetLeft,
    TargetBanned,
    TargetPromoted,
    TargetDemoted,
    Unconfirmed,
}

enum class BcRoomMemberAdminRejectionReason {
    ConnectionUnavailable,
    AccountUnavailable,
    RoomUnavailable,
    RoomChanged,
    SettingsUnavailable,
    NotAdministrator,
    TargetUnavailable,
    TargetChanged,
    SelfTarget,
    TargetRoleChanged,
    Busy,
    PendingResponse,
}

class BcRoomMemberAdminRejectedException(val reason: BcRoomMemberAdminRejectionReason) :
    IllegalStateException(reason.name)

enum class BcRoomRemovalReason {
    Kicked,
    Banned,
}
