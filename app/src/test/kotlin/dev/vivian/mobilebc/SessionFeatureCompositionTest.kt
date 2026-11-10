package dev.vivian.mobilebc

import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginAccount
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.feature.chat.ChatComposerMode
import dev.vivian.mobilebc.feature.chat.ChatComposerState
import dev.vivian.mobilebc.feature.chat.ChatInputState
import dev.vivian.mobilebc.feature.chat.ChatRoomDraft
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFeatureCompositionTest {
    @Test
    fun resourceVersionFollowsSessionAndFallsBackOnlyWhenSessionHasNoVersion() {
        val saved = savedLoginState(emptyList())
        assertEquals("R132Beta2", saved.resourceVersion(" r132beta2 "))
        assertEquals("R131", saved.resourceVersion(null))
        assertEquals("R131", saved.resourceVersion(""))
        assertEquals("R133", saved.copy(bcVersion = "R133").resourceVersion(null))
        assertEquals("", saved.resourceVersion("invalid"))
    }

    @Test
    fun unconfirmedSavedVersionCannotBecomeTheResourceSource() {
        val unconfirmed = savedLoginState(emptyList()).copy(bcVersionConfirmedByUser = false)
        assertEquals("", unconfirmed.resourceVersion(null))
        assertEquals("R132", unconfirmed.resourceVersion("R132"))
        assertEquals("", unconfirmed.copy(bcVersionConfirmedByUser = true, bcVersion = "latest").resourceVersion(null))
    }

    @Test
    fun renamePreservesHiddenWhisperDraftAtDestination() {
        val oldId = BcConversationId("room:before")
        val newId = BcConversationId("room:after")
        val oldInput = MutableStateFlow(ChatInputState(text = "old"))
        val destination = MutableStateFlow(ChatInputState(roomDrafts = mapOf(42 to ChatRoomDraft("private draft"))))
        val inputs = mutableMapOf(oldId to oldInput, newId to destination)

        inputs.moveRoomInputState(oldId, newId)

        assertSame(oldInput, inputs[oldId])
        assertSame(destination, inputs[newId])
        assertEquals("private draft", destination.value.roomDrafts[42]?.text)
    }

    @Test
    fun renamedComposerKeepsInFlightStateAndReceivesItsCompletion() {
        val oldId = BcConversationId("room:before")
        val newId = BcConversationId("room:after")
        val pending = ChatComposerState.Sending("sent", ChatComposerMode.RoomMessage)
        val original = MutableStateFlow(ChatInputState(text = "sent", pendingSend = pending))
        val inputs = mutableMapOf(oldId to original)

        inputs.moveRoomInputState(oldId, newId)

        assertNull(inputs[oldId])
        assertSame(original, inputs[newId])
        assertSame(pending, inputs[newId]?.value?.pendingSend)
        original.value = original.value.copy(text = "", pendingSend = null)
        assertEquals("", inputs[newId]?.value?.text)
        assertNull(inputs[newId]?.value?.pendingSend)
    }

    @Test
    fun renamePreservesExistingDestinationDraft() {
        val oldId = BcConversationId("room:before")
        val newId = BcConversationId("room:after")
        val oldInput = MutableStateFlow(ChatInputState(text = "old"))
        val newInput = MutableStateFlow(ChatInputState(text = "other draft"))
        val inputs = mutableMapOf(oldId to oldInput, newId to newInput)

        inputs.moveRoomInputState(oldId, newId)

        assertSame(oldInput, inputs[oldId])
        assertSame(newInput, inputs[newId])
        assertEquals("other draft", inputs[newId]?.value?.text)
    }

    @Test
    fun recoverableAccountCountRequiresCredentialsAndAConfirmedSystemVersion() {
        val state = savedLoginState(
            accounts = listOf(
                account(accountName = "alice", password = "secret"),
                account(accountName = "", password = "secret"),
                account(accountName = "bob", password = ""),
                account(accountName = "carol", password = "secret"),
            ),
        )

        assertEquals(2, state.recoverableAccountCount())
        assertEquals(0, state.copy(bcVersionConfirmedByUser = false).recoverableAccountCount())
        assertEquals(0, state.copy(bcVersion = "latest").recoverableAccountCount())
    }

    private fun savedLoginState(
        accounts: List<BcSavedLoginAccount>,
    ): BcSavedLoginState = BcSavedLoginState(
        accounts = accounts,
        selectedAccountName = "alice",
        saveCredentialsByDefault = true,
        bcVersion = "R131",
        bcVersionConfirmedByUser = true,
    )

    private fun account(
        accountName: String,
        password: String,
    ): BcSavedLoginAccount = BcSavedLoginAccount(
        accountName = accountName,
        password = password,
        serverUrl = "https://example.test/",
    )
}
