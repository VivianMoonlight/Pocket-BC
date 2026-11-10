package dev.vivian.mobilebc.domain.bcitemproperty

/** Input stays in the permission request; socket commands never contain the attempted code. */
data class BcLockCredential(val value: String) {
    override fun toString(): String = "BcLockCredential([redacted])"
}

enum class BcLockInputKind {
    Password,
    Combination;

    val maxLength: Int get() = if (this == Combination) 4 else 8

    fun acceptsInput(value: String): Boolean = when (this) {
        Password -> value.isNotEmpty() && value.length <= maxLength
        Combination -> value.matches(Regex("[0-9]{4}"))
    }

    internal val propertyKey: String get() = if (this == Combination) "CombinationNumber" else "Password"

    internal fun matches(input: BcLockCredential, propertyJson: String?): Boolean {
        if (!acceptsInput(input.value)) return false
        val expected = propertyJson.readBcStringProperty(propertyKey) ?: return false
        return (if (this == Password) input.value.uppercase() else input.value) == expected
    }

    companion object {
        fun forLock(lockName: String?): BcLockInputKind? = when (lockName) {
            "PasswordPadlock", "SafewordPadlock", "TimerPasswordPadlock" -> Password
            "CombinationPadlock" -> Combination
            else -> null
        }
    }
}
