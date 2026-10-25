package org.ffortuna.capture

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.time.Clock
import java.util.UUID

/** Owned solely by the native serial executor. This proof contains no financial ledger. */
class CaptureStore(private val context: Context, private val name: String = "ffortuna-capture-poc",
    private val clock:Clock=Clock.systemUTC(), private val allowCreate:Boolean=true) : AutoCloseable {
    private val file = context.getDatabasePath("$name.db")
    private val vault = KeyVault(context, name)
    private val db: SQLiteDatabase
    val cipherVersion: String
    init {
        var stage = CaptureStorageStage.LIBRARY
        var opened: SQLiteDatabase? = null
        try {
            SqlCipherPolicy.initialize()
            stage = CaptureStorageStage.KEY
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Database directory unavailable" }
            if(!file.exists()) check(allowCreate && !vault.hasStoredMaterial()) { "MISSING_DATABASE_PRESERVED_KEY" }
            else check(file.isFile && file.length()>=16) { "INVALID_DATABASE_FILE" }
            val key = vault.load(file.exists())
            stage = CaptureStorageStage.OPEN
            try { db = SQLiteDatabase.openOrCreateDatabase(file, key, null, SqlCipherPolicy.failClosed, SqlCipherPolicy.hook); opened = db }
            finally { key.fill(0) }
            stage = CaptureStorageStage.SECURITY
            cipherVersion = db.rawQuery("PRAGMA cipher_version", null).use { check(it.moveToFirst()); it.getString(0) }
            check(cipherVersion.isNotBlank()) { "SQLCipher indisponível; captura bloqueada." }
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA cipher_memory_security=ON")
            // This setter returns a row; execSQL rejects SQLITE_ROW in the Android driver.
            db.rawQuery("PRAGMA secure_delete=ON", null).use { check(it.moveToFirst() && it.getInt(0) == 1) }
            db.execSQL("PRAGMA synchronous=FULL")
            stage = CaptureStorageStage.MIGRATION
            db.beginTransaction()
            try {
                check(db.version <= 2) { "Banco de versão futura; dados preservados." }
                if (db.version == 0) {
                    db.execSQL("""CREATE TABLE observations (
                        id TEXT PRIMARY KEY, source_key TEXT NOT NULL, revision INTEGER NOT NULL,
                        fingerprint TEXT NOT NULL, published_at TEXT NOT NULL, captured_at TEXT NOT NULL,
                        payload TEXT, state TEXT NOT NULL CHECK(state IN ('pending','processed')),
                        parser_version TEXT, parsed_json TEXT,
                        UNIQUE(source_key,fingerprint), UNIQUE(source_key,revision))""")
                    db.version = 1
                }
                if (db.version == 1) {
                    db.execSQL("ALTER TABLE observations ADD COLUMN source TEXT NOT NULL DEFAULT 'simulation'")
                    db.execSQL("ALTER TABLE observations ADD COLUMN source_package TEXT NOT NULL DEFAULT ''")
                    db.execSQL("UPDATE observations SET source_package=? WHERE source_package=''", arrayOf(context.packageName))
                    db.version = 2
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (error: Exception) {
            try { opened?.close() } catch (_: Exception) { }
            throw CaptureStorageFailure(stage, error)
        } catch (error: LinkageError) {
            try { opened?.close() } catch (_: Exception) { }
            throw CaptureStorageFailure(stage, error)
        }
    }

    /** First commit the observation, then do the recoverable parsing in another transaction. */
    fun observe(sourceKey: String, payload: String, publishedAt: Long, capturedAt: String = clock.instant().toString()): String {
        Instant.parse(capturedAt)
        expireRaw()
        val hash = CaptureIdentity.fingerprint(payload, publishedAt)
        db.beginTransaction()
        val id: String
        try {
            val prior = db.rawQuery("SELECT id FROM observations WHERE source_key=? AND fingerprint=?", arrayOf(sourceKey, hash)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (prior != null) id = prior
            else {
                val revision = db.rawQuery("SELECT COALESCE(MAX(revision),0)+1 FROM observations WHERE source_key=?", arrayOf(sourceKey)).use { it.moveToFirst(); it.getInt(0) }
                id = UUID.randomUUID().toString()
                db.execSQL("INSERT INTO observations (id,source_key,revision,fingerprint,published_at,captured_at,payload,state,source,source_package) VALUES (?,?,?,?,?,?,?,'pending','simulation',?)",
                    arrayOf(id, sourceKey, revision, hash, Instant.ofEpochMilli(publishedAt).toString(), capturedAt, payload, context.packageName))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }

    fun processPending(): Int {
        expireRaw()
        val pending = mutableListOf<Pair<String,String>>()
        db.rawQuery("SELECT id,payload FROM observations WHERE state='pending' AND payload IS NOT NULL ORDER BY captured_at", null).use { while(it.moveToNext()) pending.add(it.getString(0) to it.getString(1)) }
        for ((id, payload) in pending) {
            val original = JSONObject(payload)
            val rawAmount = original.opt("amountCents")
            val amount = if(rawAmount is Long || rawAmount is Int) (rawAmount as Number).toLong() else -1L
            val validAmount = amount in 1..9007199254740991L
            val date = original.optString("effectiveDate")
            val validDate = try { LocalDate.parse(date).toString() == date } catch (_: Exception) { false }
            val validNature = original.optString("kind") in listOf("income", "expense")
            val draft = JSONObject().put("amountCents", if(validAmount) amount else JSONObject.NULL)
                .put("kind", if(validNature) original.getString("kind") else JSONObject.NULL)
                .put("effectiveDate", if(validDate) date else JSONObject.NULL).put("effectiveAt", JSONObject.NULL)
                .put("description", original.optString("description")).put("category", original.optString("category", "A classificar"))
                .put("reserveId", JSONObject.NULL).put("direction", JSONObject.NULL)
            val confidence = JSONObject().put("movement","certain").put("amount",if(validAmount) "certain" else "missing")
                .put("nature",if(validNature) "certain" else "missing").put("account",if(original.optString("accountRef")=="demo-main") "certain" else "uncertain").put("date",if(validDate) "certain" else "missing").put("category","uncertain")
            val evidence = JSONObject()
            for(field in listOf("amountCents","kind","effectiveDate","description","category"))
                evidence.put(field,JSONObject().put("value",original.opt(field)?.toString() ?: "null").put("sourceField",field))
            val parsed = JSONObject().put("parserVersion", "native-synthetic/1").put("draft", draft).put("confidence",confidence)
                .put("fieldEvidence",evidence).put("externalId",original.getString("externalId"))
                .put("reason","Prova sintética: interpretação persistida, sem lançamento financeiro.")
            db.beginTransaction()
            try {
                db.execSQL("UPDATE observations SET state='processed',parser_version=?,parsed_json=? WHERE id=? AND state='pending'", arrayOf("native-synthetic/1", parsed.toString(),id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return pending.size
    }

    fun snapshot(): Map<String, Any?> {
        expireRaw()
        val rows = mutableListOf<Map<String,Any?>>()
        db.rawQuery("SELECT id,source_key,revision,published_at,captured_at,state,parser_version,parsed_json,payload,source,source_package FROM observations ORDER BY captured_at DESC,rowid DESC LIMIT 30", null).use {
            while(it.moveToNext()) rows.add(mapOf("id" to it.getString(0),"sourceKey" to it.getString(1),"revision" to it.getInt(2),"publishedAt" to it.getString(3),"capturedAt" to it.getString(4),"state" to it.getString(5),"parserVersion" to it.getString(6),"parsedJson" to it.getString(7),"payload" to it.getString(8),"source" to it.getString(9),"sourcePackage" to it.getString(10)))
        }
        val count=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN state='pending' AND payload IS NOT NULL THEN 1 ELSE 0 END),0) FROM observations",null).use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }
        val unavailablePending=db.rawQuery("SELECT COUNT(*) FROM observations WHERE state='pending' AND payload IS NULL",null).use { it.moveToFirst(); it.getInt(0) }
        return mapOf("unavailablePending" to unavailablePending,"cipherVersion" to cipherVersion,"schemaVersion" to db.version,"total" to count.first,"pending" to count.second,"observations" to rows)
    }
    fun exportBackup(): InboxBackup {
        expireRaw()
        val rows=mutableListOf<BackupObservation>()
        var characters=0L
        db.rawQuery("SELECT id,source_key,revision,fingerprint,published_at,captured_at,state,parser_version,parsed_json,source,source_package FROM observations ORDER BY rowid",null).use {
            while(it.moveToNext()) {
                val row=BackupObservation(it.getString(0),it.getString(1),it.getInt(2),it.getString(3),it.getString(4),it.getString(5),it.getString(6),it.getString(7),it.getString(8),it.getString(9),it.getString(10))
                characters+=row.sourceKey.length.toLong()+(row.parsedJson?.length ?: 0)+256
                if(rows.size>=10_000 || characters>BackupCodec.MAX_PLAINTEXT) throw BackupFailure(BackupProblem.TOO_LARGE)
                rows.add(row)
            }
        }
        return InboxBackup(clock.instant().toString(),rows).also { it.validate() }
    }
    /** Only used on a fresh, inactive encrypted box. Uniqueness failures roll back the entire import. */
    fun importBackup(backup:InboxBackup) {
        backup.validate()
        db.beginTransaction()
        try {
            db.rawQuery("SELECT COUNT(*) FROM observations",null).use { check(it.moveToFirst() && it.getInt(0)==0) }
            for(row in backup.observations) db.execSQL("INSERT INTO observations (id,source_key,revision,fingerprint,published_at,captured_at,payload,state,parser_version,parsed_json,source,source_package) VALUES (?,?,?,?,?,?,NULL,?,?,?,?,?)",
                arrayOf<Any?>(row.id,row.sourceKey,row.revision,row.fingerprint,row.publishedAt,row.capturedAt,row.state,row.parserVersion,row.parsedJson,row.source,row.sourcePackage))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun verifyEncrypted(): Boolean {
        expireRaw()
        db.rawQuery("PRAGMA cipher_integrity_check",null).use { check(!it.moveToFirst()) { "Falha de integridade cifrada." } }
        check(file.inputStream().use { input ->
            val header = ByteArray(16)
            check(input.read(header) == 16)
            String(header, Charsets.US_ASCII)
        } != "SQLite format 3\u0000")
        return true
    }
    fun expireRaw():RawRetentionSummary = try { RawRetention.purge(db,clock.instant()) }
        catch(error:Exception) { throw CaptureStorageFailure(CaptureStorageStage.RETENTION,error) }
    fun securityConfiguration():Map<String,Any> {
        expireRaw()
        fun integer(sql:String)=db.rawQuery(sql,null).use { check(it.moveToFirst());it.getInt(0) }
        val level=db.rawQuery("PRAGMA cipher_log_level",null).use { check(it.moveToFirst());it.getString(0) }
        return mapOf("tempStore" to integer("PRAGMA temp_store"),"secureDelete" to integer("PRAGMA secure_delete"),
            "foreignKeys" to integer("PRAGMA foreign_keys"),"synchronous" to integer("PRAGMA synchronous"),"coreLogLevel" to level)
    }
    override fun close() { db.close() }
    fun reset() { close(); check(context.deleteDatabase(file.name)); vault.erase() }
}
