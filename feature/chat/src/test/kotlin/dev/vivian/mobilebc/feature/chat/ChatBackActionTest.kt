package dev.vivian.mobilebc.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatBackActionTest {
    @Test fun backRemovesOneLayerAtATime() {
        assertEquals(ChatBackAction.HideKeyboard, chatBackAction(true, true, ChatComposerTool.Item, true))
        assertEquals(ChatBackAction.ClearSelection, chatBackAction(false, true, ChatComposerTool.Item, true))
        assertEquals(ChatBackAction.ToolBack, chatBackAction(false, false, ChatComposerTool.Item, true))
        assertEquals(ChatBackAction.CancelReply, chatBackAction(false, false, null, true))
        assertEquals(ChatBackAction.Navigate, chatBackAction(false, false, null, false))
    }

    @Test fun everyToolDelegatesBackToItsExistingWorkflow() {
        ChatComposerTool.entries.forEach { tool ->
            assertEquals(ChatBackAction.ToolBack, chatBackAction(false, false, tool, false))
        }
    }
}
