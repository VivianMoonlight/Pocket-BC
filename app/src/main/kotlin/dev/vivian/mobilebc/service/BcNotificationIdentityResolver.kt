package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.session.core.BcRelationshipLoadState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot

internal fun notificationInput(message: BcMessage, session: BcSessionSnapshot?): BcNotificationInput {
    val member = message.sender.memberNumber
    val system = message.notificationPolicy == BcNotificationPolicy.SystemAlert ||
        message.kind in setOf(BcMessageKind.System, BcMessageKind.Error)
    val relations = session?.relationships
    val identities = if (system) emptySet() else if (relations == null ||
        relations.loadState != BcRelationshipLoadState.Loaded ||
        relations.accountMemberNumber != session.account?.memberNumber
    ) setOf(BcNotificationIdentity.Unknown) else buildSet {
        val self = relations.self
        if (member in self.friends) add(BcNotificationIdentity.Friend)
        if (member in self.submissives) add(BcNotificationIdentity.Submissive)
        if (member != null && self.ownership?.memberNumber == member) add(BcNotificationIdentity.Owner)
        if (member != null && self.loverships.any { it.memberNumber == member }) add(BcNotificationIdentity.Lover)
        if (isEmpty()) add(BcNotificationIdentity.Stranger)
    }
    return BcNotificationInput(
        memberNumber = member,
        identities = identities,
        type = message.kind,
        text = message.text,
        involvesSelf = session?.account?.memberNumber?.let { it == message.targetMemberNumber } == true ||
            message.notificationPolicy == BcNotificationPolicy.DirectMessage,
        system = system,
    )
}
