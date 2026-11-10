package dev.vivian.mobilebc.domain.sync

interface SyncScheduler {
    fun scheduleOutboxDrain()
}
