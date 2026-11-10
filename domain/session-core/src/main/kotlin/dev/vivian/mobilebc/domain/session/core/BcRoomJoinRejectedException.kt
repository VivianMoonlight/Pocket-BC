package dev.vivian.mobilebc.domain.session.core

/** A server response that rejects joining a room while the transport remains usable. */
class BcRoomJoinRejectedException(message: String) : IllegalStateException(message)
