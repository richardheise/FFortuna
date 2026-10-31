package org.ffortuna.capture

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Bounded encrypted diagnostic evidence only; no ledger, parsing, backups or synthetic-box migration. */
class DiagnosticStore(private val context:Context,private val name:String="ffortuna-diagnostic-v1",allowCreate:Boolean=false):AutoCloseable {
    private val file=context.getDatabasePath("$name.db")
    private val vault=KeyVault(context,name)
    private val db:SQLiteDatabase
    init {
        SqlCipherPolicy.initialize()
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        if(!file.exists()) check(allowCreate && !vault.hasStoredMaterial()) { "DIAGNOSTIC_MISSING_DB" }
        else check(file.isFile && file.length()>=16) { "DIAGNOSTIC_INVALID_DB" }
        val key=vault.load(file.exists())
        db=try { SQLiteDatabase.openOrCreateDatabase(file,key,null,SqlCipherPolicy.failClosed,SqlCipherPolicy.hook) } finally { key.fill(0) }
        try {
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery("PRAGMA secure_delete=ON",null).use { check(it.moveToFirst() && it.getInt(0)==1) }
            db.execSQL("PRAGMA synchronous=FULL");db.execSQL("PRAGMA cipher_memory_security=ON")
            db.beginTransaction()
            try {
                check(db.version<=3)
                if(db.version==0) {
                    db.execSQL("""CREATE TABLE session(id INTEGER PRIMARY KEY CHECK(id=1),session_id TEXT NOT NULL,
                        started_at TEXT NOT NULL,expires_at TEXT NOT NULL,elapsed_start INTEGER NOT NULL,boot INTEGER NOT NULL,
                        signer TEXT NOT NULL,enabled INTEGER NOT NULL CHECK(enabled IN (0,1)))""")
                    db.execSQL("""CREATE TABLE samples(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,source_key TEXT NOT NULL,
                        revision INTEGER NOT NULL,fingerprint TEXT NOT NULL,published_at TEXT NOT NULL,captured_at TEXT NOT NULL,
                        is_summary INTEGER NOT NULL,group_key TEXT,payload TEXT,truncated INTEGER NOT NULL,
                        UNIQUE(source_key,fingerprint),UNIQUE(source_key,revision))""")
                    db.execSQL("CREATE TABLE reviews(sample_id TEXT PRIMARY KEY REFERENCES samples(id) ON DELETE CASCADE,fields TEXT NOT NULL)")
                    db.version=1
                }
                if(db.version==1) {
                    CandidateStore.createSchema(db)
                    // Earlier consent did not include automatic parsing. Preserve data, require a new session.
                    db.execSQL("UPDATE session SET enabled=0")
                    db.version=2
                }
                if(db.version==2) {
                    db.execSQL("ALTER TABLE session ADD COLUMN mode TEXT NOT NULL DEFAULT 'diagnostic'")
                    db.execSQL("ALTER TABLE samples ADD COLUMN mode TEXT NOT NULL DEFAULT 'diagnostic'")
                    db.execSQL("ALTER TABLE samples ADD COLUMN origin_json TEXT")
                    StatementStaging.createSchema(db)
                    db.version=3
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            verify()
        } catch(error:Throwable) { db.close();throw error }
    }
    private data class Session(val id:String,val window:DiagnosticWindow,val signer:String,val enabled:Boolean,val mode:String)
    private fun session():Session?=db.rawQuery("SELECT session_id,started_at,expires_at,elapsed_start,boot,signer,enabled,mode FROM session WHERE id=1",null).use {
        if(!it.moveToFirst()) null else Session(it.getString(0),DiagnosticWindow(Instant.parse(it.getString(1)),Instant.parse(it.getString(2)),it.getLong(3),it.getInt(4)),it.getString(5),it.getInt(6)==1,it.getString(7))
    }
    private fun count(table:String):Int {
        check(table in setOf("samples","reviews"))
        return db.rawQuery("SELECT COUNT(*) FROM $table",null).use { it.moveToFirst();it.getInt(0) }
    }
    fun start(signer:String,now:DiagnosticMoment,daily:Boolean=false) {
        if(!signer.matches(Regex("[a-f0-9]{64}(?::[a-f0-9]{64})*"))) throw DiagnosticFailure("SOURCE_CHANGED")
        maintain(now)
        if(modeCount(if(daily) "daily" else "diagnostic")>=(if(daily) DailyCapturePolicy.MAX_ROWS else DiagnosticPolicy.MAX_ROWS)) throw DiagnosticFailure("SAMPLE_LIMIT")
        val w=DiagnosticPolicy.window(now)
        db.execSQL("INSERT OR REPLACE INTO session VALUES(1,?,?,?,?,?,?,1,?)",arrayOf<Any>(UUID.randomUUID().toString(),w.startedAt.toString(),w.expiresAt.toString(),w.elapsedStart,w.boot,signer,if(daily) "daily" else "diagnostic"))
    }
    fun validateSource(signer:String?) {
        val current=session()
        if(current?.enabled==true && current.signer!=signer) { stop();throw DiagnosticFailure("SOURCE_CHANGED") }
    }
    fun stop() { db.execSQL("UPDATE session SET enabled=0 WHERE id=1") }
    private fun modeCount(mode:String)=db.rawQuery(if(mode=="daily") "SELECT COUNT(*) FROM samples WHERE mode!='diagnostic'" else "SELECT COUNT(*) FROM samples WHERE mode=?",if(mode=="daily") null else arrayOf(mode)).use { it.moveToFirst();it.getInt(0) }
    private fun active(current:Session,now:DiagnosticMoment)=if(current.mode=="daily") !now.wall.isBefore(current.window.startedAt) else current.window.active(now)
    fun maintain(now:DiagnosticMoment) {
        val current=session()
        if(current?.enabled==true && !active(current,now)) stop()
        val expired=mutableListOf<String>();val rawExpired=mutableListOf<String>()
        db.rawQuery("SELECT id,captured_at,mode,payload FROM samples",null).use { rows ->
            while(rows.moveToNext()) {
                val captured=Instant.parse(rows.getString(1))
                if(rows.getString(2)=="diagnostic" && !captured.plus(DiagnosticPolicy.RETENTION).isAfter(now.wall)) expired.add(rows.getString(0))
                else if(rows.getString(2)!="diagnostic" && !rows.isNull(3) && !captured.plus(DailyCapturePolicy.RAW_RETENTION).isAfter(now.wall)) rawExpired.add(rows.getString(0))
            }
        }
        if(expired.isNotEmpty() || rawExpired.isNotEmpty()) {
            db.beginTransaction()
            try {
                // Durable candidates/edits remain; only raw textual evidence expires in daily use.
                for(id in rawExpired) {
                    val parsed=db.rawQuery("SELECT sample_id FROM candidate_parses WHERE sample_id=?",arrayOf(id)).use { it.moveToFirst() }
                    if(!parsed) throw DiagnosticFailure("CANDIDATE_PROCESSING")
                    // Generic notification descriptions can still contain the original sentence.
                    // Remove that derived copy too; explicit user edits and imported transaction fields persist.
                    val mode=db.rawQuery("SELECT mode FROM samples WHERE id=?",arrayOf(id)).use { it.moveToFirst();it.getString(0) }
                    if(mode=="daily") {
                        val parsedJson=db.rawQuery("SELECT result_json FROM candidate_parses WHERE sample_id=?",arrayOf(id)).use { it.moveToFirst();JSONObject(it.getString(0)) }
                        parsedJson.getJSONObject("draft").put("description","")
                        db.execSQL("UPDATE candidate_parses SET result_json=? WHERE sample_id=?",arrayOf(parsedJson.toString(),id))
                    }
                    db.execSQL("UPDATE samples SET payload=NULL WHERE id=?",arrayOf(id))
                    db.execSQL("DELETE FROM reviews WHERE sample_id=?",arrayOf(id))
                }
                for(id in expired) db.execSQL("DELETE FROM samples WHERE id=?",arrayOf(id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            if(db.rawQuery("PRAGMA journal_mode",null).use { it.moveToFirst();it.getString(0).equals("wal",true) })
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use { check(it.moveToFirst() && it.getInt(0)==0) }
        }
    }
    fun state(now:DiagnosticMoment):Map<String,Any?> {
        maintain(now);val current=session();val mode=current?.mode ?: "diagnostic"
        val count=modeCount(mode);val limit=if(mode=="daily") DailyCapturePolicy.MAX_ROWS else DiagnosticPolicy.MAX_ROWS
        return mapOf("status" to "ready","active" to (current?.enabled==true),"mode" to mode,"startedAt" to current?.window?.startedAt?.toString(),
            "expiresAt" to (if(mode=="daily") null else current?.window?.expiresAt?.toString()),"count" to count,"reviewedCount" to count("reviews"),
            "limitReached" to (count>=limit),"failureCode" to null)
    }
    /** Callback is invoked only after origin, consent, time, signer and capacity checks. */
    fun observe(sourcePackage:String,signer:String,sourceKey:String,publishedAt:Long,groupKey:String?,summary:Boolean,
        received:DiagnosticMoment,now:DiagnosticMoment,fields:()->Pair<DiagnosticFields,Boolean>):Boolean {
        if(sourcePackage!=DiagnosticPolicy.PACKAGE) return false
        maintain(now)
        val current=session() ?: return false
        if(!current.enabled || !active(current,received) || !active(current,now) || publishedAt<current.window.startedAt.toEpochMilli()) return false
        if(current.signer!=signer) { stop();throw DiagnosticFailure("SOURCE_CHANGED") }
        val limit=if(current.mode=="daily") DailyCapturePolicy.MAX_ROWS else DiagnosticPolicy.MAX_ROWS
        if(modeCount(current.mode)>=limit) { stop();throw DiagnosticFailure("SAMPLE_LIMIT") }
        if(sourceKey.length>4096 || (groupKey?.length ?: 0)>4096) throw DiagnosticFailure("METADATA_LIMIT")
        val data=if(summary) DiagnosticFields() to false else fields()
        data.first.validate()
        val payload=if(summary) null else data.first.encode()
        val fingerprint=DiagnosticPolicy.fingerprint(payload,publishedAt,summary,groupKey)
        db.beginTransaction()
        try {
            val exists=db.rawQuery("SELECT id FROM samples WHERE source_key=? AND fingerprint=?",arrayOf(sourceKey,fingerprint)).use { it.moveToFirst() }
            if(exists) return false
            val revision=db.rawQuery("SELECT COALESCE(MAX(revision),0)+1 FROM samples WHERE source_key=?",arrayOf(sourceKey)).use { it.moveToFirst();it.getInt(0) }
            db.execSQL("INSERT INTO samples(id,session_id,source_key,revision,fingerprint,published_at,captured_at,is_summary,group_key,payload,truncated,mode) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(UUID.randomUUID().toString(),current.id,sourceKey,revision,fingerprint,
                Instant.ofEpochMilli(publishedAt).toString(),received.wall.toString(),if(summary) 1 else 0,groupKey,payload,if(data.second) 1 else 0,current.mode))
            // Evidence is committed durably before any suggestion/redaction or UI work.
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if(modeCount(current.mode)>=limit) stop()
        return true
    }
    fun samples(now:DiagnosticMoment):List<Map<String,Any?>> {
        maintain(now);val rows=mutableListOf<Map<String,Any?>>()
        db.rawQuery("SELECT s.id,s.revision,s.published_at,s.captured_at,s.is_summary,s.truncated,s.payload,r.sample_id,p.parsed_at,e.fields_json,s.mode,s.origin_json,p.result_json FROM samples s LEFT JOIN reviews r ON r.sample_id=s.id LEFT JOIN candidate_parses p ON p.sample_id=s.id LEFT JOIN candidate_edits e ON e.sample_id=s.id ORDER BY s.rowid DESC",null).use {
            while(it.moveToNext()) {
                val fields=if(!it.isNull(9)) JSONObject(it.getString(9)) else if(!it.isNull(12)) JSONObject(it.getString(12)).getJSONObject("draft") else null
                rows.add(mapOf("id" to it.getString(0),"revision" to it.getInt(1),"publishedAt" to it.getString(2),"capturedAt" to it.getString(3),
                    "isSummary" to (it.getInt(4)==1),"truncated" to (it.getInt(5)==1),"hasText" to (!it.isNull(6) && DiagnosticFields.decode(it.getString(6)).hasText()),"hasReview" to !it.isNull(7),
                    "candidateParsedAt" to if(it.isNull(8)) null else it.getString(8),"candidateReviewStatus" to if(it.isNull(9)) null else JSONObject(it.getString(9)).getString("status"),
                    "effectiveDate" to fields?.optString("effectiveDate")?.takeUnless { value -> value=="null" || value.isBlank() },
                    "mode" to it.getString(10),"origin" to if(it.isNull(11)) "notification" else "statement",
                    "description" to (fields?.optString("description") ?: "Aguardando análise"),
                    "amountCents" to fields?.opt("amountCents")?.takeUnless { value -> value===JSONObject.NULL }))
            }
        }
        return rows
    }
    fun sample(id:String,now:DiagnosticMoment):Map<String,Any?> {
        val meta=samples(now).firstOrNull { it["id"]==id } ?: throw DiagnosticFailure("SAMPLE_MISSING")
        return db.rawQuery("SELECT s.payload,r.fields FROM samples s LEFT JOIN reviews r ON r.sample_id=s.id WHERE s.id=?",arrayOf(id)).use {
            if(!it.moveToFirst()) throw DiagnosticFailure("SAMPLE_MISSING")
            val original=if(it.isNull(0)) DiagnosticFields() else DiagnosticFields.decode(it.getString(0))
            val suggestion=if(it.isNull(1)) original.suggested() else DiagnosticFields.decode(it.getString(1))
            meta+mapOf("original" to original.toMap(),"suggestion" to suggestion.toMap())
        }
    }
    fun review(id:String,json:String,confirmed:Boolean,now:DiagnosticMoment) {
        if(!confirmed) throw DiagnosticFailure("REVIEW_REQUIRED")
        maintain(now)
        val fields=DiagnosticFields.decode(json).also { it.validate(true) }
        if(!db.rawQuery("SELECT id FROM samples WHERE id=?",arrayOf(id)).use { it.moveToFirst() }) throw DiagnosticFailure("SAMPLE_MISSING")
        val exists=db.rawQuery("SELECT sample_id FROM reviews WHERE sample_id=?",arrayOf(id)).use { it.moveToFirst() }
        if(!exists && count("reviews")>=DiagnosticPolicy.MAX_REVIEWED) throw DiagnosticFailure("REVIEW_LIMIT")
        db.execSQL("INSERT OR REPLACE INTO reviews VALUES(?,?)",arrayOf(id,fields.encode()))
    }
    fun excludeReview(id:String) { db.execSQL("DELETE FROM reviews WHERE sample_id=?",arrayOf(id)) }
    fun exportReviewed(now:DiagnosticMoment):ByteArray {
        maintain(now);val result=JSONArray()
        // No real IDs, source keys, groups, sessions, signer/device data, timestamps or originals leave the database.
        db.rawQuery("SELECT s.is_summary,s.truncated,r.fields FROM reviews r JOIN samples s ON s.id=r.sample_id ORDER BY s.rowid",null).use {
            while(it.moveToNext()) {
                val fields=DiagnosticFields.decode(it.getString(2)).also { it.validate(true) }
                result.put(JSONObject().put("sample",result.length()+1).put("groupSummary",it.getInt(0)==1).put("truncated",it.getInt(1)==1)
                    .put("fields",JSONObject(fields.encode())))
            }
        }
        if(result.length()==0) throw DiagnosticFailure("NO_REVIEWED_SAMPLES")
        return JSONObject().put("format","ffortuna-reviewed-notification-samples").put("version",1)
            .put("sourcePackage",DiagnosticPolicy.PACKAGE).put("reviewedByUser",true).put("samples",result).toString().toByteArray(Charsets.UTF_8)
    }
    fun candidate(id:String,now:DiagnosticMoment,force:Boolean=false):Map<String,Any?> {
        maintain(now);return CandidateStore(db).detail(id,now.wall,force)
    }
    fun saveCandidate(id:String,json:String,now:DiagnosticMoment):Map<String,Any?> {
        maintain(now);return CandidateStore(db).save(id,json,now.wall)
    }
    fun processCandidates(now:DiagnosticMoment):Int { val count=CandidateStore(db).processCurrentSession(now.wall);maintain(now);return count }
    fun pendingIds():List<String> = db.rawQuery("SELECT s.id,e.fields_json FROM samples s LEFT JOIN candidate_edits e ON e.sample_id=s.id WHERE s.is_summary=0",null).use { rows ->
        buildList { while(rows.moveToNext()) if(rows.isNull(1) || JSONObject(rows.getString(1)).optString("status")!="dismissed") add(rows.getString(0)) }
    }
    fun statementBatches()=StatementStaging(db).batches()
    fun stageStatement(document:StatementDocument,digest:String,now:DiagnosticMoment)=StatementStaging(db).stage(document,digest,now.wall)
    fun verify() {
        db.rawQuery("PRAGMA cipher_integrity_check",null).use { check(!it.moveToFirst()) }
        check(file.inputStream().use { input -> val b=ByteArray(16);input.read(b)==16 && !b.contentEquals("SQLite format 3\u0000".toByteArray()) })
    }
    override fun close() { db.close() }
}
