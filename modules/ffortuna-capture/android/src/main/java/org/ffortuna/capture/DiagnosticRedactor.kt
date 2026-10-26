package org.ffortuna.capture

/** Suggests deterministic redactions; it does not anonymize text or identify names/places. */
object DiagnosticRedactor {
    const val MAX_CHARS = 16 * 1024
    private const val MASK = "█"
    private const val SYNTHETIC_BRL = "R$ 12,34"

    private val sensitive = Regex(
        """(?<money>(?:R\$|BRL)\s*[-+]?(?:[0-9]{1,3}(?:\.[0-9]{3})+|[0-9]+)(?:,[0-9]{1,2})?)""" +
            """|(?<pix>(?<pixprefix>\b(?:chave\s+pix|pix\s+key)\s*[:=]\s*)(?<pixvalue>[A-Z0-9._%+-]{3,}@[A-Z0-9.-]+\.[A-Z]{2,}|\+?[0-9][0-9 ()./-]{6,}[0-9]|[A-F0-9-]{16,}))""" +
            """|(?<url>\b(?:(?:https?|ftp)://[^\s<>]+|www\.[^\s<>]+|[A-Z0-9-]+(?:\.[A-Z0-9-]+)*\.[A-Z]{2,}(?:/[^\s<>]*)?))""" +
            """|(?<email>\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b)""" +
            """|(?<uuid>\b[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\b)""" +
            """|(?<cnpj>(?<![0-9])(?:[0-9]{2}\.?[0-9]{3}\.?[0-9]{3}/?[0-9]{4}-?[0-9]{2})(?![0-9]))""" +
            """|(?<cpf>(?<![0-9])(?:[0-9]{3}\.?[0-9]{3}\.?[0-9]{3}-?[0-9]{2})(?![0-9]))""" +
            """|(?<date>(?<![0-9])(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{4}/[0-9]{2}/[0-9]{2}|[0-9]{2}[./-][0-9]{2}[./-][0-9]{4})(?![0-9]))""" +
            """|(?<phone>(?<![0-9])(?:\+?55[ ().-]*)?(?:\(?[0-9]{2}\)?[ .-]*)?(?:9?[0-9]{4})[ .-]?[0-9]{4}(?![0-9]))""" +
            """|(?<digits>(?<![0-9])[0-9](?:[ ,\.()/-]*[0-9]){7,}(?![0-9]))""",
        RegexOption.IGNORE_CASE
    )

    /** Returns a review aid only. Names, shops, locations, and free-form details need manual review. */
    fun suggest(text: String): String {
        requireValidInput(text)
        val result = sensitive.replace(text) { match ->
            when {
                match.groups["money"] != null -> SYNTHETIC_BRL
                match.groups["pix"] != null -> match.groups["pixprefix"]!!.value + MASK
                else -> MASK
            }
        }
        if (result.length > MAX_CHARS) throw IllegalArgumentException("OUTPUT_TOO_LARGE")
        return result
    }

    /** True only when common structured identifiers and control characters are absent. */
    fun validateReviewed(text: String): Boolean {
        if (!validLengthAndControls(text)) return false
        return sensitive.findAll(text).all { match ->
            val money = match.groups["money"]?.value
            money != null && money.replace(" ", "").equals("R$12,34", ignoreCase = true)
        }
    }

    private fun requireValidInput(text: String) {
        if (!validLengthAndControls(text)) throw IllegalArgumentException("INVALID_DIAGNOSTIC_TEXT")
    }

    private fun validLengthAndControls(text: String): Boolean =
        text.length <= MAX_CHARS && text.none { Character.isISOControl(it) && it != '\n' && it != '\t' }
}
