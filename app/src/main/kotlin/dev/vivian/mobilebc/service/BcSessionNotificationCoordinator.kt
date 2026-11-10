package dev.vivian.mobilebc.service

import android.app.PendingIntent
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcNotificationAccountContext
import dev.vivian.mobilebc.core.model.BcNotificationPolicy
import dev.vivian.mobilebc.core.model.BcNotificationSettings
import dev.vivian.mobilebc.core.model.BcNotificationInput
import dev.vivian.mobilebc.core.model.BcNotificationLevel
import dev.vivian.mobilebc.service.notification.BcNotificationController

/**
 * Owns business-level notification decisions for session messages.
 *
 * The Android controller only builds/posts notifications. This coordinator decides
 * whether a message should interrupt the user, whether it should be suppressed in
 * the foreground, and whether a late/stale message should be ignored.
 */
class BcSessionNotificationCoordinator(
    private val notificationController: BcNotificationController,
    private val openIntentForMessage: (BcMessage, BcNotificationAccountContext?) -> PendingIntent,
    private val smallIconRes: Int,
    private val accountContextProvider: () -> BcNotificationAccountContext?,
    private val settingsProvider: () -> BcNotificationSettings,
    private val isAppInForeground: () -> Boolean,
    private val inputProvider: (BcMessage) -> BcNotificationInput,
) {
    private val lastNotifiedByConversation = mutableMapOf<ScopedConversationKey, MessageNotificationCursor>()

    fun onInboundMessage(message: BcMessage) {
        if (message.sender.isSelf) return
        if (message.notificationPolicy == BcNotificationPolicy.None) return

        val accountContext = accountContextProvider()
        if (!markLatestOrReject(message, accountContext)) return

        val settings = settingsProvider()
        if (isAppInForeground() && settings.shouldSuppressWhenForeground(message.notificationPolicy)) {
            return
        }

        val decision = settings.decide(inputProvider(message))
        if (decision.level == BcNotificationLevel.Off) return
        notificationController.notifyMessage(
            level = decision.level,
            message = message,
            accountContext = accountContext,
            openIntent = openIntentForMessage(message, accountContext),
            smallIconRes = smallIconRes,
        )
    }

    private fun markLatestOrReject(
        message: BcMessage,
        accountContext: BcNotificationAccountContext?,
    ): Boolean {
        val key = ScopedConversationKey(
            accountKey = accountContext?.stableKey ?: BcNotificationAccountContext.UNKNOWN_ACCOUNT_KEY,
            conversationId = message.conversationId.value,
        )
        val next = MessageNotificationCursor(
            timestampMillis = message.timestamp.toEpochMilli(),
            messageId = message.id.value,
        )
        val previous = lastNotifiedByConversation[key]
        if (!next.canFollow(previous)) return false
        lastNotifiedByConversation[key] = next
        return true
    }

    private data class ScopedConversationKey(
        val accountKey: String,
        val conversationId: String,
    )

    internal data class MessageNotificationCursor(
        val timestampMillis: Long,
        val messageId: String,
    ) {
        fun canFollow(previous: MessageNotificationCursor?): Boolean =
            previous == null ||
                (timestampMillis >= previous.timestampMillis && messageId != previous.messageId)
    }
}
