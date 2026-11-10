package dev.vivian.mobilebc.data.storage

data class PersistedDeadLetter(
    val id: String,
    val stage: String,
    val eventName: String,
    val codecName: String?,
    val rawPayload: String,
    val errorCode: String,
    val errorMessage: String,
    val createdAtMillis: Long,
)
