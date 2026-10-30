package org.ffortuna.capture

import java.math.BigInteger

data class MoneyEvidence(
    val field: String,
    val start: Int,
    val end: Int,
    val text: String,
    val amountCents: Long?
)

data class BrlParse(
    val amountCents: Long?,
    val amountConfidence: String,
    val evidence: List<MoneyEvidence>,
    val reasonCode: String
)

/** Conservative amount-only scanner. It does not infer transaction type, account, or date. */
object BrlNotificationParser {
    const val VERSION = "notification-brl/1"
    const val MAX_CENTS = 9007199254740991L
    private const val MAX_TOTAL_CHARS = 16 * 1024
    private const val MAX_FIELD_CHARS = 4096
    private const val MAX_EVIDENCE = 64
    private val marker = Regex("(?i)R\\$|\\bBRL\\b")
    private val amountFormat = Regex("(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+),[0-9]{2}")

    fun parse(fields: List<Pair<String, String>>, truncated: Boolean = false, groupSummary: Boolean = false): BrlParse {
        val total = fields.sumOf { it.second.length.toLong() }
        if (total > MAX_TOTAL_CHARS || fields.any { it.second.length > MAX_FIELD_CHARS })
            return result("INPUT_LIMIT")

        val found = mutableListOf<MoneyEvidence>()
        var evidenceLimit = false
        fieldLoop@ for ((field, text) in fields) {
            for (match in marker.findAll(text)) {
                if (found.size >= MAX_EVIDENCE) {
                    evidenceLimit = true
                    break@fieldLoop
                }
                var start = match.range.first
                var prefix = start - 1
                while (prefix >= 0 && text[prefix].isMoneySpace()) prefix--
                if (prefix >= 0 && text[prefix] in charArrayOf('-', '+', '\u2212')) start = prefix
                var end = match.range.last + 1
                while (end < text.length && text[end].isMoneySpace()) end++
                val numberStart = end
                if (end < text.length && (text[end] == '+' || text[end] == '-')) end++
                while (end < text.length) {
                    val c = text[end]
                    when {
                        c.isDigit() || c == ',' || c == '.' -> end++
                        c.isLetter() -> end++ // Keep attached suffixes so malformed prefixes cannot pass.
                        // A final sentence period is not part of the amount; internal punctuation is.
                        c == '.' && end + 1 < text.length && text[end + 1].isLetter() -> end++
                        else -> break
                    }
                }
                if (end > numberStart) {
                    // Leave one trailing sentence period outside the evidence span.
                    if (end > numberStart && text[end - 1] == '.' && (end == text.length || text[end].isWhitespace())) end--
                    if (end > start) {
                        found += evidence(field, text, start, end)
                    }
                }
            }
        }

        if (evidenceLimit) return BrlParse(null, "missing", found, "EVIDENCE_LIMIT")
        if (groupSummary) return BrlParse(null, "missing", found, "GROUP_SUMMARY")
        if (truncated) return BrlParse(null, "missing", found, "TRUNCATED")
        if (found.isEmpty()) return result("NO_AMOUNT")
        if (found.any { it.amountCents == null }) return BrlParse(null, "uncertain", found, "INVALID_AMOUNT")

        // The same literal repeated across distinct fields is often the same expanded notification value.
        // Repetition within one field remains ambiguous, even when the literal is identical.
        val sameFieldRepeat = found.groupBy { it.field to it.text }.values.any { it.size > 1 }
        val distinctLiterals = found.map { it.text }.distinct()
        val distinctAmounts = found.mapNotNull { it.amountCents }.distinct()
        if (sameFieldRepeat || distinctLiterals.size > 1 || distinctAmounts.size > 1)
            return BrlParse(null, "uncertain", found, "AMBIGUOUS_AMOUNT")
        return BrlParse(distinctAmounts.single(), "certain", found, "AMOUNT_FOUND")
    }

    private fun evidence(field: String, text: String, start: Int, end: Int): MoneyEvidence {
        val literal = text.substring(start, end)
        val markerMatch = marker.find(literal)
        val amountText = if (markerMatch != null && markerMatch.range.first == 0) {
            val m = markerMatch
            literal.substring(m.range.last + 1).trim { it.isMoneySpace() }
        } else literal
        val cents = parseCents(amountText)
        return MoneyEvidence(field, start, end, literal, cents)
    }

    private fun parseCents(raw: String): Long? {
        val normalized = raw.trim()
        if (!amountFormat.matches(normalized)) return null
        val digits = normalized.replace(".", "")
        val split = digits.indexOf(',')
        if (split <= 0 || split != digits.length - 3) return null
        return try {
            val cents = BigInteger(digits.substring(0, split) + digits.substring(split + 1))
            if (cents.signum() <= 0 || cents > BigInteger.valueOf(MAX_CENTS)) null else cents.toLong()
        } catch (_: NumberFormatException) { null }
    }

    private fun Char.isMoneySpace() = this == ' ' || this == '\t' || this == '\u00a0' || this == '\u202f'
    private fun result(reason: String) = BrlParse(null, "missing", emptyList(), reason)
}
