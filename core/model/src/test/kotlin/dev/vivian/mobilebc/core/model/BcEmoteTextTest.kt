package dev.vivian.mobilebc.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BcEmoteTextTest {
    @Test
    fun normalEmoteIncludesSenderAndBcActionConsumesControlStar() {
        assertEquals("Alice waves", bcEmoteDisplayText("waves", "Alice"))
        assertEquals("The lights dim", bcEmoteDisplayText("*The lights dim", "Alice"))
    }

    @Test
    fun possessiveAndCommaEmotesJoinSenderWithoutExtraSpace() {
        assertEquals("Alice's eyes widen", bcEmoteDisplayText("'s eyes widen", "Alice"))
        assertEquals("Alice, looking around", bcEmoteDisplayText(", looking around", "Alice"))
        assertEquals("waves", bcEmoteDisplayText("waves", ""))
    }
}
