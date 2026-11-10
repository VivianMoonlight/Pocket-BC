package dev.vivian.mobilebc.core.model

/**
 * Logical Android notification channels used by mobile-bc.
 *
 * Android 8+ makes channel settings authoritative for sound, vibration, and heads-up/banner
 * behavior. The app should display these system settings and route notifications to the right
 * channel; it should not pretend to override a channel that the user changed in Android settings.
 */
enum class BcNotificationChannelKind {
    ForegroundSession,
    Normal,
    Important,
    Critical,
}

enum class BcNotificationImportanceClass(val displayLabel: String) {
    Normal("Normal"),
    Important("Important"),
    Critical("Critical"),
}

/**
 * Runtime snapshot of Android notification delivery conditions.
 *
 * App preferences express lightweight in-app routing/suppression. This model reflects what Android
 * will actually do for each notification channel: whether it is allowed, silent, vibrating, or able
 * to show heads-up/banner notifications.
 */
data class BcNotificationDiagnostics(
    val appNotificationsEnabled: Boolean = true,
    val postNotificationsPermissionRequired: Boolean = false,
    val postNotificationsPermissionGranted: Boolean = true,
    val channelsSupported: Boolean = false,
    val channels: List<BcNotificationChannelDiagnostics> = emptyList(),
) {
    val hasPermissionIssue: Boolean
        get() = !appNotificationsEnabled ||
            (postNotificationsPermissionRequired && !postNotificationsPermissionGranted)

    val hasChannelIssue: Boolean
        get() = channels.any { it.isMissing || it.isBlocked }

    val hasIssue: Boolean
        get() = hasPermissionIssue || hasChannelIssue

    val summary: String
        get() = when {
            !appNotificationsEnabled -> "App notifications are disabled in Android settings."
            postNotificationsPermissionRequired && !postNotificationsPermissionGranted ->
                "Android notification permission is not granted."
            channels.any { it.isMissing } -> "One or more notification channels are missing."
            channels.any { it.isBlocked } -> "One or more notification channels are blocked in Android settings."
            channelsSupported -> "Android notification channels are available. Sound, vibration, and banner behavior are controlled in system channel settings."
            else -> "Android notification permission looks usable. Sound and vibration are controlled per notification on this Android version."
        }
}

data class BcNotificationChannelDiagnostics(
    val id: String,
    val name: String,
    val purpose: String,
    val expectedImportanceLabel: String = "System controlled",
    val actualImportance: Int? = null,
    val actualImportanceLabel: String,
    val soundEnabled: Boolean? = null,
    val vibrationEnabled: Boolean? = null,
    val bannerEnabled: Boolean? = null,
    val kind: BcNotificationChannelKind? = null,
    val importanceClass: BcNotificationImportanceClass? = null,
    val isMissing: Boolean = false,
    val isBlocked: Boolean = false,
    val isLowerThanExpected: Boolean = false,
) {
    val statusLabel: String
        get() = when {
            isMissing -> "Missing"
            isBlocked -> "Blocked"
            else -> "System"
        }

    val detailText: String
        get() = buildString {
            append("Android: ")
            append(actualImportanceLabel)
            bannerEnabled?.let { append(if (it) ", banner on" else ", banner off") }
            soundEnabled?.let { append(if (it) ", sound on" else ", sound off") }
            vibrationEnabled?.let { append(if (it) ", vibration on" else ", vibration off") }
        }
}
