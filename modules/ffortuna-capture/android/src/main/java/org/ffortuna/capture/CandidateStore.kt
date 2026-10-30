package org.ffortuna.capture

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Uses the diagnostic owner's connection/executor. Previews are not a second financial ledger. */
class CandidateStore(private val db:SQLiteDatabase) {
    companion object {
        fun createSchema(db:SQLiteDatabase) {
            db.execSQL("""CREATE TABLE candidate_parses(sample_id TEXT PRIMARY KEY REFERENCES samples(id) ON DELETE CASCADE,
                parser_version TEXT NOT NULL,result_json TEXT NOT NULL,parsed_at TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE candidate_edits(sample_id TEXT PRIMARY KEY REFERENCES samples(id) ON DELETE CASCADE,
                fields_json TEXT NOT NULL,saved_at TEXT NOT NULL)""")
        }
        private val reasons=mapOf(
            "AMOUNT_FOUND" to "Valor em BRL localizado no original; natureza, conta e data efetiva exigem revisão.",
            "NO_AMOUNT" to "Nenhum valor em BRL no formato reconhecido; não há prova de movimentação.",
            "AMBIGUOUS_AMOUNT" to "Há mais de uma ocorrência possível; escolha o valor após conferir o original.",
            "INVALID_AMOUNT" to "Valor incompleto, inválido ou fora do limite seguro; não foi escolhido.",
            "TRUNCATED" to "O texto foi truncado; o valor exige revisão do original.",
            "INPUT_LIMIT" to "O texto excede os limites do parser.","EVIDENCE_LIMIT" to "Há ocorrências demais para escolher um valor seguro.")
    }
    private data class Source(val id:String,val sourceKey:String,val revision:Int,val publishedAt:String,val capturedAt:String,
        val payload:String?,val summary:Boolean,val truncated:Boolean)
    private fun source(id:String):Source=db.rawQuery("SELECT id,source_key,revision,published_at,captured_at,payload,is_summary,truncated FROM samples WHERE id=?",arrayOf(id)).use {
        if(!it.moveToFirst()) throw DiagnosticFailure("SAMPLE_MISSING")
        Source(it.getString(0),it.getString(1),it.getInt(2),it.getString(3),it.getString(4),if(it.isNull(5)) null else it.getString(5),it.getInt(6)==1,it.getInt(7)==1)
    }
    private fun original(source:Source):DiagnosticFields {
        if(source.summary) throw DiagnosticFailure("GROUP_SUMMARY")
        return source.payload?.let(DiagnosticFields::decode) ?: throw DiagnosticFailure("PAYLOAD_UNAVAILABLE")
    }
    private fun draft(fields:DiagnosticFields,amount:Long?):Map<String,Any?> = mapOf("amountCents" to amount,"kind" to null,"effectiveDate" to null,
        "effectiveAt" to null,"description" to listOf(fields.text,fields.bigText,fields.title).firstOrNull { it.isNotBlank() }?.take(500).orEmpty(),
        "category" to "A classificar","reserveId" to null,"direction" to null)
    private fun parse(source:Source,now:Instant) {
        val fields=original(source)
        val texts=listOf("title" to fields.title,"text" to fields.text,"bigText" to fields.bigText,"subText" to fields.subText)+
            fields.textLines.mapIndexed { index,text -> "textLines[$index]" to text }
        val result=BrlNotificationParser.parse(texts,source.truncated)
        val evidence=result.evidence.map { mapOf("sourceField" to it.field,"start" to it.start,"end" to it.end,"observedText" to it.text,"amountCents" to it.amountCents) }
        val primary=result.evidence.firstOrNull { it.amountCents==result.amountCents && result.amountCents!=null }
        val fieldEvidence=if(primary==null) emptyMap() else mapOf("amountCents" to mapOf("value" to result.amountCents.toString(),
            "sourceField" to primary.field,"observedText" to primary.text,"start" to primary.start,"end" to primary.end))
        val data=mapOf("parserVersion" to BrlNotificationParser.VERSION,"draft" to draft(fields,result.amountCents),
            "confidence" to mapOf("movement" to "uncertain","amount" to result.amountConfidence,"nature" to "missing","account" to "missing","date" to "missing","category" to "uncertain"),
            "fieldEvidence" to fieldEvidence,"amountEvidence" to evidence,"externalId" to null,
            "reasonCode" to result.reasonCode,"reason" to (reasons[result.reasonCode] ?: "Revisar antes de interpretar financeiramente."),"automaticPostingAllowed" to false)
        // Edits reference samples, never this replaceable parsing row. Reprocessing cannot cascade-delete corrections.
        db.execSQL("INSERT OR REPLACE INTO candidate_parses VALUES(?,?,?,?)",arrayOf(source.id,BrlNotificationParser.VERSION,JSONObject(data).toString(),now.toString()))
    }
    fun processCurrentSession(now:Instant):Int {
        val pending=mutableListOf<String>()
        db.rawQuery("""SELECT s.id FROM samples s LEFT JOIN candidate_parses p ON p.sample_id=s.id
            WHERE s.session_id=(SELECT session_id FROM session WHERE id=1) AND s.is_summary=0 AND s.payload IS NOT NULL
            AND (p.sample_id IS NULL OR p.parser_version!=?) ORDER BY s.rowid""",arrayOf(BrlNotificationParser.VERSION)).use {
            while(it.moveToNext()) pending.add(it.getString(0))
        }
        for(id in pending) parse(source(id),now)
        return pending.size
    }
    fun detail(id:String,now:Instant,force:Boolean=false):Map<String,Any?> {
        val source=source(id);val original=original(source)
        val version=db.rawQuery("SELECT parser_version FROM candidate_parses WHERE sample_id=?",arrayOf(id)).use { if(it.moveToFirst()) it.getString(0) else null }
        if(force || version!=BrlNotificationParser.VERSION) parse(source,now)
        val parsed=db.rawQuery("SELECT result_json,parsed_at FROM candidate_parses WHERE sample_id=?",arrayOf(id)).use {
            check(it.moveToFirst());JSONObject(it.getString(0)) to it.getString(1)
        }
        val edit=db.rawQuery("SELECT fields_json,saved_at FROM candidate_edits WHERE sample_id=?",arrayOf(id)).use {
            if(it.moveToFirst()) JSONObject(it.getString(0)) to it.getString(1) else null
        }
        val previous=db.rawQuery("SELECT id FROM samples WHERE source_key=? AND revision<? ORDER BY revision DESC LIMIT 1",arrayOf(source.sourceKey,source.revision.toString())).use {
            if(it.moveToFirst()) it.getString(0) else null
        }
        val related=db.rawQuery("SELECT COUNT(*) FROM candidate_edits e JOIN samples s ON s.id=e.sample_id WHERE s.source_key=? AND s.id!=?",arrayOf(source.sourceKey,id)).use { it.moveToFirst();it.getInt(0)>0 }
        val later=db.rawQuery("SELECT COUNT(*) FROM samples WHERE source_key=? AND revision>?",arrayOf(source.sourceKey,source.revision.toString())).use { it.moveToFirst();it.getInt(0)>0 }
        return mapOf("id" to id,"eventId" to id,"sourceKey" to source.sourceKey,"revision" to source.revision,"publishedAt" to source.publishedAt,"capturedAt" to source.capturedAt,
            "parsedAt" to parsed.second,"parsed" to jsonMap(parsed.first),"original" to original.toMap(),
            "review" to edit?.let { jsonMap(it.first) },"reviewedAt" to edit?.second,"previousSampleId" to previous,
            "relatedReviewExists" to related,"hasLaterRevision" to later,"temporary" to true,"transactionId" to null)
    }
    fun save(id:String,json:String,now:Instant):Map<String,Any?> {
        if(json.length>8192) throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW")
        val root=try { JSONObject(json) } catch (_:Exception) { throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW") }
        val required=setOf("amountCents","kind","effectiveDate","description","category","status")
        val keys=root.keys().asSequence().toSet()
        if(!keys.containsAll(required) || !required.plus(setOf("categoryId","tagIds")).containsAll(keys)) throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW")
        ClassificationReview.validate(root)
        val amount=if(root.isNull("amountCents")) null else when(val value=root.get("amountCents")) {
            is Int -> value.toLong(); is Long -> value;else -> throw DiagnosticFailure("INVALID_AMOUNT")
        }
        if(amount!=null && amount !in 1..BrlNotificationParser.MAX_CENTS) throw DiagnosticFailure("INVALID_AMOUNT")
        fun nullable(key:String):String?=if(root.isNull(key)) null else root.get(key) as? String ?: throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW")
        val kind=nullable("kind")
        if(kind!=null && kind !in setOf("income","expense","transfer","other")) throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW")
        val date=nullable("effectiveDate")
        if(date!=null) try {
            if(!date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) || LocalDate.parse(date).toString()!=date || date.startsWith("0000")) throw IllegalArgumentException()
        } catch (_:Exception) { throw DiagnosticFailure("INVALID_DATE") }
        val description=root.get("description") as? String ?: throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW")
        val category=root.get("category") as? String ?: throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW")
        val status=root.get("status") as? String ?: throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW")
        if(description.length>500 || category.length>80 || status !in setOf("reviewed","dismissed") || (description+category).any { Character.isISOControl(it) && it!='\n' && it!='\t' })
            throw DiagnosticFailure("INVALID_CANDIDATE_REVIEW")
        original(source(id))
        db.execSQL("INSERT OR REPLACE INTO candidate_edits VALUES(?,?,?)",arrayOf(id,root.toString(),now.toString()))
        return detail(id,now)
    }
    private fun jsonMap(root:JSONObject):Map<String,Any?> = root.keys().asSequence().associateWith { key -> jsonValue(root.get(key)) }
    private fun jsonValue(value:Any?):Any?=when(value) {
        null,JSONObject.NULL -> null
        is JSONObject -> jsonMap(value)
        is org.json.JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }
}
