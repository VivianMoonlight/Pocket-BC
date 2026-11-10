package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcItemActionMessage
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import kotlin.math.abs
import kotlin.math.floor

internal fun prepareTimerAdjustment(command: BcCharacterItemUpdateCommand, now: Long, language: String): BcCharacterItemUpdateCommand {
    val adjustment = command.adjustLockTimer ?: return command
    check(adjustment.maximumSeconds > 0) { "The timer maximum must be positive." }
    val property = JsonParser.parseString(requireNotNull(command.propertyJson)).asJsonObject
    val previous = property.get("RemoveTimer")
    check(previous?.isJsonPrimitive == true && previous.asJsonPrimitive.isNumber && previous.asDouble.isFinite()) { "The lock timer is invalid." }
    val maximum = Math.addExact(now, Math.multiplyExact(adjustment.maximumSeconds, 1000L))
    val next = Math.round(minOf(previous.asDouble + adjustment.seconds * 1000.0, maximum.toDouble()))
    val delta = next.toDouble() - previous.asDouble
    val show = property.get("ShowTimer")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true
    val action = if (delta == 0.0) null else BcItemActionMessage(
        if (!show) "TimerAddRemoveUnknownTime" else if (delta < 0) "TimerRemoveTime" else "TimerAddTime",
        timerTime = formatTimerDuration(abs(delta), language).takeIf { show })
    return command.copy(propertyJson = BcItemPropertyJson.overlayRawProperties(command.propertyJson, "{\"RemoveTimer\":$next}"), publishAction = action)
}

private fun formatTimerDuration(milliseconds: Double, language: String): String {
    val chinese = language.equals("CN", true) || language.startsWith("zh", true)
    val minutes = floor(milliseconds / 60000).toLong()
    val days = minutes / 1440
    val hours = minutes / 60 % 24
    val remainingMinutes = minutes % 60
    return buildList {
        if (days > 0) add("$days ${if (chinese) "天" else "Days"}")
        if (hours > 0) add("$hours ${if (chinese) "小时" else "Hours"}")
        if (remainingMinutes > 0) add("$remainingMinutes ${if (chinese) "分钟" else "Minutes"}")
        if (isEmpty()) add("${kotlin.math.ceil(milliseconds / 1000).toLong().coerceAtLeast(1)} ${if (chinese) "秒" else "Seconds"}")
    }.joinToString(" ")
}
