package org.ffortuna.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MetadataRulesTest {
    @Test fun descriptionMatchingIsExactAfterWhitespaceCaseAndUnicodeNormalization() {
        assertEquals(MetadataRules.matchKey(" Caf\u00E9\t  Loja "), MetadataRules.matchKey("cafe\u0301\u2003loja"))
        assertNotEquals(MetadataRules.matchKey("Loja A"), MetadataRules.matchKey("Loja-A"))
        assertThrows(FinanceFailure::class.java) { MetadataRules.matchKey(" \t\u00A0 ") }
        assertThrows(FinanceFailure::class.java) { MetadataRules.matchKey("Movimento\u0000inválido") }
    }
}
