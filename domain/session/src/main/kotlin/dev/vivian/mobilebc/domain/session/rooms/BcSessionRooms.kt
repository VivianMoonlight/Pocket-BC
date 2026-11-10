package dev.vivian.mobilebc.domain.session.rooms

import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomCreateCommand
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminObservation

interface BcSessionRooms {
    suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary>
    suspend fun joinRoom(roomName: String, password: String? = null)
    suspend fun leaveRoom()
    suspend fun createRoom(command: BcRoomCreateCommand): BcRoom =
        throw UnsupportedOperationException("Room creation is not supported by this session.")
    suspend fun updateRoom(command: BcRoomUpdateCommand): BcRoom =
        throw UnsupportedOperationException("Room updates are not supported by this session.")
    suspend fun manageRoomMember(command: BcRoomMemberAdminCommand): BcRoomMemberAdminObservation =
        throw UnsupportedOperationException("Room member administration is not supported by this session.")
}
