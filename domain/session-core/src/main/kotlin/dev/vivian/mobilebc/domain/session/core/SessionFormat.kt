package dev.vivian.mobilebc.domain.session.core

fun BcConnectionHealth.label(): String = when (this) {
    BcConnectionHealth.Offline -> "Offline"
    BcConnectionHealth.ConnectedUnverified -> "Verifying"
    BcConnectionHealth.VerifiedOnline -> "Online"
    BcConnectionHealth.Stale -> "Stale"
}

fun BcSessionState.label(): String = when (this) {
    BcSessionState.Idle -> "Idle"
    is BcSessionState.Connecting -> "Connecting"
    BcSessionState.Connected -> "Connected"
    BcSessionState.LoggingIn -> "Logging in"
    is BcSessionState.LoginQueued -> "Queued"
    BcSessionState.Ready -> "Ready"
    is BcSessionState.SearchingRooms -> "Searching"
    is BcSessionState.JoiningRoom -> "Joining"
    is BcSessionState.CreatingRoom -> "Creating room"
    is BcSessionState.UpdatingRoom -> "Updating room"
    is BcSessionState.ManagingRoomMember -> "Managing room member"
    is BcSessionState.Reconnecting -> "Reconnecting"
    is BcSessionState.Blocked -> "Blocked"
    is BcSessionState.Error -> "Error"
    BcSessionState.Stopped -> "Stopped"
}

fun BcSessionSnapshot.statusDescription(includeMemberNumber: Boolean = false): String = when (state) {
    BcSessionState.Idle -> "Session is idle"
    is BcSessionState.Connecting -> "Connecting to BC"
    BcSessionState.Connected -> "Connected, waiting for login"
    BcSessionState.LoggingIn -> "Logging in"
    is BcSessionState.LoginQueued -> state.position?.let { "Login queue position $it" } ?: "Waiting in login queue"
    BcSessionState.Ready -> currentRoom?.let { room ->
        "In room ${room.name.value}"
    } ?: account?.let { account ->
        buildString {
            append("Logged in as ")
            append(account.displayName)
            if (includeMemberNumber) append(" (#${account.memberNumber})")
        }
    } ?: "Session ready"
    is BcSessionState.SearchingRooms -> "Searching rooms"
    is BcSessionState.JoiningRoom -> "Joining ${state.roomName}"
    is BcSessionState.CreatingRoom -> "Creating ${state.roomName}"
    is BcSessionState.UpdatingRoom -> "Updating ${state.roomName}"
    is BcSessionState.ManagingRoomMember -> "Managing member #${state.targetMemberNumber} in ${state.roomName}"
    is BcSessionState.Reconnecting -> "Reconnecting, attempt ${state.attempt}"
    is BcSessionState.Blocked -> "Session blocked: ${state.reason}"
    is BcSessionState.Error -> "Session error"
    BcSessionState.Stopped -> "Session stopped"
}

