package org.ffortuna.capture

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

class StatementFailure(val code: String) : RuntimeException(code)
data class StatementRow(
    val date: String,
    val signedCents: Long,
    val description: String,
    val externalId: String?,
    val amountText: String = "",
    val dateText: String = ""
)
data class StatementDocument(val format: String, val accountScope: String?, val rows: List<StatementRow>)

/** Small, bounded parser for bank statement formats. Input is never retained by this object. */
object StatementParser {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ROWS = 2000
    private const val MAX_CENTS = 9007199254740991L
    private const val MAX_NODE_TEXT = 4096

    fun parse(bytes: ByteArray): StatementDocument {
        if (bytes.size > MAX_BYTES || bytes.isEmpty()) fail()
        val text = decodeUtf8(bytes).removePrefix("\uFEFF").trimStart()
        if (text.isEmpty()) fail()
        return if (text.startsWith("<") || Regex("^OFXHEADER\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(text)) parseOfx(text) else parseCsv(text)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { fail() }

    private data class Node(val name: String, val children: MutableList<Node> = mutableListOf(), val value: StringBuilder = StringBuilder()) {
        fun appendText(text: String) {
            if (value.length + text.length > StatementParser.MAX_NODE_TEXT) StatementParser.fail()
            value.append(text)
        }
        fun descendants(tag: String): List<Node> = buildList {
            for (child in children) {
                if (child.name == tag) add(child)
                addAll(child.descendants(tag))
            }
        }
        fun child(tag: String): String? = children.firstOrNull { it.name == tag }?.value?.toString()?.trim()
    }

    private val containers = setOf(
        "OFX", "SIGNONMSGSRSV1", "SONRS", "STATUS", "BANKMSGSRSV1", "STMTTRNRS", "STMTRS",
        "BANKACCTFROM", "BANKTRANLIST", "STMTTRN", "LEDGERBAL", "AVAILBAL", "CCSTMTTRNRS", "CCSTMTRS"
    )
    private val tagPattern = Regex("<(/?)([A-Za-z][A-Za-z0-9:_-]*)([^>]*)>")

    private fun parseOfx(source: String): StatementDocument {
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(source)) fail()
        val xml = Regex("^\\s*<\\?xml\\b", RegexOption.IGNORE_CASE).containsMatchIn(source)
        if (xml) {
            if (Regex("<\\?(?!xml\\b)", RegexOption.IGNORE_CASE).containsMatchIn(source)) fail()
        }
        val body = source.replace(Regex("<\\?xml\\b[^?]*\\?>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        val root = Node("#ROOT")
        val stack = mutableListOf(root)
        var pos = 0
        var rootCount = 0
        var nodeCount = 0
        for (match in tagPattern.findAll(body)) {
            val between = body.substring(pos, match.range.first)
            if (between.contains('<') || between.contains('>')) fail()
            if (stack.size > 1) stack.last().appendText(decodeXmlText(between))
            else if (between.isNotBlank() && !between.startsWith("OFXHEADER:", true) && !between.startsWith("DATA:", true) && !between.startsWith("VERSION:", true) && !between.startsWith("SECURITY:", true) && !between.startsWith("ENCODING:", true) && !between.startsWith("CHARSET:", true) && !between.startsWith("COMPRESSION:", true) && !between.startsWith("OLDFILEUID:", true) && !between.startsWith("NEWFILEUID:", true) && !between.isBlank()) fail()
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2].substringAfterLast(':').uppercase(Locale.ROOT)
            val tail = match.groupValues[3].trim()
            if (tail.isNotEmpty() && !tail.matches(Regex("(?:[A-Za-z_:][A-Za-z0-9:_.-]*\\s*=\\s*(?:\"[^\"]*\"|'[^']*')\\s*)*/?"))) fail()
            if (closing) {
                if (tail.isNotEmpty() || stack.size <= 1 || stack.last().name != name) fail()
                stack.removeAt(stack.lastIndex)
            } else {
                if(++nodeCount>40_000) fail()
                val node = Node(name)
                stack.last().children.add(node)
                if (stack.size == 1) rootCount++
                val selfClose = tail.endsWith("/")
                if (xml || selfClose || name in containers) {
                    if (!selfClose) {
                        if (stack.size >= 32) fail()
                        stack.add(node)
                    }
                } else {
                    // OFX 1.x scalar tags end at the next tag rather than carrying an end tag.
                    val next = tagPattern.find(body, match.range.last + 1)
                    val end = next?.range?.first ?: body.length
                    node.appendText(decodeXmlText(body.substring(match.range.last + 1, end)))
                    pos = end
                    continue
                }
            }
            pos = match.range.last + 1
        }
        val rest = body.substring(pos)
        if (rest.contains('<') || rest.contains('>')) fail()
        if (stack.size != 1 || rootCount != 1) fail()
        val ofx = root.children.singleOrNull()?.takeIf { it.name == "OFX" } ?: fail()
        if (ofx.descendants("CREDITCARD").isNotEmpty() || ofx.descendants("CCSTMTRS").isNotEmpty() || ofx.descendants("CCSTMTTRNRS").isNotEmpty()) fail()
        if (ofx.descendants("STMTRS").size != 1) fail()
        val currencies = ofx.descendants("CURDEF")
        if (currencies.isEmpty() || currencies.any { it.value.toString().trim().uppercase(Locale.ROOT) != "BRL" }) fail()
        val lists = ofx.descendants("BANKTRANLIST")
        if (lists.size != 1) fail()
        val transactions = lists.single().children.filter { it.name == "STMTTRN" }
        if (transactions.isEmpty() || transactions.size > MAX_ROWS) fail()
        val rows = transactions.map { tx ->
            val rawDate = tx.child("DTPOSTED") ?: fail()
            val rawAmount = tx.child("TRNAMT") ?: fail()
            val date = ofxDate(rawDate)
            val amount = ofxAmount(rawAmount)
            val id = cleanText(tx.child("FITID") ?: fail(), 500).takeIf { it.isNotEmpty() } ?: fail()
            val name = tx.child("NAME")?.trim().orEmpty()
            val memo = tx.child("MEMO")?.trim().orEmpty()
            val description = listOf(name, memo).filter { it.isNotEmpty() }.joinToString(" — ")
            StatementRow(date, amount, cleanText(description, 500), id, rawAmount, rawDate)
        }
        rejectConflictingIds(rows)
        val accounts = ofx.descendants("BANKACCTFROM")
        if (accounts.size > 1) fail()
        val acct = accounts.singleOrNull()
        val bankId = acct?.child("BANKID")
        val acctId = acct?.child("ACCTID")
        val scope = if (bankId != null && acctId != null) sha256(bankId + "\u001f" + acctId) else null
        return StatementDocument("OFX", scope, rows)
    }

    private fun decodeXmlText(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] != '&') { out.append(text[i++]); continue }
            val end = text.indexOf(';', i + 1)
            if (end < 0 || end - i > 12) fail()
            val entity = text.substring(i + 1, end)
            val decoded = when (entity) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> if (entity.startsWith("#x", true)) entity.drop(2).toIntOrNull(16)?.let(::xmlCodePoint)
                    else if (entity.startsWith("#")) entity.drop(1).toIntOrNull()?.let(::xmlCodePoint) else null
            } ?: fail()
            out.append(decoded); i = end + 1
        }
        return out.toString()
    }

    private fun ofxDate(raw: String): String {
        val digits = raw.take(8)
        if (!digits.matches(Regex("[0-9]{8}"))) fail()
        return date("${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}")
    }

    private fun date(value: String): String {
        try {
            val normalized = if (value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) value
            else if (value.matches(Regex("[0-9]{2}/[0-9]{2}/[0-9]{4}"))) "${value.substring(6)}-${value.substring(3, 5)}-${value.substring(0, 2)}"
            else fail()
            val parsed = LocalDate.parse(normalized, DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT))
            if (parsed.year < 1 || parsed.toString() != normalized) fail()
            return normalized
        } catch (_: StatementFailure) { fail() } catch (_: Exception) { fail() }
    }

    private fun ofxAmount(raw: String): Long {
        val m = Regex("^([+-]?)([0-9]+)(?:\\.([0-9]{1,2}))?$").matchEntire(raw.trim()) ?: fail()
        return cents(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    private fun parseCsv(source: String): StatementDocument {
        val delimiter = detectDelimiter(source)
        val records = csvRecords(source, delimiter)
        val header = records.firstOrNull()?.map { normalizeHeader(it) } ?: fail()
        fun find(vararg aliases: String): Int? {
            val matches = header.indices.filter { header[it] in aliases }
            if (matches.size > 1) fail()
            return matches.singleOrNull()
        }
        val dateCol = find("date", "data") ?: fail()
        val amountCol = find("amount", "valor") ?: fail()
        val descCol = find("description", "descricao", "historico") ?: fail()
        val idCol = find("id", "identificador", "fitid")
        if (records.size - 1 > MAX_ROWS) fail()
        val rows = records.drop(1).filterNot { it.all(String::isBlank) }.map { row ->
            if (row.size != header.size) fail()
            val id = idCol?.let { cleanText(row[it].trim(), 500).takeIf(String::isNotEmpty) }
            StatementRow(
                date(row[dateCol].trim()), csvAmount(row[amountCol]), cleanText(row[descCol].trim(), 500), id,
                row[amountCol], row[dateCol]
            )
        }
        if (rows.isEmpty() || rows.size > MAX_ROWS) fail()
        rejectConflictingIds(rows)
        return StatementDocument("CSV", null, rows)
    }

    private fun normalizeHeader(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]"), "")

    private fun detectDelimiter(source: String): Char {
        var quoted = false; var commas = 0; var semis = 0; var i = 0
        while (i < source.length && source[i] != '\n' && source[i] != '\r') {
            when (source[i]) {
                '"' -> if (quoted && i + 1 < source.length && source[i + 1] == '"') i++ else quoted = !quoted
                ',' -> if (!quoted) commas++
                ';' -> if (!quoted) semis++
            }
            i++
        }
        if (quoted || commas == semis) fail()
        return if (commas > semis) ',' else ';'
    }

    private fun csvRecords(source: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var afterQuote = false; var i = 0
        while (i < source.length) {
            val c = source[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < source.length && source[i + 1] == '"') { field.append('"'); i++ }
                    else { quoted = false; afterQuote = true }
                } else field.append(c)
            } else when {
                afterQuote && c == delimiter -> { row.add(field.toString()); field.setLength(0); afterQuote = false }
                afterQuote && c == '\r' -> { row.add(field.toString()); field.setLength(0); records.add(row); row = mutableListOf(); afterQuote = false; if (i + 1 < source.length && source[i + 1] == '\n') i++ }
                afterQuote && c == '\n' -> { row.add(field.toString()); field.setLength(0); records.add(row); row = mutableListOf(); afterQuote = false }
                afterQuote && c.isWhitespace() -> Unit
                afterQuote -> fail()
                c == '"' -> { if (field.isNotEmpty()) fail(); quoted = true }
                c == delimiter -> { row.add(field.toString()); field.setLength(0) }
                c == '\r' -> { row.add(field.toString()); field.setLength(0); records.add(row); row = mutableListOf(); if (i + 1 < source.length && source[i + 1] == '\n') i++ }
                c == '\n' -> { row.add(field.toString()); field.setLength(0); records.add(row); row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (quoted) fail()
        if (field.isNotEmpty() || row.isNotEmpty() || afterQuote) { row.add(field.toString()); records.add(row) }
        if (records.isEmpty()) fail()
        return records
    }

    private fun csvAmount(raw: String): Long {
        if(raw.length>64) fail()
        var value = raw.trim().replace("\u00a0", " ")
        if (value.startsWith("R$", true)) value = value.drop(2).trim()
        val m = Regex("^([+-]?)(.*)$").matchEntire(value) ?: fail()
        val sign = m.groupValues[1]; val number = m.groupValues[2]
        val localized = Regex("([0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]+),([0-9]{1,2})").matchEntire(number)
        if (localized != null) return cents(sign, localized.groupValues[1].replace(".", ""), localized.groupValues[2])
        val dot = Regex("([0-9]+)\\.([0-9]{1,2})").matchEntire(number)
        if (dot != null) return cents(sign, dot.groupValues[1], dot.groupValues[2])
        if (number.matches(Regex("[0-9]+"))) return cents(sign, number, "")
        fail()
    }

    private fun cents(sign: String, whole: String, fraction: String): Long {
        if(whole.length>16 || fraction.length>2) fail()
        if (whole.length > 16) fail()
        val exact = BigInteger(whole).multiply(BigInteger.valueOf(100)) + BigInteger((fraction + "00").take(2))
        if (exact > BigInteger.valueOf(MAX_CENTS)) fail()
        val signed = if (sign == "-") exact.negate() else exact
        return signed.toLong()
    }

    private fun cleanText(value: String, max: Int): String {
        if (value.length > max || value.any { Character.isISOControl(it) && it !in setOf('\n', '\r', '\t') }) fail()
        return value
    }

    private fun rejectConflictingIds(rows: List<StatementRow>) {
        val seen = mutableMapOf<String, StatementRow>()
        for (row in rows) {
            val id = row.externalId ?: continue
            val old = seen.putIfAbsent(id, row)
            if (old != null && old != row) fail()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun xmlCodePoint(value: Int): String? = try {
        if (value !in 1..0x10FFFF || value in 0xD800..0xDFFF) null else String(Character.toChars(value))
    } catch (_: Exception) { null }

    private fun fail(): Nothing = throw StatementFailure("INVALID_STATEMENT")
}
