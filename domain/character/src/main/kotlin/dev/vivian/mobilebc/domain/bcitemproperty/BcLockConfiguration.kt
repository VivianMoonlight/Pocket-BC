package dev.vivian.mobilebc.domain.bcitemproperty

sealed interface BcLockConfiguration {
    data class InitializePassword(val password: BcLockCredential, val hint: String, val removeOnUnlock: Boolean? = null) : BcLockConfiguration
    data class ChangeCombination(val current: BcLockCredential, val next: BcLockCredential) : BcLockConfiguration
    data class UpdateKeyholders(val memberNumbers: String, val appendOwner: Boolean = true, val appendLovers: Boolean = true, val appendWhitelist: Boolean = false) : BcLockConfiguration
}

enum class BcLockConfigurationMode {
    InitializePassword, ChangeCombination, UpdateKeyholders;

    companion object {
        fun forLock(propertyJson: String?, actorMemberNumber: Int): BcLockConfigurationMode? =
            if (propertyJson.readBcStringProperty("LockedBy") == "HighSecurityPadlock") UpdateKeyholders else when (BcLockInputKind.forLock(propertyJson.readBcStringProperty("LockedBy"))) {
                BcLockInputKind.Combination -> ChangeCombination
                BcLockInputKind.Password -> if (propertyJson.readBcBooleanProperty("LockSet") != true &&
                    propertyJson.readBcIntProperty("LockMemberNumber")?.let { it == actorMemberNumber } != false) InitializePassword else null
                null -> null
            }
    }
}

fun validBcNewLockPassword(value: String): Boolean = value.length <= 8 && (value.isEmpty() || value.uppercase().matches(Regex("[A-Z]{1,8}")))
