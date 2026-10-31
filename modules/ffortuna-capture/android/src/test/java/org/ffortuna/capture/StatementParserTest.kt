package org.ffortuna.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets

class StatementParserTest {
    private fun parse(text: String) = StatementParser.parse(text.toByteArray(StandardCharsets.UTF_8))

    @Test fun standardOfxOneHeaderIsDispatchedAsOfx() {
        val source = """OFXHEADER:100
DATA:OFXSGML
VERSION:102
<OFX><SONRS><CURDEF>BRL</SONRS><STMTRS><BANKTRANLIST><STMTTRN><DTPOSTED>20240101<TRNAMT>0<FITID>one</STMTTRN></BANKTRANLIST></STMTRS></OFX>"""
        assertEquals("OFX", parse(source).format)
    }

    @Test fun csvAcceptsReorderedHeadersQuotedNewlinesAndKeepsEqualPurchases() {
        val document = parse("""Historico;ID;Valor;Data
"Compra; café
loja";x-1;-1.234,56;29/02/2024
"Compra; café
loja";x-2;-1.234,56;29/02/2024
""")
        assertEquals("CSV", document.format)
        assertNull(document.accountScope)
        assertEquals(2, document.rows.size)
        assertEquals(-123456L, document.rows[0].signedCents)
        assertEquals("2024-02-29", document.rows[0].date)
        assertEquals("29/02/2024", document.rows[0].dateText)
        assertEquals("-1.234,56", document.rows[0].amountText)
        assertEquals("Compra; café\nloja", document.rows[0].description)
    }

    @Test fun csvAmountsAreExactAndSignsAndLimitsAreRespected() {
        val rows = listOf("-1,25", "+0.99", "90071992547409.91")
        val csv = "data,valor,descricao\n2024-01-01,\"${rows[0]}\",a\n2024-01-02,${rows[1]},b\n2024-01-03,${rows[2]},c"
        val parsed = parse(csv)
        assertEquals(listOf(-125L, 99L, 9007199254740991L), parsed.rows.map { it.signedCents })
        for (amount in listOf("1,234", "1.234", "90071992547409.92", "--1,00", "1,001")) {
            assertThrows(StatementFailure::class.java) {
                parse("data,amount,description\n2024-01-01,\"$amount\",item")
            }
        }
    }

    @Test fun csvDatesAndMalformedRowsFailAsOneDocument() {
        assertEquals("2024-02-29", parse("data;valor;descricao\n29/02/2024;1,00;x").rows.single().date)
        for (date in listOf("29/02/2023", "2024-02-30", "0000-01-01", "1/2/2024")) {
            assertThrows(StatementFailure::class.java) { parse("date,amount,description\n$date,1.00,x") }
        }
        assertThrows(StatementFailure::class.java) {
            parse("date,amount,description\n2024-01-01,1.00,ok\n2024-02-30,2.00,bad")
        }
    }

    @Test fun ofxSgmlUsesSignedAmountsAndHashesAccountScope() {
        val document = parse("""OFXHEADER:100
DATA:OFXSGML
VERSION:102
<OFX><SIGNONMSGSRSV1><SONRS><STATUS><CODE>0<SEVERITY>INFO</STATUS><DTSERVER>20241030120000</SONRS></SIGNONMSGSRSV1><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL<BANKACCTFROM><BANKID>001<ACCTID>123</BANKACCTFROM><BANKTRANLIST><STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20240229110000[-3:BRT]<TRNAMT>-12.34<FITID>fit-1<NAME>Mercado<MEMO>Compra</STMTTRN></BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>""")
        val row = document.rows.single()
        assertEquals("OFX", document.format)
        assertEquals("2024-02-29", row.date)
        assertEquals("20240229110000[-3:BRT]", row.dateText)
        assertEquals("-12.34", row.amountText)
        assertEquals(-1234L, row.signedCents)
        assertEquals("Mercado — Compra", row.description)
        assertEquals("fit-1", row.externalId)
        assertEquals(64, document.accountScope?.length)
    }

    @Test fun ofxXmlCurrencyAndSecurityRulesAreEnforced() {
        val xml = """<?xml version="1.0"?><OFX><SIGNONMSGSRSV1><SONRS><STATUS><CODE>0</CODE></STATUS></SONRS></SIGNONMSGSRSV1><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL</CURDEF><BANKACCTFROM><BANKID>01</BANKID><ACCTID>02</ACCTID></BANKACCTFROM><BANKTRANLIST><STMTTRN><DTPOSTED>20240101</DTPOSTED><TRNAMT>1.2</TRNAMT><FITID>x</FITID><NAME>A &amp; B</NAME></STMTTRN></BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>"""
        assertEquals("A & B", parse(xml).rows.single().description)
        for (bad in listOf(
            xml.replace("BRL", "USD"),
            xml.replace("<CURDEF>BRL</CURDEF>", "<CURDEF>USD</CURDEF>"),
            xml.replace("</OFX>", "<!DOCTYPE OFX [<!ENTITY x SYSTEM 'file:///etc/passwd'>]></OFX>"),
            xml.replace("<FITID>x</FITID>", "<FITID></FITID>")
        )) assertThrows(StatementFailure::class.java) { parse(bad) }
        val duplicateAccount = xml.replace("</BANKACCTFROM>", "</BANKACCTFROM><BANKACCTFROM><BANKID>03</BANKID><ACCTID>04</ACCTID></BANKACCTFROM>")
        assertThrows(StatementFailure::class.java) { parse(duplicateAccount) }
        val credit = xml.replace("<STMTRS>", "<CCSTMTRS>").replace("</STMTRS>", "</CCSTMTRS>")
        assertThrows(StatementFailure::class.java) { parse(credit) }
        val tooDeep = xml.replace("<OFX>", "<OFX>${"<A>".repeat(32)}")
            .replace("</OFX>", "</A>".repeat(32) + "</OFX>")
        assertThrows(StatementFailure::class.java) { parse(tooDeep) }
    }

    @Test fun conflictingIdsInvalidUtf8AndResourceLimitsFail() {
        assertThrows(StatementFailure::class.java) { parse("date,amount,description,id\n2024-01-01,1.00,a,x\n2024-01-02,2.00,b,x") }
        assertThrows(StatementFailure::class.java) { StatementParser.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(StatementFailure::class.java) { StatementParser.parse(ByteArray(StatementParser.MAX_BYTES + 1)) }
        val tooMany = buildString { append("date,amount,description\n"); repeat(StatementParser.MAX_ROWS + 1) { append("2024-01-01,1.00,x\n") } }
        assertThrows(StatementFailure::class.java) { parse(tooMany) }
    }
}
