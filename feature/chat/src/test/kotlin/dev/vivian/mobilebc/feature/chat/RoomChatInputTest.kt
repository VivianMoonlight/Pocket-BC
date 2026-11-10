package dev.vivian.mobilebc.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomChatInputTest {
    @Test
    fun muStyleColonRequiresThePreferenceAndMoreThanThreeUtf16Units() {
        assertEquals(RoomChatInput.Emote("waves*"), parseRoomChatInput(" :waves* ", muStylePoses = true))
        assertEquals(RoomChatInput.Emote("abc"), parseRoomChatInput(":abc", muStylePoses = true))
        listOf(":", ":a", ":ab", ":\uD83D\uDE00").forEach { draft ->
            assertEquals(RoomChatInput.Chat(draft), parseRoomChatInput(draft, muStylePoses = true))
        }
        assertEquals(RoomChatInput.Emote("\uD83D\uDE00x"), parseRoomChatInput(":\uD83D\uDE00x", muStylePoses = true))
        assertEquals(RoomChatInput.Chat(":waves*"), parseRoomChatInput(":waves*"))
        assertEquals(RoomChatInput.Chat("\u200B:waves"), parseRoomChatInput("\\:waves", muStylePoses = true))
    }

    @Test
    fun bcEmoteSyntaxUsesEmotePayloadAndKeepsBodyCase() {
        assertEquals(RoomChatInput.Emote("Waves Hello"), parseRoomChatInput(" /ME\tWaves Hello "))
        assertEquals(RoomChatInput.Emote("waves"), parseRoomChatInput("*waves*"))
        assertEquals(RoomChatInput.Emote("*The Lights Dim"), parseRoomChatInput("/action The Lights Dim"))
        assertEquals(RoomChatInput.Emote("*The Lights Dim"), parseRoomChatInput("**The Lights Dim*"))
    }

    @Test
    fun emptyEmotesAndUnknownCommandsStayLocal() {
        listOf("/me", "/ME\t ", "/action", "*", "**", "* *").forEach { text ->
            assertEquals(text, RoomChatInput.Invalid(ChatComposerValidationError.EmptyEmote), parseRoomChatInput(text))
        }
        listOf("/dice 2d6", "/meow hello", "/emote waves", "/roll", "/safeword", "/unknown secret").forEach { text ->
            assertEquals(text, RoomChatInput.Invalid(ChatComposerValidationError.UnsupportedRoomCommand), parseRoomChatInput(text))
        }
    }

    @Test
    fun escapedCommandsAndEmotesAreLiteralChat() {
        assertEquals(RoomChatInput.Chat("/me waves"), parseRoomChatInput("//me waves"))
        assertEquals(RoomChatInput.Chat("/w 123 private"), parseRoomChatInput("//w 123 private"))
        assertEquals(RoomChatInput.Chat("\u200B*waves*"), parseRoomChatInput("\\*waves*"))
        assertEquals(RoomChatInput.Chat("\u200B/me waves"), parseRoomChatInput("\\/me waves"))
        assertEquals(RoomChatInput.Chat("hello /me waves"), parseRoomChatInput("hello /me waves"))
    }

    @Test
    fun whisperParsingRejectsMissingOrInvalidRecipientAndPreservesMessage() {
        assertEquals(RoomChatInput.Whisper(123, "Hello  There"), parseRoomChatInput("/WHISPER\t123 Hello  There"))
        listOf("/w", "/w 123", "/w nope secret", "/w 0 secret", "/w -2 secret", "/w 2147483648 secret").forEach { text ->
            assertEquals(text, RoomChatInput.Invalid(ChatComposerValidationError.InvalidWhisperCommand), parseRoomChatInput(text))
        }
    }
}
