package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.protocol.DeadLetterSink
import dev.vivian.mobilebc.domain.session.auth.BcSessionPreferencesStore

/**
 * App-wide storage facade.
 *
 * These repositories are intentionally not bound to a BC account/member number. Account-specific
 * message, outbox, inbox, cursor, and timeline ports belong under [BcPersistenceManager.runtime]
 * and take an explicit owner member number.
 */
class SharedRepositories internal constructor(
    val sessionPreferences: BcSessionPreferencesStore,
    val deadLetterSink: DeadLetterSink,
)
