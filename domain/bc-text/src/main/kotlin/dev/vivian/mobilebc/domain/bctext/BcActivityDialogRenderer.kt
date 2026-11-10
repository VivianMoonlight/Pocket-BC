package dev.vivian.mobilebc.domain.bctext

/** Renders the placeholder vocabulary used by native and extension BC activity dialogs. */
fun String.renderBcActivityDialog(
    sourceDisplayName: String,
    targetDisplayName: String,
    groupDisplayName: String = "",
    subjectPronoun: String = "they",
    objectPronoun: String = "them",
    possessivePronoun: String = "their",
): String {
    val group = groupDisplayName
    return replace("SourceCharacter", sourceDisplayName)
        .replace("DestinationCharacter", targetDisplayName)
        .replace("TargetCharacter", targetDisplayName)
        .replace("\${group}", group)
        .replace("\$group", group)
        .replace("{group}", group)
        .replace("\${target}", group)
        .replace("\$target", group)
        .replace("PronounPossessive", possessivePronoun)
        .replace("PronounObject", objectPronoun)
        .replace("Pronoun", subjectPronoun)
}
