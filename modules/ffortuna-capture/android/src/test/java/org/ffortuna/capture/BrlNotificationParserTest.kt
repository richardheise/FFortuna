package org.ffortuna.capture

import org.junit.Assert.*
import org.junit.Test

/** Explicitly invented strings; these are not bank templates or real notifications. */
class BrlNotificationParserTest {
    private fun parse(text: String, field: String = "text") = BrlNotificationParser.parse(listOf(field to text))

    @Test fun parsesExplicitBrlMarkersAndGroupedAmountsAsIntegerCents() {
        assertEquals(1234L, parse("Exemplo fictício: R$ 12,34").amountCents)
        assertEquals(123456L, parse("BRL 1.234,56").amountCents)
        assertEquals(12_345_678_900L, parse("R$ 123.456.789,00").amountCents)
    }

    @Test fun requiresMarkerAndAllowsUnicodeSpacesOnlyAfterMarker() {
        assertNull(parse("valor de teste 12,34").amountCents)
        assertEquals(1234L, parse("R$\u00a012,34").amountCents)
        assertEquals(1234L, parse("BRL\u202f12,34").amountCents)
        assertNull(parse("R$ 1\u00a0234,56").amountCents)
        assertNull(parse("R$ 1\u202f234,56").amountCents)
    }

    @Test fun recordsExactUtf16EvidenceRangeAndLiteral() {
        val text = "☕🙂 R$ 12,34."
        val result = parse(text)
        val evidence = result.evidence.single()
        assertEquals("R$ 12,34", evidence.text)
        assertEquals(text.indexOf("R$"), evidence.start)
        assertEquals(text.indexOf(".", text.indexOf("34")), evidence.end)
        assertEquals(1234L, evidence.amountCents)
    }

    @Test fun identicalLiteralAcrossDifferentFieldsCanBeOneAmount() {
        val result = BrlNotificationParser.parse(listOf("text" to "R$ 12,34", "bigText" to "R$ 12,34"))
        assertEquals("AMOUNT_FOUND", result.reasonCode)
        assertEquals(1234L, result.amountCents)
        assertEquals(2, result.evidence.size)
    }

    @Test fun sameFieldRepetitionIsAmbiguousEvenWhenLiteralMatches() {
        val result = parse("R$ 12,34 e R$ 12,34")
        assertNull(result.amountCents)
        assertEquals("AMBIGUOUS_AMOUNT", result.reasonCode)
    }

    @Test fun multipleValuesAreAmbiguousIncludingTwoPurchases() {
        val result = parse("Duas compras fictícias: R$ 12,34 e R$ 56,78")
        assertNull(result.amountCents)
        assertEquals("AMBIGUOUS_AMOUNT", result.reasonCode)
        assertEquals(2, result.evidence.size)
    }

    @Test fun incompleteForeignAndMalformedMarkedAmountsAreNotChosen() {
        for (text in listOf("R$ 12", "R$ 12,3", "R$ 1,234", "R$ 12.34,56", "R$ 12,34.56", "R$ 1,23abc", "R$ 12,34.abc", "USD 12,34", "US$ 12,34")) {
            val result = parse(text)
            assertNull(text, result.amountCents)
            assertTrue(text, result.evidence.isEmpty() || result.reasonCode == "INVALID_AMOUNT" || result.reasonCode == "NO_AMOUNT")
        }
    }

    @Test fun nakedOrForeignAmountsAreNotReadAsBrl() {
        for (text in listOf("12,34", "-12,34", "+12,34", "€ 12,34", "£ 12,34", "¥ 12,34", "USD 12,34")) {
            assertNull(text, parse(text).amountCents)
        }
    }

    @Test fun negativeMarkerBeforeCurrencyIsPreservedAsInvalidEvidence() {
        for (text in listOf("-R$12,34", "−R$12,34")) {
            val result = parse(text)
            assertNull(result.amountCents)
            assertEquals("INVALID_AMOUNT", result.reasonCode)
            assertEquals(text, result.evidence.single().text)
        }
    }

    @Test fun separatedNegativeSignsCannotBecomePositiveAmounts() {
        for (text in listOf("- R$ 12,34", "− R$ 12,34", "-\u00a0BRL 12,34")) {
            val result=parse(text)
            assertNull(text,result.amountCents)
            assertEquals("INVALID_AMOUNT",result.reasonCode)
            assertEquals(text,result.evidence.single().text)
        }
    }

    @Test fun zeroAndValuesAboveExactIntegerCeilingAreRejected() {
        assertNull(parse("R$ 0,00").amountCents)
        assertNull(parse("R$ 90.071.992.547.409,92").amountCents)
        assertEquals(9007199254740991L, parse("R$ 90.071.992.547.409,91").amountCents)
    }

    @Test fun groupAndTruncatedInputsNeverProduceAnAmountButKeepEvidence() {
        val fields = listOf("text" to "R$ 12,34")
        val group = BrlNotificationParser.parse(fields, groupSummary = true)
        assertNull(group.amountCents); assertEquals("GROUP_SUMMARY", group.reasonCode); assertEquals(1, group.evidence.size)
        val truncated = BrlNotificationParser.parse(fields, truncated = true)
        assertNull(truncated.amountCents); assertEquals("TRUNCATED", truncated.reasonCode); assertEquals(1, truncated.evidence.size)
    }

    @Test fun inputAndEvidenceLimitsFailClosed() {
        assertEquals("INPUT_LIMIT", parse("x".repeat(4097)).reasonCode)
        assertEquals("INPUT_LIMIT", BrlNotificationParser.parse(listOf("text" to "x".repeat(8193), "title" to "x".repeat(8193))).reasonCode)
        val many = (1..65).joinToString(" ") { "R$ 1,00" }
        val overflow = parse(many)
        assertNull(overflow.amountCents)
        assertEquals("EVIDENCE_LIMIT", overflow.reasonCode)
        assertEquals(64, overflow.evidence.size)
    }

    @Test fun reasonCodesAndVersionAreStableForNoAmountAndOneAmount() {
        assertEquals("notification-brl/1", BrlNotificationParser.VERSION)
        assertEquals("NO_AMOUNT", parse("mensagem fictícia sem valor").reasonCode)
        assertEquals("AMOUNT_FOUND", parse("BRL 2,00").reasonCode)
        assertEquals("certain", parse("BRL 2,00").amountConfidence)
        assertEquals("missing", parse("sem valor").amountConfidence)
    }

    @Test fun malformedTokenCannotBeAcceptedByItsValidPrefix() {
        for (text in listOf("R$1,234", "R$12,34.56", "R$12.34,56", "R$1,23abc")) {
            val result = parse(text)
            assertNull(text, result.amountCents)
            assertEquals("INVALID_AMOUNT", result.reasonCode)
            assertEquals(text, result.evidence.single().text)
            assertNull(result.evidence.single().amountCents)
        }
    }

    @Test fun unsupportedPrecisionAndInvalidGroupingRemainEvidenceOnly() {
        for (text in listOf("R$ 1.23,45", "R$ 12 34,56", "R$ 1.23.456,78")) {
            val result = parse(text)
            assertNull(text, result.amountCents)
            assertEquals("INVALID_AMOUNT", result.reasonCode)
        }
    }
}
