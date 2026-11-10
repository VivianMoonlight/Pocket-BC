package dev.vivian.mobilebc.domain.bctext

import org.junit.Assert.assertEquals
import org.junit.Test

class BcCsvRowsTest {
    @Test
    fun quotedCommasAndEscapedQuotesStayInTheSameCell() {
        assertEquals(
            listOf(listOf("key", "hello, \"Bob\"", "")),
            parseBcCsvRows("key,\"hello, \"\"Bob\"\"\","),
        )
    }

    @Test
    fun quotedLineBreaksStayInTheSameRecord() {
        assertEquals(
            listOf(listOf("key", "first\r\nsecond"), listOf("next", "value")),
            parseBcCsvRows("key,\"first\r\nsecond\"\r\nnext,value"),
        )
    }

    @Test
    fun blankRecordsAreIgnoredAndCrLfIsOneRecordBoundary() {
        assertEquals(
            listOf(listOf("first", "value"), listOf("second", "value")),
            parseBcCsvRows("\r\n , \r\nfirst,value\r\n\nsecond,value\r\n"),
        )
    }

    @Test
    fun emptyCellsAndTrailingCellsArePreserved() {
        assertEquals(
            listOf(listOf("group", "", "name"), listOf("key", "")),
            parseBcCsvRows("group,,name\nkey,"),
        )
    }
}
