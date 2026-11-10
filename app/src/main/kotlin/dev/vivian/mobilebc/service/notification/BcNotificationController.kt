package dev.vivian.mobilebc.service.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.vivian.mobilebc.R
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcNotificationAccountContext
import dev.vivian.mobilebc.core.model.BcNotificationChannelDiagnostics
import dev.vivian.mobilebc.core.model.BcNotificationChannelKind
import dev.vivian.mobilebc.core.model.BcNotificationDiagnostics
import dev.vivian.mobilebc.core.model.BcNotificationImportanceClass
import dev.vivian.mobilebc.core.model.BcNotificationLevel
import dev.vivian.mobilebc.core.model.BcNotificationPolicy

class BcNotificationController(
    private val context: Context,
    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java),
) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        NotificationChannelSpec.all(context).forEach { spec ->
            notificationManager.createNotificationChannel(spec.createChannel())
        }
        listOf(
            ROOM_NORMAL_CHANNEL_ID, ROOM_IMPORTANT_CHANNEL_ID,
            CHAT_NORMAL_CHANNEL_ID, CHAT_IMPORTANT_CHANNEL_ID, SYSTEM_ALERT_CHANNEL_ID,
        ).forEach(notificationManager::deleteNotificationChannel)
    }

    fun readDiagnostics(): BcNotificationDiagnostics {
        ensureChannels()
        val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val permissionGranted = !permissionRequired || canPostNotifications()
        val channels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannelSpec.all(context).map { spec ->
                val channel = notificationManager.getNotificationChannel(spec.id)
                spec.toDiagnostics(channel, context)
            }
        } else {
            emptyList()
        }
        return BcNotificationDiagnostics(
            appNotificationsEnabled = appNotificationsEnabled,
            postNotificationsPermissionRequired = permissionRequired,
            postNotificationsPermissionGranted = permissionGranted,
            channelsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
            channels = channels,
        )
    }

    fun buildSessionStatus(
        title: String,
        text: String,
        openIntent: PendingIntent,
        stopIntent: PendingIntent,
        smallIconRes: Int,
    ): Notification =
        NotificationCompat.Builder(context, SESSION_CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle("${context.getString(R.string.app_name)}: $title")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(smallIconRes, context.getString(R.string.notification_open), openIntent)
            .addAction(smallIconRes, context.getString(R.string.notification_stop_session), stopIntent)
            .build()

    fun notifyMessage(
        level: BcNotificationLevel,
        message: BcMessage,
        accountContext: BcNotificationAccountContext?,
        openIntent: PendingIntent,
        smallIconRes: Int,
    ) {
        if (!canPostNotifications()) return
        val notification = buildMessageNotification(message, accountContext, openIntent, smallIconRes, level) ?: return
        notificationManager.notify(message.notificationId(accountContext), notification)
    }

    fun notifySessionStatus(notification: Notification) {
        notificationManager.notify(SESSION_NOTIFICATION_ID, notification)
    }

    private fun buildMessageNotification(
        message: BcMessage,
        accountContext: BcNotificationAccountContext?,
        openIntent: PendingIntent,
        smallIconRes: Int,
        level: BcNotificationLevel,
    ): Notification? {
        val channelId = channelId(level) ?: return null
        val priority = if (level == BcNotificationLevel.Critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
        val accountPrefix = accountContext?.displayLabel?.let { "[$it] " }.orEmpty()
        val title = when (message.notificationPolicy) {
            BcNotificationPolicy.RoomMessage -> accountPrefix + context.getString(
                R.string.notification_current_room_sender,
                message.sender.displayName,
            )
            BcNotificationPolicy.DirectMessage -> "${accountPrefix}${message.kind.notificationLabel()}: ${message.sender.displayName}"
            BcNotificationPolicy.SystemAlert -> "${accountPrefix}${context.getString(R.string.app_name)}: ${message.kind.notificationLabel()}"
            BcNotificationPolicy.None -> return null
        }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(message.text.ifBlank { message.kind.notificationLabel() })
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(message.notificationGroupKey(accountContext))
            .build()
    }

    private fun channelId(level: BcNotificationLevel): String? = when (level) {
        BcNotificationLevel.Off -> null
        BcNotificationLevel.Normal -> NORMAL_CHANNEL_ID
        BcNotificationLevel.Important -> IMPORTANT_CHANNEL_ID
        BcNotificationLevel.Critical -> CRITICAL_CHANNEL_ID
    }
    private fun BcMessage.notificationId(accountContext: BcNotificationAccountContext?): Int {
        if (notificationPolicy == BcNotificationPolicy.None) return SESSION_NOTIFICATION_ID
        val scopedHash = stableNotificationHash(
            accountContext?.stableKey ?: BcNotificationAccountContext.UNKNOWN_ACCOUNT_KEY,
            conversationId.value,
        ).and(NOTIFICATION_HASH_MASK)
        return MESSAGE_NOTIFICATION_BASE_ID + scopedHash
    }

    private fun BcMessage.notificationGroupKey(accountContext: BcNotificationAccountContext?): String {
        val accountKey = accountContext?.stableKey ?: BcNotificationAccountContext.UNKNOWN_ACCOUNT_KEY
        return when (notificationPolicy) {
            BcNotificationPolicy.RoomMessage -> "$ROOM_MESSAGE_GROUP_PREFIX.$accountKey"
            BcNotificationPolicy.DirectMessage,
            BcNotificationPolicy.SystemAlert,
            -> "$CHAT_MESSAGE_GROUP_PREFIX.$accountKey"
            BcNotificationPolicy.None -> "$CHAT_MESSAGE_GROUP_PREFIX.$accountKey"
        }
    }

    private fun stableNotificationHash(vararg values: String): Int =
        values.fold(17) { acc, value -> 31 * acc + value.hashCode() }

    private fun BcMessageKind.notificationLabel(): String = when (this) {
        BcMessageKind.Whisper -> context.getString(R.string.notification_kind_whisper)
        BcMessageKind.Beep -> context.getString(R.string.notification_kind_message)
        BcMessageKind.Chat -> context.getString(R.string.notification_kind_chat)
        BcMessageKind.Emote -> context.getString(R.string.notification_kind_emote)
        BcMessageKind.Action -> context.getString(R.string.notification_kind_action)
        BcMessageKind.Activity -> context.getString(R.string.notification_kind_activity)
        BcMessageKind.System -> context.getString(R.string.notification_kind_system)
        BcMessageKind.Error -> context.getString(R.string.notification_kind_error)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private data class NotificationChannelSpec(
        val id: String,
        val name: String,
        val description: String,
        val kind: BcNotificationChannelKind,
        val importanceClass: BcNotificationImportanceClass?,
        val defaultImportance: Int,
        val defaultSound: Boolean,
        val defaultVibration: Boolean,
        val showBadge: Boolean = true,
    ) {
        fun createChannel(): NotificationChannel {
            val channel = NotificationChannel(id, name, defaultImportance)
            channel.description = description
            channel.setShowBadge(showBadge)
            channel.enableVibration(defaultVibration)
            if (defaultSound) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
            } else {
                channel.setSound(null, null)
            }
            return channel
        }

        fun toDiagnostics(channel: NotificationChannel?, context: Context): BcNotificationChannelDiagnostics {
            val actualImportance = channel?.importance
            val isMissing = channel == null
            val isBlocked = actualImportance == NotificationManager.IMPORTANCE_NONE
            return BcNotificationChannelDiagnostics(
                id = id,
                name = channel?.name?.toString() ?: name,
                purpose = purposeLabel(context),
                expectedImportanceLabel = context.getString(R.string.notification_system_controlled),
                actualImportance = actualImportance,
                actualImportanceLabel = actualImportance?.let { importanceLabel(context, it) }
                    ?: context.getString(R.string.notification_missing),
                soundEnabled = channel?.sound != null,
                vibrationEnabled = channel?.shouldVibrate(),
                bannerEnabled = actualImportance?.let { it >= NotificationManager.IMPORTANCE_HIGH },
                kind = kind,
                importanceClass = importanceClass,
                isMissing = isMissing,
                isBlocked = isBlocked,
                isLowerThanExpected = false,
            )
        }

        private fun purposeLabel(context: Context): String = when (importanceClass) {
            null -> name
            else -> "${kind.displayLabel(context)} · ${importanceClass.displayLabel(context)}"
        }

        companion object {
            fun all(context: Context): List<NotificationChannelSpec> = listOf(
                    NotificationChannelSpec(
                        id = SESSION_CHANNEL_ID,
                        name = context.getString(R.string.notification_channel_session_name),
                        description = context.getString(R.string.notification_channel_session_description),
                        kind = BcNotificationChannelKind.ForegroundSession,
                        importanceClass = null,
                        defaultImportance = NotificationManager.IMPORTANCE_LOW,
                        defaultSound = false,
                        defaultVibration = false,
                        showBadge = false,
                    ),
                    NotificationChannelSpec(
                        NORMAL_CHANNEL_ID, context.getString(R.string.notification_level_normal),
                        context.getString(R.string.notification_levels_description),
                        BcNotificationChannelKind.Normal, BcNotificationImportanceClass.Normal,
                        NotificationManager.IMPORTANCE_LOW, false, false,
                    ),
                    NotificationChannelSpec(
                        IMPORTANT_CHANNEL_ID, context.getString(R.string.notification_level_important),
                        context.getString(R.string.notification_levels_description),
                        BcNotificationChannelKind.Important, BcNotificationImportanceClass.Important,
                        NotificationManager.IMPORTANCE_DEFAULT, true, true,
                    ),
                    NotificationChannelSpec(
                        CRITICAL_CHANNEL_ID, context.getString(R.string.notification_level_critical),
                        context.getString(R.string.notification_levels_description),
                        BcNotificationChannelKind.Critical, BcNotificationImportanceClass.Critical,
                        NotificationManager.IMPORTANCE_HIGH, true, true,
                    ),
                )
        }
    }

    companion object {
        const val SESSION_NOTIFICATION_ID = 1001

        const val SESSION_CHANNEL_ID = "bc_session_v3"
        const val NORMAL_CHANNEL_ID = "bc_normal_v4"
        const val IMPORTANT_CHANNEL_ID = "bc_important_v4"
        const val CRITICAL_CHANNEL_ID = "bc_critical_v4"
        const val ROOM_NORMAL_CHANNEL_ID = "bc_room_normal_v3"
        const val ROOM_IMPORTANT_CHANNEL_ID = "bc_room_important_v3"
        const val CHAT_NORMAL_CHANNEL_ID = "bc_chat_normal_v3"
        const val CHAT_IMPORTANT_CHANNEL_ID = "bc_chat_important_v3"
        const val SYSTEM_ALERT_CHANNEL_ID = "bc_system_alerts_v3"

        private const val MESSAGE_NOTIFICATION_BASE_ID = 20_000
        private const val NOTIFICATION_HASH_MASK = 0x0fffffff
        private const val ROOM_MESSAGE_GROUP_PREFIX = "dev.vivian.mobilebc.notifications.ROOM_MESSAGES"
        private const val CHAT_MESSAGE_GROUP_PREFIX = "dev.vivian.mobilebc.notifications.CHAT_MESSAGES"
    }
}

private fun BcNotificationChannelKind.displayLabel(context: Context): String = when (this) {
    BcNotificationChannelKind.Normal -> context.getString(R.string.notification_level_normal)
    BcNotificationChannelKind.Important -> context.getString(R.string.notification_level_important)
    BcNotificationChannelKind.Critical -> context.getString(R.string.notification_level_critical)
    BcNotificationChannelKind.ForegroundSession -> context.getString(R.string.notification_foreground_session)

}

private fun BcNotificationImportanceClass.displayLabel(context: Context): String = when (this) {
    BcNotificationImportanceClass.Critical -> context.getString(R.string.notification_level_critical)
    BcNotificationImportanceClass.Normal -> context.getString(R.string.notification_importance_normal)
    BcNotificationImportanceClass.Important -> context.getString(R.string.notification_importance_important)
}

private fun importanceLabel(context: Context, importance: Int): String = when (importance) {
    NotificationManager.IMPORTANCE_NONE -> context.getString(R.string.notification_blocked)
    NotificationManager.IMPORTANCE_MIN -> context.getString(R.string.notification_importance_min)
    NotificationManager.IMPORTANCE_LOW -> context.getString(R.string.notification_importance_low)
    NotificationManager.IMPORTANCE_DEFAULT -> context.getString(R.string.notification_importance_default)
    NotificationManager.IMPORTANCE_HIGH -> context.getString(R.string.notification_importance_high)
    else -> context.getString(R.string.notification_importance_value, importance)
}
