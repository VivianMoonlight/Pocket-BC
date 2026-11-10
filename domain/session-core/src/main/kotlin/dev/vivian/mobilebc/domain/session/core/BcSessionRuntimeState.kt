package dev.vivian.mobilebc.domain.session.core

import dev.vivian.mobilebc.core.model.BcServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BcRememberedLogin(val accountName: String, val password: String?)

data class BcRememberedRoomTarget(val roomName: String, val password: String? = null)

class BcSessionRuntimeState {
    var lastServerConfig: BcServerConfig? = null
    @Volatile
    var rememberedLogin: BcRememberedLogin? = null
        private set

    val lastAccountName: String? get() = rememberedLogin?.accountName
    val lastPassword: String? get() = rememberedLogin?.password

    @Synchronized
    fun rememberLogin(accountName: String?, password: String?) {
        rememberedLogin = accountName?.let { BcRememberedLogin(it, password) }
    }

    @Synchronized
    fun forgetLoginPassword() {
        rememberedLogin = rememberedLogin?.copy(password = null)
    }

    @Volatile
    var rememberedRoomTarget: BcRememberedRoomTarget? = null
        private set

    val lastJoinedRoomName: String? get() = rememberedRoomTarget?.roomName
    val lastJoinedRoomPassword: String? get() = rememberedRoomTarget?.password

    fun rememberRoomTarget(roomName: String?, password: String? = null) {
        rememberedRoomTarget = roomName?.let { BcRememberedRoomTarget(it, password) }
    }

    private val _snapshot = MutableStateFlow(BcSessionSnapshot())
    val snapshot: StateFlow<BcSessionSnapshot> = _snapshot.asStateFlow()

    val current: BcSessionSnapshot
        get() = _snapshot.value

    fun updateSession(transform: (BcSessionSnapshot) -> BcSessionSnapshot) {
        _snapshot.update(transform)
    }

}
