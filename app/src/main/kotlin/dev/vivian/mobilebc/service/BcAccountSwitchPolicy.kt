package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState

internal fun BcSavedLoginState.confirmedNormalizedBcVersionOrNull(): String? =
    bcVersion
        .takeIf { version -> bcVersionConfirmedByUser && version.isNotBlank() }
        ?.normalizedBcVersionOrNull()
