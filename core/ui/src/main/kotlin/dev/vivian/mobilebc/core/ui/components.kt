package dev.vivian.mobilebc.core.ui

import dev.vivian.mobilebc.core.model.bcEmoteDisplayText

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.DeliveryState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileBcTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    labelColor: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(title, color = bcLabelColor(labelColor) ?: Color.Unspecified, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = bcLabelColor(labelColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            onBack?.let { navigateBack ->
                IconButton(onClick = navigateBack) {
                    Icon(
                        imageVector = MobileBcIcons.Back,
                        contentDescription = stringResource(R.string.core_ui_back),
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    semanticLabel: String = text,
) {
    Surface(
        modifier = modifier.semantics { contentDescription = semanticLabel },
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(50),
    ) {
        Text(text = text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}



enum class SessionStatusTone {
    Neutral,
    Warning,
    Error,
}

@Composable
fun SessionStatusBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: SessionStatusTone = SessionStatusTone.Neutral,
    showProgress: Boolean = false,
) {
    if (text.isBlank()) return

    val colors = when (tone) {
        SessionStatusTone.Neutral -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        SessionStatusTone.Warning -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )

        SessionStatusTone.Error -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = colors,
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = text
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun AvatarToken(
    label: String,
    modifier: Modifier = Modifier,
    semanticLabel: String? = null,
) {
    val resolvedSemanticLabel = semanticLabel ?: stringResource(R.string.core_ui_avatar, label)
    Box(
        modifier = modifier
            .semantics { contentDescription = resolvedSemanticLabel }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1).uppercase(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun ConversationRow(
    conversation: BcConversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = conversation.type == ConversationType.CurrentRoom,
    supportingText: String? = conversation.subtitle,
    trailingText: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (conversation.type == ConversationType.Direct) {
                ChatAvatar(conversation.title, conversation.avatarUrl)
            } else {
                AvatarToken(conversation.title)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                supportingText?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                trailingText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                if (conversation.unreadCount > 0) {
                    StatusChip(conversation.unreadCount.coerceAtMost(99).toString())
                }
            }
        }
    }
}

@Composable
fun RoomRow(room: BcRoomSummary, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(room.name.value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${room.memberCount}/${room.memberLimit}", style = MaterialTheme.typography.labelMedium)
            }
            Text(room.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                room.language?.let { StatusChip(it) }
                StatusChip(room.mapType.displayName)
                room.game?.let { StatusChip(it) }
                if (room.isLocked) StatusChip(stringResource(R.string.core_ui_locked))
            }
            if (room.friends.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.core_ui_friends_here,
                        room.friends.joinToString { it.name },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: BcMessage,
    modifier: Modifier = Modifier,
    conversationTitle: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onSenderClick: (() -> Unit)? = null,
    replyContent: (@Composable () -> Unit)? = null,
    customBackground: Color? = null,
    customText: Color? = null,
) {
    val isAction = message.kind == BcMessageKind.Emote || message.kind == BcMessageKind.Action || message.kind == BcMessageKind.Activity
    val isRoomLine = message.transport == BcMessageTransport.RoomChat ||
        message.transport == BcMessageTransport.RoomWhisper
    val isWhisper = message.transport == BcMessageTransport.RoomWhisper
    val isNarration = isAction || message.transport == BcMessageTransport.LocalSystem
    val labelColor = bcLabelColor(message.sender.labelColor)
    val hasSpeakerColor = labelColor != null && message.kind in setOf(
        BcMessageKind.Chat, BcMessageKind.Whisper, BcMessageKind.Beep,
        BcMessageKind.Emote, BcMessageKind.Action, BcMessageKind.Activity,
    )
    val baseColor = when {
        isWhisper -> MaterialTheme.colorScheme.secondaryContainer
        hasSpeakerColor -> androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, labelColor!!, 0.1f)
        isAction -> MaterialTheme.colorScheme.surfaceVariant
        message.transport == BcMessageTransport.AccountBeep -> MaterialTheme.colorScheme.tertiaryContainer
        isRoomLine || isNarration -> Color.Transparent
        message.sender.isSelf -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val color = if (selected) MaterialTheme.colorScheme.primaryContainer else customBackground ?: baseColor
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        isWhisper -> MaterialTheme.colorScheme.onSecondaryContainer
        hasSpeakerColor -> MaterialTheme.colorScheme.onSurface
        isAction -> MaterialTheme.colorScheme.onSurfaceVariant
        message.transport == BcMessageTransport.AccountBeep -> MaterialTheme.colorScheme.onTertiaryContainer
        isNarration -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val label = message.displayLabel(conversationTitle)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val messageTimeFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
    val formattedTime = messageTimeFormatter.format(message.timestamp)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.sender.isSelf && !isRoomLine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = color,
            contentColor = if (selected) contentColor else customText ?: contentColor,
            shape = MaterialTheme.shapes.small,
            border = if (isWhisper) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            modifier = Modifier
                .then(if (isRoomLine) Modifier.fillMaxWidth() else Modifier)
                .semantics { this.selected = selected }
                .then(
                    when {
                        onLongClick != null -> Modifier.combinedClickable(
                            onClick = onClick ?: {},
                            onLongClick = onLongClick,
                        )
                        onClick != null -> Modifier.clickable(onClick = onClick)
                        else -> Modifier
                    },
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = if (onSenderClick != null && label != null && onClick == null) {
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSenderClick)
                            .heightIn(min = 48.dp)
                    } else {
                        Modifier.padding(bottom = 4.dp)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isRoomLine && message.sender.memberNumber != null && message.kind in setOf(
                            BcMessageKind.Chat, BcMessageKind.Whisper, BcMessageKind.Beep, BcMessageKind.Emote,
                        )
                    ) {
                        ChatAvatar(message.sender.displayName, message.sender.avatarUrl)
                    }
                    Text(
                        text = chatHeaderText(
                            label = label,
                            identity = message.sender.displayName.takeIf {
                                !message.sender.isSelf || message.transport == BcMessageTransport.RoomChat
                            },
                            time = formattedTime,
                            identityColor = labelColor.takeIf { hasSpeakerColor && !selected },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            selected -> MaterialTheme.colorScheme.onPrimaryContainer
                            customText != null -> customText
                            isWhisper -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                replyContent?.invoke()
                Text(
                    message.displayText(),
                    style = when {
                        isWhisper -> MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp)
                        isAction -> MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
                        else -> MaterialTheme.typography.bodyMedium
                    },
                )
                ChatImages(message.text, enabled = onClick == null && !selected && LocalScreenInteractive.current)
                message.deliveryStatusLabel()?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.delivery == DeliveryState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private fun BcMessage.displayText(): String = when (kind) {
    BcMessageKind.Action,
    BcMessageKind.Activity,
    -> "($text)"

    BcMessageKind.Emote -> "*${bcEmoteDisplayText(text, sender.displayName)}*"
    else -> text
}

@Composable
private fun BcMessage.displayLabel(conversationTitle: String?): String? = when (transport) {
    BcMessageTransport.AccountBeep -> if (sender.isSelf) {
        stringResource(R.string.core_ui_beep_to, directTargetName(conversationTitle))
    } else {
        stringResource(R.string.core_ui_beep_from, sender.displayName)
    }
    BcMessageTransport.RoomWhisper -> if (sender.isSelf) {
        stringResource(R.string.core_ui_whisper_to, directTargetName(conversationTitle))
    } else {
        stringResource(R.string.core_ui_whisper_from, sender.displayName)
    }
    BcMessageTransport.LocalSystem -> stringResource(R.string.core_ui_system)
    BcMessageTransport.ProtocolEvent -> stringResource(R.string.core_ui_protocol)
    BcMessageTransport.RoomChat -> when (kind) {
        BcMessageKind.Chat -> sender.displayName
        BcMessageKind.Emote,
        BcMessageKind.Action,
        BcMessageKind.Activity,
        BcMessageKind.Whisper,
        BcMessageKind.Beep,
        BcMessageKind.System,
        BcMessageKind.Error,
        -> null
    }
}

private fun BcMessage.directTargetName(conversationTitle: String?): String =
    conversationTitle?.takeIf(String::isNotBlank) ?: "#${targetMemberNumber ?: "unknown"}"

@Composable
private fun BcMessage.deliveryStatusLabel(): String? =
    if (!sender.isSelf) {
        null
    } else {
        when (delivery) {
            DeliveryState.Sending -> stringResource(R.string.core_ui_sending)
            DeliveryState.Failed -> stringResource(R.string.core_ui_not_delivered)
            DeliveryState.Sent -> null
        }
    }
