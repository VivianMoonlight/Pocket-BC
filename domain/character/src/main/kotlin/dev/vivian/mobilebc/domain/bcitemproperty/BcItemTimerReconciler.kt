package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcItemActionMessage
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.InteractionGateway
import dev.vivian.mobilebc.domain.session.core.estimatedServerTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native TimerInventoryRemove runs for the authenticated wearer, independently of item permissions. */
class BcItemTimerReconciler(
    private val gateway: InteractionGateway,
    private val snapshots: StateFlow<BcSessionSnapshot>,
    private val serverTime: (BcSessionSnapshot) -> Long? = { it.estimatedServerTimeMillis() },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val diagnostic: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private var trackedSession: TimerSession? = null
    private val attempts = mutableMapOf<String, Attempt>()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                snapshots.first { it.timerSession() != null && serverTime(it) != null && it.self()?.appearance.orEmpty().any { item -> item.expiry() != null } }
                reconcile()
                delay(1_000)
            }
        }
    }

    suspend fun reconcile() = mutex.withLock {
        val initial = snapshots.value
        val session = initial.timerSession() ?: return@withLock
        if (trackedSession != session) { trackedSession = session; attempts.clear() }
        val now = serverTime(initial) ?: return@withLock
        val expired = initial.self()?.appearance.orEmpty().filter { it.expiredAt(now) }
        attempts.keys.retainAll(expired.map { it.group }.toSet())
        for (item in expired) {
            val current = snapshots.value
            if (current.timerSession() != session) return@withLock
            val self = current.self() ?: return@withLock
            if (self.appearance.firstOrNull { it.group == item.group } != item) continue
            val last = attempts[item.group]
            val attemptedAt = monotonicMillis()
            if (last?.item == item && attemptedAt - last.atMillis in 0 until 15_000L) continue
            val remove = BcItemPropertyJson.readExactPropertyValue(item.propertyJson, "RemoveItem") == "true"
            val lock = BcItemPropertyJson.readStringValue(BcItemPropertyJson.readExactPropertyValue(item.propertyJson, "LockedBy"))?.takeIf(String::isNotBlank)
            val command = BcCharacterItemUpdateCommand(self.memberNumber, item.group,
                name = item.name.takeUnless { remove }, colorJson = item.colorJson.takeUnless { remove },
                difficulty = item.difficulty.takeUnless { remove }, craftJson = item.craftJson.takeUnless { remove },
                propertyJson = if (remove) null else BcItemPropertyJson.withoutLock(item.propertyJson),
                expireTimerSource = item,
                publishAction = lock?.let { BcItemActionMessage("TimerRelease", lockName = it,
                    destinationCharacterName = self.nickname?.takeIf(String::isNotBlank) ?: self.name) })
            attempts[item.group] = Attempt(item, attemptedAt)
            try {
                gateway.updateCharacterItem(command, listOf(self)) {
                    val latest = snapshots.value
                    check(latest.timerSession() == session && latest.self()?.appearance?.firstOrNull { it.group == item.group } == item &&
                        serverTime(latest)?.let { item.expiredAt(it) } == true) { "The timed item or session changed before expiry was sent." }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                diagnostic("Item timer update failed for ${item.group}: ${failure.message ?: failure::class.java.simpleName}")
            }
        }
    }

    private fun BcCharacterItem.expiry(): Double? = BcItemPropertyJson.readExactPropertyValue(propertyJson, "RemoveTimer")?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun BcCharacterItem.expiredAt(now: Long): Boolean = expiry()?.let { it <= now.toDouble() } == true
    private fun BcSessionSnapshot.self() = roomCharacters.firstOrNull { it.isSelf && it.memberNumber == account?.memberNumber }
    private fun BcSessionSnapshot.timerSession(): TimerSession? = account?.takeIf { isVerifiedOnline && currentRoom != null && self() != null }?.let {
        TimerSession(it.memberNumber, relationships.accountGeneration, selfRuleLogSessionId, roomPresenceEpoch)
    }
    private data class TimerSession(val member: Int, val generation: Long, val login: String?, val roomVisit: Long)
    private data class Attempt(val item: BcCharacterItem, val atMillis: Long)
}
