package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.domain.bccharacterstate.BcLockInputConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BcCombinationInputTest {
    @Test fun inputMatchesTheVectorsAlsoExecutedByTheOfficialR133Handler() {
        val fixtures = requireNotNull(javaClass.classLoader?.getResource("bc-fixtures/combination-input-r133.json")).readText()
        BcItemPropertyJson.readObjectArray(fixtures).forEach { fixture ->
            val id = fixture.readBcStringProperty("id")
            val random = requireNotNull(BcItemPropertyJson.readPropertyValue(fixture, "random")).trim().removeSurrounding("[", "]")
                .split(',').filter { it.isNotBlank() }.map { it.toDouble() }.iterator()
            val input = BcCombinationInput(BcLockInputConditions(fixture.readBcBooleanProperty("blind") == true, fixture.readBcIntProperty("clumsiness") ?: 0)) { random.next() }
            BcItemPropertyJson.readObjectArray(BcItemPropertyJson.readPropertyValue(fixture, "edits")).forEach { edit ->
                fun string(key: String) = requireNotNull(BcItemPropertyJson.readStringValue(BcItemPropertyJson.readPropertyValue(edit, key)))
                assertEquals(id, string("expected"), input.edit(string("value")))
            }
            assertTrue("Unused random values: $id", !random.hasNext())
        }
    }
}
