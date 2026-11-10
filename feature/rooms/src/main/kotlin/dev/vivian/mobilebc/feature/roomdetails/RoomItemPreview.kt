package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot

/** A local rendering draft; this function does not produce an executable interaction. */
internal fun itemPreviewCharacter(target: BcCharacterSnapshot, entry: InteractionItemPickerEntryUiState): BcCharacterSnapshot? {
    if (entry.operation !in setOf("Wear", "Replace", "Modify")) return null
    if (entry.candidate != null && entry.candidate.targetMemberNumber != target.memberNumber) return null
    val name = entry.itemName ?: return null
    val current = target.appearance.firstOrNull { it.group == entry.group }
    val item = if (entry.operation == "Modify") {
        if (current?.name != name) return null
        current.copy(propertyJson = entry.propertyJson ?: current.propertyJson, difficulty = entry.difficulty ?: current.difficulty)
    } else BcCharacterItem(entry.group, name, propertyJson = entry.propertyJson, difficulty = entry.difficulty)
    val appearance = if (current == null) target.appearance + item else target.appearance.map { if (it.group == entry.group) item else it }
    return target.copy(appearance = appearance)
}
