package dev.vivian.mobilebc.feature.characterprofile

import dev.vivian.mobilebc.feature.rooms.R

internal fun relationStageLabel(ownership: Boolean, stage: Int?): Int = if (ownership) {
    when (stage) {
        0 -> R.string.character_relation_trial
        1 -> R.string.character_relation_owned
        else -> R.string.character_relation_stage_unknown
    }
} else {
    when (stage) {
        0 -> R.string.character_relation_dating
        1 -> R.string.character_relation_engaged
        2 -> R.string.character_relation_married
        else -> R.string.character_relation_stage_unknown
    }
}

internal fun relationDurationMinutes(startEpochMillis: Long?, nowMillis: Long): Long? =
    startEpochMillis?.takeIf { it > 0 && it <= nowMillis }?.let { (nowMillis - it) / 60_000 }
