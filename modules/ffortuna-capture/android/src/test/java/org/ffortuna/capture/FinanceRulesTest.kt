package org.ffortuna.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class FinanceRulesTest {
    @Test fun centsAreExactSignedSafeIntegersAndRejectWrongTypes() {
        assertEquals(1L, FinanceRules.cents(1))
        assertEquals(FinanceRules.MAX, FinanceRules.cents(FinanceRules.MAX))
        assertEquals(0L, FinanceRules.cents(0, signed = true))
        assertEquals(-FinanceRules.MAX, FinanceRules.cents(-FinanceRules.MAX, signed = true))
        for (value in listOf(0L, -1L, FinanceRules.MAX + 1, 1.0, "1", null)) {
            assertThrows(FinanceFailure::class.java) { FinanceRules.cents(value) }
        }
        assertEquals(-1L, FinanceRules.cents(-1, signed = true))
        assertThrows(FinanceFailure::class.java) { FinanceRules.cents(-FinanceRules.MAX - 1, signed = true) }
    }

    @Test fun aggregateUsesOverflowSafeArithmeticAtJsSafeBoundary() {
        assertEquals(FinanceRules.MAX, FinanceRules.sum(listOf(FinanceRules.MAX, 1L, -1L)))
        assertEquals(-FinanceRules.MAX, FinanceRules.sum(listOf(-FinanceRules.MAX, -1L, 1L)))
        assertThrows(FinanceFailure::class.java) { FinanceRules.sum(listOf(FinanceRules.MAX, 1L)) }
        assertThrows(FinanceFailure::class.java) { FinanceRules.sum(listOf(-FinanceRules.MAX, -1L)) }
    }

    @Test fun datesRequireRealCanonicalCalendarDaysAndMonths() {
        assertEquals("2024-02-29", FinanceRules.date("2024-02-29"))
        assertEquals("2026-10", FinanceRules.month("2026-10"))
        for (value in listOf("2023-02-29", "2026-2-01", "2026-10-1", "0000-01-01", "2026-13-01")) {
            assertThrows(FinanceFailure::class.java) { FinanceRules.date(value) }
        }
        assertThrows(FinanceFailure::class.java) { FinanceRules.month("2026-1") }
        assertThrows(FinanceFailure::class.java) { FinanceRules.month("2026-13") }
        assertEquals(LocalDate.now().toString(), FinanceRules.date(LocalDate.now().toString()))
    }

    @Test fun labelsBoundLengthAndRejectControlsButPermitNewlineTab() {
        assertEquals("x".repeat(80), FinanceRules.text("x".repeat(80), 80))
        assertEquals("linha\n\t2", FinanceRules.text("linha\n\t2", 80))
        assertThrows(FinanceFailure::class.java) { FinanceRules.text("x".repeat(81), 80) }
        assertThrows(FinanceFailure::class.java) { FinanceRules.text("a\u0000b", 80) }
        assertThrows(FinanceFailure::class.java) { FinanceRules.text("a\u001bb", 80) }
    }
}
