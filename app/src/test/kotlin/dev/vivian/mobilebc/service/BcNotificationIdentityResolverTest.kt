package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.session.core.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BcNotificationIdentityResolverTest {
    private val message = BcMessage(
        BcMessageId("m"), BcConversationId("c"), sender = BcSender(42, "Someone"),
        kind = BcMessageKind.Activity, text = "Hello", timestamp = Instant.EPOCH, targetMemberNumber = 10,
    )
    private val session = BcSessionSnapshot(
        account = BcAccount(10, "Self", "self"),
        relationships = BcRelationshipSnapshot().nextLoaded(10, BcSelfRelations(
            friends = setOf(42), submissives = setOf(42),
        )),
    )

    @Test fun knownRelationsCanOverlapAndExplicitTargetInvolvesSelf() {
        val input = notificationInput(message, session)
        assertEquals(setOf(BcNotificationIdentity.Friend, BcNotificationIdentity.Submissive), input.identities)
        assertTrue(input.involvesSelf)
    }

    @Test fun unknownAndWrongAccountRelationshipsNeverBecomeStranger() {
        assertEquals(setOf(BcNotificationIdentity.Unknown), notificationInput(message, null).identities)
        assertEquals(setOf(BcNotificationIdentity.Unknown),
            notificationInput(message, session.copy(account = BcAccount(99, "Other", "other"))).identities)
        assertEquals(setOf(BcNotificationIdentity.Stranger),
            notificationInput(message.copy(sender = BcSender(50, "New")), session).identities)
    }
}
