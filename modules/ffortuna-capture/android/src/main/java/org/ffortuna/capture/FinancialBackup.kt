package org.ffortuna.capture

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Distinct from synthetic backups. No diagnostic raw payload, key, path or credential. */
object FinancialBackup {
    private val columns=mapOf(
        "classifications" to listOf("id","kind","name","name_key","archived"),
        "finance_profile" to listOf("id","opening_date","opening_cents"),
        "finance_transactions" to listOf("id","command_key","request_json","kind","amount","effective_date","description","category_id","category_legacy","created_at","source_event_id","source_json"),
        "finance_entries" to listOf("transaction_id","resource","cents"),
        "finance_tags" to listOf("transaction_id","tag_id"),
        "finance_balance_checks" to listOf("id","effective_date","amount","created_at"),
        "finance_cancellations" to listOf("transaction_id","reason","cancelled_at"))
    private val integers=setOf("archived","opening_cents","amount","cents")
    private val nullable=setOf("category_id","source_event_id","source_json")
    fun encode(ledger:FinancialLedger):ByteArray {
        val data=FinancialLedger.tables.associateWith { ledger.rows(it) }
        val plain=JSONObject(linkedMapOf("format" to "tique.financial-book","version" to 1,"rawPayloadsIncluded" to false,"data" to data)).toString().toByteArray(Charsets.UTF_8)
        if(plain.size>BackupCodec.MAX_PLAINTEXT) { plain.fill(0);throw FinanceFailure("TRANSACTION_LIMIT") }
        return plain
    }
    fun decode(plain:ByteArray):Map<String,List<Map<String,Any?>>> {
        try {
            if(plain.size>BackupCodec.MAX_PLAINTEXT) throw BackupFailure(BackupProblem.TOO_LARGE)
            val root=JSONObject(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain)).toString())
            require(root.keys().asSequence().toSet()==setOf("format","version","rawPayloadsIncluded","data"))
            require(root.getString("format")=="tique.financial-book" && root.get("version")==1 && root.get("rawPayloadsIncluded")==false)
            val data=root.getJSONObject("data");require(data.keys().asSequence().toSet()==columns.keys)
            return columns.mapValues { (table,names) ->
                val rows=data.getJSONArray(table)
                val limit=when(table) { "classifications" -> 200;"finance_profile" -> 1;"finance_transactions","finance_cancellations" -> 10_000;"finance_entries" -> 20_000;"finance_tags" -> 200_000;else -> 1000 }
                require(rows.length()<=limit)
                (0 until rows.length()).map { index ->
                    val row=rows.getJSONObject(index);require(row.keys().asSequence().toSet()==names.toSet())
                    names.associateWith { key ->
                        val value=row.get(key)
                        if(value===JSONObject.NULL) { require(key in nullable);null }
                        else if(key in integers || (table=="finance_profile" && key=="id")) {
                            require(value is Int || value is Long);(value as Number).toLong()
                        } else {
                            require(value is String && value.length<=8192)
                            if(key in setOf("id","transaction_id","tag_id","category_id","source_event_id") && !(table=="finance_profile" && key=="id")) require(UUID.fromString(value).toString()==value)
                            value
                        }
                    }
                }
            }
        } catch(error:BackupFailure) { throw error }
        catch (_:Exception) { throw BackupFailure(BackupProblem.INVALID_DATA) }
    }
    fun info(plain:ByteArray):RecoveryPayloadInfo {
        val data=decode(plain)
        return RecoveryPayloadInfo(maxOf(1,data.getValue("finance_transactions").size),MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) })
    }
    fun restore(db:net.zetetic.database.sqlcipher.SQLiteDatabase,plain:ByteArray) {
        val data=decode(plain)
        db.beginTransaction()
        try {
            for(table in FinancialLedger.tables.reversed()) db.execSQL("DELETE FROM $table")
            for((table,names) in columns) for(row in data.getValue(table)) {
                val parameters=names.joinToString(",") { "?" }
                db.execSQL("INSERT INTO $table (${names.joinToString(",")}) VALUES($parameters)",names.map { row[it] }.toTypedArray())
            }
            db.rawQuery("PRAGMA foreign_key_check",null).use { if(it.moveToFirst()) throw FinanceFailure("INVALID_STATE") }
            FinancialLedger(db).validate()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
