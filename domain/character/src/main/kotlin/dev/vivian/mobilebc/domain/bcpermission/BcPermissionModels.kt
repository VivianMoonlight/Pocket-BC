package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcActivityEffect

/**
 * A user-visible interaction that may or may not be executable in the current BC state.
 *
 * Candidates are deliberately not socket commands. The permission engine may reject a candidate,
 * require a server AllowItem check, or expand it into one or more concrete executable actions in
 * later phases. Phase 1 keeps one candidate -> one decision, while preserving the API shape needed
 * for Needs-* / TargetNeeds-* / ShockItem expansion in Phase 3.
 */
sealed interface BcInteractionCandidate {
    val targetMemberNumber: Int
    val group: String

    data class Activity(
        override val targetMemberNumber: Int,
        override val group: String,
        val activityName: String,
        /**
         * Optional item variant chosen by the user from a permission-filtered action list.
         * The permission engine must resolve this selection again before producing a command.
         */
        val activityAssetGroup: String? = null,
        val activityAssetName: String? = null,
    ) : BcInteractionCandidate

    data class WearItem(
        override val targetMemberNumber: Int,
        override val group: String,
        val itemName: String,
        val propertyJson: String? = null,
        val difficulty: Int? = null,
    ) : BcInteractionCandidate

    data class RemoveItem(
        override val targetMemberNumber: Int,
        override val group: String,
    ) : BcInteractionCandidate

    data class UnlockItem(
        override val targetMemberNumber: Int,
        override val group: String,
        val itemName: String,
        val lockName: String,
        val credential: dev.vivian.mobilebc.domain.bcitemproperty.BcLockCredential? = null,
    ) : BcInteractionCandidate

    data class LockItem(
        override val targetMemberNumber: Int,
        override val group: String,
        val itemName: String,
        val lockName: String,
    ) : BcInteractionCandidate

    data class ConfigureLock(
        override val targetMemberNumber: Int,
        override val group: String,
        val itemName: String,
        val lockName: String,
        val configuration: dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration,
    ) : BcInteractionCandidate

    data class ChangeLockTimer(
        override val targetMemberNumber: Int,
        override val group: String,
        val itemName: String,
        val lockName: String,
        val change: dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange,
    ) : BcInteractionCandidate

    data class ModifyItem(
        override val targetMemberNumber: Int,
        override val group: String,
        val itemName: String,
        val propertyJson: String,
        val difficulty: Int? = null,
    ) : BcInteractionCandidate
}

/** A concrete action that can be handed to the character interaction domain. */
sealed interface BcExecutableInteraction {
    data class Activity(
        val command: BcActivityCommand,
        val usedItem: BcPermissionItemRef? = null,
        val warnings: List<BcPermissionReason> = emptyList(),
        val effects: List<BcActivityEffect> = emptyList(),
    ) : BcExecutableInteraction

    data class ItemUpdate(
        val command: BcCharacterItemUpdateCommand,
        val warnings: List<BcPermissionReason> = emptyList(),
    ) : BcExecutableInteraction
}

data class BcActionExecutionPlan(
    val activity: BcActivityCommand,
    val effects: List<BcActivityEffect>,
)

data class BcPermissionItemRef(
    val group: String,
    val name: String,
    val source: BcPermissionItemSource,
)

enum class BcPermissionItemSource {
    Actor,
    Target,
}

sealed interface BcPermissionDecision {
    data class Allowed(
        val executable: BcExecutableInteraction,
    ) : BcPermissionDecision

    data class Denied(
        val candidate: BcInteractionCandidate,
        val reasons: List<BcPermissionReason>,
    ) : BcPermissionDecision

    data class NeedsServerCheck(
        val candidate: BcInteractionCandidate,
        val reasons: List<BcPermissionReason>,
    ) : BcPermissionDecision
}

data class BcPermissionFilterResult(
    val allowed: List<BcExecutableInteraction>,
    val denied: List<BcDeniedInteraction>,
    val needsServerCheck: List<BcPendingServerCheckInteraction>,
) {
    companion object {
        val Empty = BcPermissionFilterResult(
            allowed = emptyList(),
            denied = emptyList(),
            needsServerCheck = emptyList(),
        )
    }
}

data class BcDeniedInteraction(
    val candidate: BcInteractionCandidate,
    val reasons: List<BcPermissionReason>,
)

data class BcPendingServerCheckInteraction(
    val candidate: BcInteractionCandidate,
    val reasons: List<BcPermissionReason>,
)

data class BcPermissionRequest(
    val context: BcPermissionContext,
    val candidate: BcInteractionCandidate,
)

data class BcPermissionFilterRequest(
    val context: BcPermissionContext,
    val candidates: List<BcInteractionCandidate>,
)


/**
 * A paged permission request for callers that render long interaction lists lazily.
 *
 * [offset] is measured in candidate rows, not executable rows. A single candidate may expand into
 * multiple executable actions (Needs-* / TargetNeeds-* / ShockItem), so a result can contain more
 * than [limit] rendered rows when the final scanned candidate expands. This avoids splitting one BC
 * action expansion across pages.
 */
data class BcPermissionFilterPageRequest(
    val context: BcPermissionContext,
    val candidates: List<BcInteractionCandidate>,
    val offset: Int = 0,
    val limit: Int = 50,
    val includeAllowed: Boolean = true,
    val includeDenied: Boolean = true,
    val includeNeedsServerCheck: Boolean = true,
    val maxScannedCandidates: Int = Int.MAX_VALUE,
) {
    init {
        require(offset >= 0) { "offset must be non-negative." }
        require(limit >= 0) { "limit must be non-negative." }
        require(maxScannedCandidates >= 0) { "maxScannedCandidates must be non-negative." }
    }
}

data class BcPermissionFilterPageResult(
    val result: BcPermissionFilterResult,
    val offset: Int,
    val nextOffset: Int?,
    val scannedCandidateCount: Int,
    val emittedInteractionCount: Int,
    val hasMoreCandidates: Boolean,
) {
    val isEmpty: Boolean get() = emittedInteractionCount == 0

    companion object {
        fun empty(offset: Int, hasMoreCandidates: Boolean = false): BcPermissionFilterPageResult =
            BcPermissionFilterPageResult(
                result = BcPermissionFilterResult.Empty,
                offset = offset,
                nextOffset = if (hasMoreCandidates) offset else null,
                scannedCandidateCount = 0,
                emittedInteractionCount = 0,
                hasMoreCandidates = hasMoreCandidates,
            )
    }
}

data class BcPermissionContext(
    val actor: BcCharacterSnapshot,
    val target: BcCharacterSnapshot,
    val room: BcPermissionRoomContext,
    val rules: BcRuleCatalog = BcRuleCatalog.Empty,
    /**
     * Result of BC's server AllowItem check, when known.
     *
     * null means local rules passed far enough that the caller should perform queryAllowItem before
     * actually sending item updates. This lets batch filtering stay local and avoid sending a socket
     * query per candidate.
     */
    val serverAllowItem: Boolean? = null,
    val itemPrivateState: dev.vivian.mobilebc.core.model.BcItemPermissionPrivateState? = null,
    val serverTimeAvailable: Boolean = false,
    val serverTimeMillis: Long? = null,
)

data class BcPermissionRoomContext(
    val roomName: String? = null,
    val memberNumbers: Set<Int>,
    val blockCategories: Set<String> = emptySet(),
) {
    fun contains(memberNumber: Int): Boolean = memberNumber in memberNumbers
}
