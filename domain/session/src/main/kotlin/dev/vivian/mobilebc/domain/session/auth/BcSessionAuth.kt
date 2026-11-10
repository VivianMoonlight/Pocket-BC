package dev.vivian.mobilebc.domain.session.auth

interface BcSessionAuth {
    suspend fun login(accountName: String, password: String)
    suspend fun logout()
}
