package dev.vivian.mobilebc.domain.session.chat

import dev.vivian.mobilebc.core.model.BcMessageReplyReference

interface BcSessionChat {
    suspend fun sendChat(text: String)
    suspend fun sendChat(text: String, replyTo: BcMessageReplyReference) {
        throw UnsupportedOperationException("Message replies are not supported by this session.")
    }
    suspend fun sendEmote(text: String)
    suspend fun sendEmote(text: String, replyTo: BcMessageReplyReference) {
        throw UnsupportedOperationException("Message replies are not supported by this session.")
    }
    suspend fun sendAction(text: String)
    suspend fun sendWhisper(targetMemberNumber: Int, text: String)
    suspend fun sendWhisper(targetMemberNumber: Int, text: String, replyTo: BcMessageReplyReference) {
        throw UnsupportedOperationException("Message replies are not supported by this session.")
    }
    suspend fun sendBeep(targetMemberNumber: Int, text: String)
}
