package dev.vivian.mobilebc.platform.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.vivian.mobilebc.domain.sync.OutboxDrainCoordinator
import dev.vivian.mobilebc.domain.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

class AndroidWorkManagerSyncScheduler(
    context: Context,
) : SyncScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun scheduleOutboxDrain() {
        val request = OneTimeWorkRequestBuilder<OutboxDrainWorker>()
            .setConstraints(networkConnectedConstraints())
            .addTag(TAG_OUTBOX_DRAIN)
            .build()

        workManager.enqueueUniqueWork(
            UNIQUE_OUTBOX_DRAIN,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConnectedConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    private companion object {
        private const val TAG_OUTBOX_DRAIN = "bc_outbox_drain"
        private const val UNIQUE_OUTBOX_DRAIN = "bc_unique_outbox_drain"
    }
}

class OutboxDrainWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Process-local dependencies are registered by the active session service. A cold
        // WorkManager process must not acknowledge pending outbox work as completed before that
        // owner is available.
        val target = OutboxDrainWorkerDependencies.currentTarget() ?: return Result.retry()
        val result = runCatching {
            target.coordinator.drain(ownerMemberNumber = target.ownerMemberNumber)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return Result.retry()
        }
        return if (result.shouldRetry) Result.retry() else Result.success()
    }
}

object OutboxDrainWorkerDependencies {
    internal data class DrainTarget(
        val coordinator: OutboxDrainCoordinator,
        val ownerMemberNumber: Int,
    )
    private data class DependencySet(
        val coordinator: OutboxDrainCoordinator,
        val currentOwnerMemberNumber: () -> Int?,
    )

    private val dependencyRef = AtomicReference<DependencySet?>()

    fun register(
        coordinator: OutboxDrainCoordinator,
        currentOwnerMemberNumber: () -> Int?,
    ) {
        dependencyRef.set(
            DependencySet(
                coordinator = coordinator,
                currentOwnerMemberNumber = currentOwnerMemberNumber,
            )
        )
    }

    fun unregister(coordinator: OutboxDrainCoordinator) {
        val current = dependencyRef.get() ?: return
        if (current.coordinator === coordinator) {
            dependencyRef.compareAndSet(current, null)
        }
    }

    internal fun currentTarget(): DrainTarget? {
        val dependencies = dependencyRef.get() ?: return null
        val owner = dependencies.currentOwnerMemberNumber() ?: return null
        return DrainTarget(dependencies.coordinator, owner)
    }
}
