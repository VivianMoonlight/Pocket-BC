package dev.vivian.mobilebc.domain.bccharacterstate

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog

class DefaultBcCharacterStateResolver {
    fun resolve(
        character: BcCharacterSnapshot,
        rules: BcRuleCatalog,
        source: BcCharacterItemSource,
    ): BcCharacterRuleState {
        val key = CharacterRuleStateCacheKey(
            characterIdentity = System.identityHashCode(character),
            memberNumber = character.memberNumber,
            appearanceHash = character.appearance.hashCode(),
            activePoseHash = character.activePose.hashCode(),
            poseStateHash = character.poseState.hashCode(),
            bodyHash = character.body.hashCode(),
            ruleCatalogIdentity = System.identityHashCode(rules),
            ruleCatalogVersion = rules.version,
            source = source,
        )
        synchronized(cacheLock) {
            cache[key]?.let { return it }
        }
        val state = BcCharacterRuleState(
            character = character,
            wornItems = character.appearance.map { item ->
                BcWornItemState(
                    item = item,
                    assetRule = rules.asset(item.group, item.name),
                    source = source,
                )
            },
        )
        synchronized(cacheLock) {
            cache[key] = state
        }
        return state
    }

    private data class CharacterRuleStateCacheKey(
        val characterIdentity: Int,
        val memberNumber: Int,
        val appearanceHash: Int,
        val activePoseHash: Int,
        val poseStateHash: Int,
        val bodyHash: Int,
        val ruleCatalogIdentity: Int,
        val ruleCatalogVersion: String,
        val source: BcCharacterItemSource,
    )

    private companion object {
        const val CACHE_LIMIT = 64
        val cacheLock = Any()
        val cache = object : LinkedHashMap<CharacterRuleStateCacheKey, BcCharacterRuleState>(
            CACHE_LIMIT,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CharacterRuleStateCacheKey, BcCharacterRuleState>?): Boolean =
                size > CACHE_LIMIT
        }
    }
}
