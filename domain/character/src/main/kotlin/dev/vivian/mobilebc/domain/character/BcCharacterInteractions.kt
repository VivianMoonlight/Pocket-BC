package dev.vivian.mobilebc.domain.character

import dev.vivian.mobilebc.core.model.BcCharacterInteractionResult
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInboundActivityCommand
import dev.vivian.mobilebc.core.model.BcInboundActivityResult
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcSelfStateResult
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfStateRequest
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlan
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeRequest
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeResult
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot

interface BcCharacterInteractions {
    suspend fun saveWardrobe(request: BcWardrobeRequest): BcWardrobeResult =
        BcWardrobeResult.Failed("Saving wardrobe slots is not supported.")

    /** Applies only the complete plan that was displayed for confirmation. */
    suspend fun applyWardrobe(request: BcWardrobeRequest, expectedPlan: BcWardrobePlan): BcWardrobeResult =
        BcWardrobeResult.Failed("Restoring wardrobe slots is not supported.")

    suspend fun renameWardrobe(request: BcWardrobeRequest, name: String): BcWardrobeResult =
        BcWardrobeResult.Failed("Renaming wardrobe slots is not supported.")

    /** Edits only the authenticated character and rechecks the editor's account/room ownership. */
    suspend fun updateSelfState(request: BcSelfStateRequest): BcSelfStateResult =
        BcSelfStateResult.Failed("Self pose and expression updates are not supported.")

    /**
     * Evaluates a permission candidate, performs the server AllowItem check when required, and
     * sends the resulting executable activity or item interaction.
     */
    suspend fun sendInteraction(
        request: BcPermissionRequest,
        activityClientMsgId: String? = null,
    ): BcCharacterInteractionResult

    /** Applies supported client-side effects from a server-authoritative inbound Activity. */
    suspend fun handleInboundActivity(command: BcInboundActivityCommand): BcInboundActivityResult =
        BcInboundActivityResult.Ignored
}

sealed interface BcCharacterRosterState {
    data object NotInRoom : BcCharacterRosterState
    data class InRoom(
        val roomName: BcRoomName,
        val characters: List<BcCharacterSnapshot>,
        val blockCategories: Set<String> = emptySet(),
    ) : BcCharacterRosterState
}

fun BcSessionSnapshot.toCharacterRosterState(): BcCharacterRosterState =
    currentRoom?.let { room ->
        BcCharacterRosterState.InRoom(
            roomName = room.name,
            characters = roomCharacters,
            blockCategories = room.blockCategories,
        )
    } ?: BcCharacterRosterState.NotInRoom
