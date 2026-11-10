package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcItemTimerAdjustment
import org.junit.Assert.*
import org.junit.Test

class RazorTimerUpdatesTest {
    @Test fun preparedDeadlinesMatchTheFixturesExecutedByTheOfficialTimerControls() {
        val rows = JsonParser.parseString(requireNotNull(javaClass.classLoader?.getResource("bc-timer-edit-r133.json")).readText()).asJsonArray
        rows.forEach { row ->
            val f = row.asJsonObject
            if (!f.has("seconds")) return@forEach
            val command = BcCharacterItemUpdateCommand(20, "ItemArms", "LeatherCuffs",
                propertyJson = """{"RemoveTimer":${f["before"]},"ShowTimer":true,"Text":"中文😀","Custom":{"keep":true}}""",
                adjustLockTimer = BcItemTimerAdjustment(f["seconds"].asInt, (f["limit"] ?: f["maximum"]).asLong))
            val result = prepareTimerAdjustment(command, f["now"].asLong, "CN")
            val property = JsonParser.parseString(result.propertyJson).asJsonObject
            assertEquals(f["id"].asString, f["after"].asLong, property["RemoveTimer"].asLong)
            assertEquals("中文😀", property["Text"].asString)
            assertTrue(property.getAsJsonObject("Custom")["keep"].asBoolean)
            val delta = f["after"].asLong - f["before"].asLong
            assertEquals(if (delta == 0L) null else if (delta < 0) "TimerRemoveTime" else "TimerAddTime", result.publishAction?.content)
            assertFalse(buildCharacterItemUpdatePayload(result).has("adjustLockTimer"))
        }
    }

    @Test fun hiddenTimeIsNotIncludedInTheActionAndClampingUsesTheActualDelta() {
        val hidden = BcCharacterItemUpdateCommand(20, "ItemArms", "LeatherCuffs", propertyJson = """{"RemoveTimer":1000000,"ShowTimer":false}""",
            adjustLockTimer = BcItemTimerAdjustment(300, 100))
        val outgoing = prepareTimerAdjustment(hidden, 1000, "CN")
        assertEquals("TimerAddRemoveUnknownTime", outgoing.publishAction?.content)
        assertNull(outgoing.publishAction?.timerTime)
        assertTrue(!buildItemActionChatPayload(outgoing, 10).toString().contains("TimerTime"))
        val shown = prepareTimerAdjustment(hidden.copy(propertyJson = """{"RemoveTimer":1000000,"ShowTimer":true}"""), 1000, "CN")
        assertEquals("TimerRemoveTime", shown.publishAction?.content)
        assertEquals("14 分钟", shown.publishAction?.timerTime)
    }
}
