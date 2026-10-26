package org.ffortuna.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test fun masksStructuredSyntheticExamplesButKeepsOrdinaryText() {
        val input = "Contato teste@example.invalid; site https://example.invalid/perfil?id=123; portal example.invalid. " +
            "UUID 123e4567-e89b-12d3-a456-426614174000 e 00000000-0000-0000-0000-000000000000; CPF 529.982.247-25; CNPJ 11.222.333/0001-81; " +
            "fone +55 (11) 91234-5678; em 2026-10-25 foram R$ 1.234,56."
        val redacted = DiagnosticRedactor.suggest(input)
        assertFalse(redacted.contains("teste@example.invalid"))
        assertFalse(redacted.contains("https://example.invalid"))
        assertFalse(redacted.contains("portal example.invalid"))
        assertFalse(redacted.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(redacted.contains("00000000-0000-0000-0000-000000000000"))
        assertFalse(redacted.contains("529.982.247-25"))
        assertFalse(redacted.contains("11.222.333/0001-81"))
        assertFalse(redacted.contains("+55 (11) 91234-5678"))
        assertFalse(redacted.contains("2026-10-25"))
        assertTrue(redacted.contains("R$ 12,34"))
        assertTrue(redacted.startsWith("Contato"))
        assertTrue(redacted.endsWith("."))
        assertTrue(DiagnosticRedactor.validateReviewed(redacted))
    }

    @Test fun masksPixLabelsAndUnformattedStructuredNumbers() {
        val result = DiagnosticRedactor.suggest("Chave Pix: 52998224725; outro identificador 11222333000181")
        assertEquals("Chave Pix: █; outro identificador █", result)
        assertTrue(DiagnosticRedactor.validateReviewed(result))
        assertFalse(DiagnosticRedactor.validateReviewed("Chave Pix: 52998224725"))
    }

    @Test fun preservesUnicodeNewlinesAndTabsForManualReview() {
        val text = "Resumo fictício ☕️ — loja de teste\nvalor R$ 42,00\tobservação"
        val result = DiagnosticRedactor.suggest(text)
        assertTrue(result.startsWith("Resumo fictício ☕️ — loja de teste\n"))
        assertTrue(result.endsWith("\tobservação"))
        assertTrue(result.contains("R$ 12,34"))
        assertTrue(DiagnosticRedactor.validateReviewed(result))
    }

    @Test fun reviewedTextRejectsSensitiveRemaindersButAllowsSyntheticAmount() {
        assertTrue(DiagnosticRedactor.validateReviewed("Exemplo fictício: R$ 12,34; texto revisado."))
        for (text in listOf(
            "contato teste@example.invalid", "acesse https://example.invalid", "portal example.invalid", "id 123e4567-e89b-12d3-a456-426614174000",
            "cpf 529.982.247-25", "cnpj 11.222.333/0001-81", "tel (11) 91234-5678", "data 2026-10-25",
            "conta 1234567890123456", "sequência 123,456,789", "valor R$ 9.876,54", "valor BRL 99,90"
        )) assertFalse(text, DiagnosticRedactor.validateReviewed(text))
    }

    @Test fun enforcesInputControlsAndOutputBounds() {
        assertEquals(DiagnosticRedactor.MAX_CHARS, DiagnosticRedactor.suggest("x".repeat(DiagnosticRedactor.MAX_CHARS)).length)
        assertThrows(IllegalArgumentException::class.java) { DiagnosticRedactor.suggest("x".repeat(DiagnosticRedactor.MAX_CHARS + 1)) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticRedactor.suggest("linha\rquebra") }
        assertFalse(DiagnosticRedactor.validateReviewed("tab\u0000control"))
        val boundary = "x".repeat(DiagnosticRedactor.MAX_CHARS - 3) + "R$1"
        assertThrows(IllegalArgumentException::class.java) { DiagnosticRedactor.suggest(boundary) }
        assertTrue(DiagnosticRedactor.suggest("R$1").length <= DiagnosticRedactor.MAX_CHARS)
    }
}
