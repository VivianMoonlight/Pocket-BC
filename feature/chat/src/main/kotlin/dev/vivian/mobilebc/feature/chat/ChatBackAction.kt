package dev.vivian.mobilebc.feature.chat

internal enum class ChatBackAction { HideKeyboard, ClearSelection, ToolBack, CancelReply, Navigate }

internal fun chatBackAction(
    keyboardVisible: Boolean,
    hasSelection: Boolean,
    tool: ChatComposerTool?,
    hasReply: Boolean,
): ChatBackAction = when {
    keyboardVisible -> ChatBackAction.HideKeyboard
    hasSelection -> ChatBackAction.ClearSelection
    tool != null -> ChatBackAction.ToolBack
    hasReply -> ChatBackAction.CancelReply
    else -> ChatBackAction.Navigate
}
