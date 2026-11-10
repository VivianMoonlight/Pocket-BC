package dev.vivian.mobilebc.core.model

/** Timestamp bounds are inclusive at the start and exclusive at the end. */
data class BcMessageHistoryQuery(
    val text: String = "",
    val fromMillis: Long? = null,
    val untilMillis: Long? = null,
    val oldestFirst: Boolean = false,
    val limit: Int = 101,
)
