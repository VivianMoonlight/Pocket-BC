package dev.vivian.mobilebc.core.model

import kotlin.time.Duration

sealed interface BcError {
    data class Network(val message: String) : BcError
    data class Auth(val reason: AuthErrorReason) : BcError
    data class Server(val code: String, val message: String) : BcError
    data class Room(val reason: RoomErrorReason) : BcError
    data class RateLimited(val retryAfter: Duration?) : BcError
    data class DuplicatedLogin(val count: Int) : BcError
    data class Unknown(val message: String) : BcError
}

enum class AuthErrorReason {
    InvalidCredentials,
    LoginQueueFailed,
    Unknown,
}

enum class RoomErrorReason {
    NotFound,
    Full,
    Locked,
    NotInRoom,
    Unknown,
}

