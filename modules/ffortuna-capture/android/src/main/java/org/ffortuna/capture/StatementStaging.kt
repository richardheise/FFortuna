package org.ffortuna.capture

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** A statement is staged as evidence, never posted based only on a signed amount. */
class StatementStaging(private val db:SQLiteDatabase) {
    companion object {
        fun createSchema(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE statement_batches(digest TEXT PRIMARY KEY,format TEXT NOT NULL,captured_at TEXT NOT NULL,row_count INTEGER NOT NULL)")
        }
        fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    fun batches():List<Map<String,Any?>> = db.rawQuery("SELECT digest,format,captured_at,row_count FROM statement_batches ORDER BY captured_at DESC LIMIT 30",null).use { rows ->
        buildList { while(rows.moveToNext()) add(mapOf("id" to rows.getString(0),"format" to rows.getString(1),"capturedAt" to rows.getString(2),"count" to rows.getInt(3))) }
    }
    fun stage(document:StatementDocument,fileDigest:String,now:Instant):Map<String,Any?> {
        require(fileDigest.matches(Regex("[a-f0-9]{64}")))
        val prior=db.rawQuery("SELECT row_count FROM statement_batches WHERE digest=?",arrayOf(fileDigest)).use { if(it.moveToFirst()) it.getInt(0) else null }
        if(prior!=null) return mapOf("status" to "ok","count" to 0,"existing" to prior)
        val total=db.rawQuery("SELECT COUNT(*) FROM samples WHERE mode!='diagnostic'",null).use { it.moveToFirst();it.getInt(0) }
        var added=0
        db.beginTransaction()
        try {
            document.rows.forEachIndexed { index,row ->
                val identity=if(document.accountScope!=null && row.externalId!=null) "${document.accountScope}:${row.externalId}" else "$fileDigest:$index"
                val key="statement:"+digest(identity.toByteArray())
                val canonical="${row.date}\u0000${row.signedCents}\u0000${row.description}"
                val fingerprint=digest(canonical.toByteArray())
                val exists=db.rawQuery("SELECT id FROM samples WHERE source_key=? AND fingerprint=?",arrayOf(key,fingerprint)).use { it.moveToFirst() }
                if(!exists) {
                    if(total+added>=DailyCapturePolicy.MAX_ROWS) throw DiagnosticFailure("SAMPLE_LIMIT")
                    val revision=db.rawQuery("SELECT COALESCE(MAX(revision),0)+1 FROM samples WHERE source_key=?",arrayOf(key)).use { it.moveToFirst();it.getInt(0) }
                    val id=UUID.nameUUIDFromBytes("tique:$key:$fingerprint".toByteArray()).toString()
                    val amount=kotlin.math.abs(row.signedCents)
                    val origin=mapOf("format" to document.format,"fileDigest" to fileDigest,"row" to index+1,"externalId" to row.externalId,"accountScope" to document.accountScope)
                    val fields=DiagnosticFields(title="Extrato ${document.format.uppercase()}",text=row.description,bigText="${row.dateText}\n${row.amountText}")
                    db.execSQL("INSERT INTO samples(id,session_id,source_key,revision,fingerprint,published_at,captured_at,is_summary,group_key,payload,truncated,mode,origin_json) VALUES(?,?,?,?,?,?,?,0,NULL,?,0,'import',?)",
                        arrayOf(id,"statement:$fileDigest",key,revision,fingerprint,now.toString(),now.toString(),fields.encode(),JSONObject(origin).toString()))
                    val evidence=mapOf("amountCents" to mapOf("value" to amount.toString(),"sourceField" to "statement.amount","observedText" to row.amountText,"start" to 0,"end" to row.amountText.length))
                    val draft=mapOf("amountCents" to amount,"kind" to (if(row.signedCents>0) "income" else "expense"),"effectiveDate" to row.date,"description" to row.description,"category" to "A classificar","categoryId" to null,"tagIds" to emptyList<String>())
                    val parsed=mapOf("parserVersion" to "statement-v1","draft" to draft,
                        "confidence" to mapOf("movement" to "certain","amount" to "certain","nature" to "uncertain","account" to "missing","date" to "certain","category" to "uncertain"),
                        "fieldEvidence" to evidence,"amountEvidence" to emptyList<Any>(),"reasonCode" to "STATEMENT_REVIEW",
                        "reason" to "Natureza sugerida pelo sinal do arquivo, não confirmada. Confira transferências, aportes e pagamentos de fatura.","automaticPostingAllowed" to false)
                    db.execSQL("INSERT INTO candidate_parses VALUES(?,?,?,?)",arrayOf(id,"statement-v1",JSONObject(parsed).toString(),now.toString()))
                    added++
                }
            }
            db.execSQL("INSERT INTO statement_batches VALUES(?,?,?,?)",arrayOf(fileDigest,document.format,now.toString(),document.rows.size))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return mapOf("status" to "ok","count" to added,"existing" to document.rows.size-added)
    }
}
