package org.ffortuna.capture

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

data class ObservedBatchPost(
    val eventId: String,
    val amountCents: Long,
    val kind: String,
    val effectiveDate: String,
    val description: String,
    val category: String,
    val categoryId: String?,
    val tagIds: List<String>,
    val source: Map<String, Any?>
)

/** One permanent BRL book, on the catalog owner's connection and serial executor. */
class FinancialLedger(private val db:SQLiteDatabase) {
    companion object {
        val tables=listOf("classifications","finance_profile","finance_transactions","finance_entries","finance_tags","finance_balance_checks","finance_cancellations","finance_observation_links",
            "finance_reserves","finance_cards","finance_commitments","finance_extended_transactions","finance_extended_entries","finance_extended_tags","finance_extended_cancellations","finance_extended_observation_links","finance_metadata_rules","finance_metadata_audit")
        fun createSchema(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE finance_profile(id INTEGER PRIMARY KEY CHECK(id=1),opening_date TEXT NOT NULL,opening_cents INTEGER NOT NULL)")
            db.execSQL("""CREATE TABLE finance_transactions(id TEXT PRIMARY KEY,command_key TEXT UNIQUE NOT NULL,request_json TEXT NOT NULL,
                kind TEXT NOT NULL CHECK(kind IN ('opening','income','expense')),amount INTEGER NOT NULL,effective_date TEXT NOT NULL,
                description TEXT NOT NULL,category_id TEXT REFERENCES classifications(id),category_legacy TEXT NOT NULL,
                created_at TEXT NOT NULL,source_event_id TEXT UNIQUE,source_json TEXT)""")
            db.execSQL("""CREATE TABLE finance_entries(transaction_id TEXT NOT NULL REFERENCES finance_transactions(id) ON DELETE CASCADE,
                resource TEXT NOT NULL CHECK(resource IN ('cash','opening','income','expense')),cents INTEGER NOT NULL,PRIMARY KEY(transaction_id,resource))""")
            db.execSQL("""CREATE TABLE finance_tags(transaction_id TEXT NOT NULL REFERENCES finance_transactions(id) ON DELETE CASCADE,
                tag_id TEXT NOT NULL REFERENCES classifications(id),PRIMARY KEY(transaction_id,tag_id))""")
            db.execSQL("CREATE TABLE finance_balance_checks(id TEXT PRIMARY KEY,effective_date TEXT NOT NULL,amount INTEGER NOT NULL,created_at TEXT NOT NULL)")
            db.execSQL("CREATE TABLE finance_cancellations(transaction_id TEXT PRIMARY KEY REFERENCES finance_transactions(id),reason TEXT NOT NULL,cancelled_at TEXT NOT NULL)")
        }
        fun createLinksSchema(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE finance_observation_links(event_id TEXT PRIMARY KEY,transaction_id TEXT NOT NULL REFERENCES finance_transactions(id),source_json TEXT NOT NULL)")
        }
        fun createResourcesSchema(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE finance_reserves(id TEXT PRIMARY KEY,command_id TEXT UNIQUE NOT NULL,name TEXT NOT NULL,modality TEXT NOT NULL,opening_cents INTEGER NOT NULL,created_on TEXT NOT NULL,updated_on TEXT NOT NULL)")
            db.execSQL("CREATE TABLE finance_cards(id TEXT PRIMARY KEY,command_id TEXT UNIQUE NOT NULL,name TEXT NOT NULL,closing_day INTEGER NOT NULL,due_day INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE finance_commitments(id TEXT PRIMARY KEY,command_id TEXT UNIQUE NOT NULL,card_id TEXT REFERENCES finance_cards(id),description TEXT NOT NULL,due_date TEXT NOT NULL,invoice_month TEXT,amount INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE finance_extended_transactions(id TEXT PRIMARY KEY,command_key TEXT UNIQUE NOT NULL,request_json TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('reserve_opening','reserve_move','reserve_adjustment','transfer','card_purchase','commitment_opening','card_payment')),amount INTEGER NOT NULL,effective_date TEXT NOT NULL,description TEXT NOT NULL,category_id TEXT REFERENCES classifications(id),category_legacy TEXT NOT NULL,resource_id TEXT,resource_name TEXT,commitment_id TEXT REFERENCES finance_commitments(id),invoice_month TEXT,direction TEXT,created_at TEXT NOT NULL,source_event_id TEXT UNIQUE,source_json TEXT)")
            db.execSQL("CREATE TABLE finance_extended_entries(transaction_id TEXT NOT NULL REFERENCES finance_extended_transactions(id) ON DELETE CASCADE,resource TEXT NOT NULL,cents INTEGER NOT NULL,PRIMARY KEY(transaction_id,resource))")
            db.execSQL("CREATE TABLE finance_extended_tags(transaction_id TEXT NOT NULL REFERENCES finance_extended_transactions(id) ON DELETE CASCADE,tag_id TEXT NOT NULL REFERENCES classifications(id),PRIMARY KEY(transaction_id,tag_id))")
            db.execSQL("CREATE TABLE finance_extended_cancellations(transaction_id TEXT PRIMARY KEY REFERENCES finance_extended_transactions(id),reason TEXT NOT NULL,cancelled_at TEXT NOT NULL)")
            db.execSQL("CREATE TABLE finance_extended_observation_links(event_id TEXT PRIMARY KEY,transaction_id TEXT NOT NULL REFERENCES finance_extended_transactions(id),source_json TEXT NOT NULL)")
        }
        fun jsonMap(root:JSONObject):Map<String,Any?> = root.keys().asSequence().associateWith { key -> jsonValue(root.get(key)) }
        private fun jsonValue(value:Any?):Any?=when(value) {
            null,JSONObject.NULL -> null
            is JSONObject -> jsonMap(value)
            is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
            else -> value
        }
    }
    private inline fun <T> atomic(block:()->T):T {
        db.beginTransaction()
        try { val result=block();validate();FinancialBackup.encode(this).fill(0);db.setTransactionSuccessful();return result } finally { db.endTransaction() }
    }
    fun configured()=db.rawQuery("SELECT COUNT(*) FROM finance_profile",null).use { it.moveToFirst();it.getInt(0)==1 }
    fun confirmedEvents():Map<String,String> = db.rawQuery("SELECT source_event_id,id FROM finance_transactions WHERE source_event_id IS NOT NULL",null).use { rows ->
        buildMap { while(rows.moveToNext()) put(rows.getString(0),rows.getString(1));db.rawQuery("SELECT event_id,transaction_id FROM finance_observation_links UNION ALL SELECT event_id,transaction_id FROM finance_extended_observation_links UNION ALL SELECT source_event_id,id FROM finance_extended_transactions WHERE source_event_id IS NOT NULL",null).use { links -> while(links.moveToNext()) put(links.getString(0),links.getString(1)) } }
    }
    fun startTracking() { if(!configured()) setup("{\"openingDate\":\"0001-01-01\",\"openingBalanceCents\":0}") }
    fun matching(amount:Long?,date:String?):List<Map<String,Any?>> {
        if(amount==null) return emptyList()
        val cancelled=rows("finance_cancellations").map { it["transaction_id"] }.toSet()
        val legacy=rows("finance_transactions").asReversed().filter { it["kind"]!="opening" && it["id"] !in cancelled && it["amount"]==amount && (date==null || it["effective_date"]==date) }.take(10).map {
            mapOf("id" to it["id"],"scope" to "finance","description" to it["description"],"amountCents" to it["amount"],"effectiveDate" to it["effective_date"],"kind" to it["kind"])
        }
        val extVoided=rows("finance_extended_cancellations").map { it["transaction_id"] }.toSet()
        val card=rows("finance_extended_transactions").asReversed().filter { it["kind"]=="card_purchase"&&it["id"] !in extVoided&&it["amount"]==amount&&(date==null||it["effective_date"]==date) }.take(10).map {
            mapOf("id" to it["id"],"scope" to "extended","description" to it["description"],"amountCents" to it["amount"],"effectiveDate" to it["effective_date"],"kind" to it["kind"])
        }
        return (legacy+card).sortedByDescending { it["effectiveDate"] as String }.take(10)
    }
    fun statementMatches(key:String):List<Map<String,Any?>> {
        val candidates=rows("finance_transactions").filter { row ->
            (row["source_json"] as? String)?.let { val source=JSONObject(it);source.optString("packageName")=="org.tique.statement" && source.optString("sourceKey")==key }==true
        }.map { it["id"] }.toMutableSet()
        for(link in rows("finance_observation_links")) {
            val source=JSONObject(link["source_json"] as String)
            if(source.optString("packageName")=="org.tique.statement" && source.optString("sourceKey")==key) candidates.add(link["transaction_id"])
        }
        val voided=rows("finance_cancellations").map { it["transaction_id"] }.toSet()
        val results=rows("finance_transactions").filter { it["id"] in candidates && it["id"] !in voided }.map {
            mapOf("id" to it["id"],"description" to it["description"],"amountCents" to it["amount"],"effectiveDate" to it["effective_date"],"kind" to it["kind"])
        }
        val extCandidates=rows("finance_extended_transactions").filter { row ->
            (row["source_json"] as? String)?.let { val source=JSONObject(it);source.optString("packageName")=="org.tique.statement"&&source.optString("sourceKey")==key }==true
        }.map { it["id"] }.toMutableSet()
        for(link in rows("finance_extended_observation_links")) { val source=JSONObject(link["source_json"] as String);if(source.optString("packageName")=="org.tique.statement"&&source.optString("sourceKey")==key) extCandidates.add(link["transaction_id"]) }
        val extVoided=rows("finance_extended_cancellations").map { it["transaction_id"] }.toSet()
        return results+rows("finance_extended_transactions").filter { it["id"] in extCandidates&&it["id"] !in extVoided }.map {
            mapOf("id" to it["id"],"description" to it["description"],"amountCents" to it["amount"],"effectiveDate" to it["effective_date"],"kind" to it["kind"])
        }
    }
    fun linkObservation(id:String,transactionId:String,source:Map<String,Any?>)=atomic {
        val current=confirmedEvents()[id]
        if(current!=null) { if(current!=transactionId) throw FinanceFailure("CONFIRMATION_CONFLICT");return@atomic }
        val legacy=rows("finance_transactions").any { it["id"]==transactionId && it["kind"]!="opening" } && rows("finance_cancellations").none { it["transaction_id"]==transactionId }
        val extended=rows("finance_extended_transactions").any { it["id"]==transactionId } && rows("finance_extended_cancellations").none { it["transaction_id"]==transactionId }
        if(!legacy&&!extended) throw FinanceFailure("NOT_FOUND")
        if(source["packageName"]=="org.tique.statement" && statementMatches(source["sourceKey"] as String).any { it["id"]!=transactionId }) throw FinanceFailure("IMPORT_CONFLICT")
        db.execSQL(if(extended) "INSERT INTO finance_extended_observation_links VALUES(?,?,?)" else "INSERT INTO finance_observation_links VALUES(?,?,?)",arrayOf(id,transactionId,JSONObject(source).toString()))
    }
    private fun profile():Pair<String,Long> = db.rawQuery("SELECT opening_date,opening_cents FROM finance_profile WHERE id=1",null).use {
        if(!it.moveToFirst()) throw FinanceFailure("ACCOUNT_REQUIRED");it.getString(0) to it.getLong(1)
    }
    private fun root(json:String,keys:Set<String>):JSONObject {
        if(json.length>8192) throw FinanceFailure("INVALID_FIELDS")
        val value=try { JSONObject(json) } catch (_:Exception) { throw FinanceFailure("INVALID_FIELDS") }
        if(value.keys().asSequence().toSet()!=keys) throw FinanceFailure("INVALID_FIELDS")
        return value
    }
    private fun string(root:JSONObject,key:String)=root.get(key) as? String ?: throw FinanceFailure("INVALID_FIELDS")
    private fun resourceText(value:String,max:Int):String=value.trim().also { if(it.isBlank()||it.length>max||it.any { char->Character.isISOControl(char) }) throw FinanceFailure("INVALID_FIELDS") }
    private fun currentDate(date:String) {
        FinanceRules.date(date)
        if(date>LocalDate.now().toString()) throw FinanceFailure("FUTURE_DATE")
    }
    fun setup(json:String) = atomic { setupInternal(json) }
    private fun setupInternal(json:String) {
        if(configured()) throw FinanceFailure("ACCOUNT_EXISTS")
        val value=root(json,setOf("openingDate","openingBalanceCents"));val date=string(value,"openingDate");currentDate(date)
        val balance=FinanceRules.cents(value.get("openingBalanceCents"),true)
        db.execSQL("INSERT INTO finance_profile VALUES(1,?,?)",arrayOf<Any>(date,balance))
        val id=UUID.randomUUID().toString()
        db.execSQL("INSERT INTO finance_transactions VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(id,"opening","{}","opening",kotlin.math.abs(balance),date,"Saldo de abertura",null,"",Instant.now().toString(),null,null))
        entries(id,balance,"opening")
    }
    private fun entries(id:String,cash:Long,counterpart:String) {
        db.execSQL("INSERT INTO finance_entries VALUES(?,?,?)",arrayOf<Any>(id,"cash",cash))
        db.execSQL("INSERT INTO finance_entries VALUES(?,?,?)",arrayOf<Any>(id,counterpart,-cash))
    }
    private fun catalog():List<Map<String,Any?>> = db.rawQuery("SELECT id,kind,name,archived FROM classifications ORDER BY id",null).use { rows ->
        buildList { while(rows.moveToNext()) add(mapOf("id" to rows.getString(0),"kind" to rows.getString(1),"name" to rows.getString(2),"archived" to (rows.getInt(3)==1))) }
    }
    fun transactionForEvent(id:String):Map<String,Any?>? {
        return db.rawQuery("SELECT id,request_json,category_legacy,kind,category_id FROM finance_transactions WHERE id=(SELECT transaction_id FROM finance_observation_links WHERE event_id=?) OR source_event_id=? UNION ALL SELECT id,request_json,category_legacy,kind,category_id FROM finance_extended_transactions WHERE id=(SELECT transaction_id FROM finance_extended_observation_links WHERE event_id=?) OR source_event_id=? LIMIT 1",arrayOf(id,id,id,id)).use {
        if(!it.moveToFirst()) null else {
            val fields=JSONObject(it.getString(1));val categoryId=if(it.isNull(4)) null else it.getString(4)
            val label=catalog().firstOrNull { option -> option["id"]==categoryId }?.get("name") ?: it.getString(2)
            fields.put("category",label).put("categoryId",categoryId ?: JSONObject.NULL)
            val tagsTable=if(it.getString(3)=="card_purchase") "finance_extended_tags" else "finance_tags"
            val tags=db.rawQuery("SELECT tag_id FROM $tagsTable WHERE transaction_id=? ORDER BY tag_id",arrayOf(it.getString(0))).use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.getString(0)) } }
            fields.put("tagIds",JSONArray(tags))
            mapOf("id" to it.getString(0),"fields" to jsonMap(fields),"kind" to it.getString(3),"scope" to (if(it.getString(3)=="card_purchase") "extended" else "finance"))
        }
    } }
    fun post(json:String,source:Map<String,Any?>?=null):String = atomic { postInternal(json,source) }
    private fun postInternal(json:String,source:Map<String,Any?>?):String {
        profile()
        val manual=source==null
        val keys=setOf("amountCents","kind","effectiveDate","description","category","categoryId","tagIds")+(if(manual) setOf("commandId") else emptySet())
        val root=root(json,keys)
        val command=if(manual) string(root,"commandId").also { if(runCatching { UUID.fromString(it).toString()==it }.getOrDefault(false)!=true) throw FinanceFailure("INVALID_FIELDS") } else source!!["eventId"] as String
        val key=(if(manual) "manual:" else "notification:")+command
        val amount=FinanceRules.cents(root.get("amountCents"));val kind=string(root,"kind")
        if(kind !in setOf("income","expense")) throw FinanceFailure("UNSUPPORTED_MOVEMENT")
        val date=string(root,"effectiveDate");currentDate(date)
        val description=FinanceRules.text(string(root,"description"),500)
        val existing=db.rawQuery("SELECT id,request_json FROM finance_transactions WHERE command_key=?",arrayOf(key)).use {
            if(it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }
        // Existing archived selections may be kept; new ones require active catalog options.
        val previous=existing?.second?.let { jsonMap(JSONObject(it)) }
        val normalized=JSONObject(ClassificationReview.prepare(root.toString(),catalog(),previous))
        val category=FinanceRules.text(string(normalized,"category"),80)
        val categoryId=if(normalized.isNull("categoryId")) null else normalized.getString("categoryId")
        val tags=normalized.getJSONArray("tagIds");val ids=(0 until tags.length()).map { tags.getString(it) }.sorted()
        // Canonical IDs make retries independent of catalog renames and tag selection order.
        val request=JSONObject(linkedMapOf("amountCents" to amount,"kind" to kind,"effectiveDate" to date,"description" to description,
            "category" to (if(categoryId==null) category else ""),"categoryId" to categoryId,"tagIds" to ids)).toString()
        if(existing!=null) {
            if(JSONObject(existing.second).toString()!=JSONObject(request).toString()) throw FinanceFailure("CONFIRMATION_CONFLICT")
            return existing.first
        }
        if(source?.get("packageName")=="org.tique.statement" && statementMatches(source["sourceKey"] as String).isNotEmpty()) throw FinanceFailure("IMPORT_CONFLICT")
        val transactionCount=db.rawQuery("SELECT COUNT(*) FROM finance_transactions",null).use { it.moveToFirst();it.getInt(0) }
        if(transactionCount>=10_000) throw FinanceFailure("TRANSACTION_LIMIT")
        val id=UUID.randomUUID().toString();val sourceJson=source?.let { JSONObject(it).toString() }
        if(sourceJson!=null && sourceJson.length>4096) throw FinanceFailure("INVALID_SOURCE")
        db.execSQL("INSERT INTO finance_transactions VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(id,key,request,kind,amount,date,description,categoryId,category,Instant.now().toString(),if(manual) null else command,sourceJson))
        entries(id,if(kind=="income") amount else -amount,kind)
        for(tag in ids) db.execSQL("INSERT INTO finance_tags VALUES(?,?)",arrayOf(id,tag))
        return id
    }
    fun confirmObservedBatch(items:List<ObservedBatchPost>):Map<String,Any?> {
        if(items.size>StatementParser.MAX_ROWS) throw FinanceFailure("BATCH_LIMIT")
        if(items.map { it.eventId }.distinct().size!=items.size) throw FinanceFailure("INVALID_FIELDS")
        if(items.isEmpty()) return mapOf("registered" to 0,"months" to emptyList<String>())
        return atomic {
            if(!configured()) setupInternal("{\"openingDate\":\"0001-01-01\",\"openingBalanceCents\":0}")
            // Check against the pre-batch book before inserting any selected row. Equal rows
            // inside this batch remain distinct; only an existing posted movement needs review.
            for(item in items) if(!hasPostedEvent(item.eventId) && hasMatchingPostedMovement(item.amountCents,item.effectiveDate))
                throw FinanceFailure("OBSERVATION_CONFLICT")
            val months=items.map { item ->
                if(UUID.fromString(item.eventId).toString()!=item.eventId || item.source["eventId"]!=item.eventId) throw FinanceFailure("INVALID_SOURCE")
                if(item.kind !in setOf("income","expense")) throw FinanceFailure("UNSUPPORTED_MOVEMENT")
                val request=JSONObject().put("amountCents",item.amountCents).put("kind",item.kind)
                    .put("effectiveDate",item.effectiveDate).put("description",item.description)
                    .put("category",item.category).put("categoryId",item.categoryId ?: JSONObject.NULL)
                    .put("tagIds",JSONArray(item.tagIds)).toString()
                postInternal(request,item.source)
                FinanceRules.date(item.effectiveDate).take(7)
            }.distinct().sorted()
            mapOf("registered" to items.size,"months" to months)
        }
    }
    fun hasObservationLink(eventId:String)=db.rawQuery("SELECT event_id FROM finance_observation_links WHERE event_id=? UNION ALL SELECT event_id FROM finance_extended_observation_links WHERE event_id=? LIMIT 1",arrayOf(eventId,eventId)).use { it.moveToFirst() }
    fun hasPostedEvent(eventId:String)=db.rawQuery("SELECT source_event_id FROM finance_transactions WHERE source_event_id=? UNION ALL SELECT source_event_id FROM finance_extended_transactions WHERE source_event_id=? LIMIT 1",arrayOf(eventId,eventId)).use { it.moveToFirst() }
    fun hasMatchingPostedMovement(amount:Long,date:String)=db.rawQuery("""SELECT t.id FROM finance_transactions t
        WHERE t.kind IN ('income','expense') AND t.amount=? AND t.effective_date=?
        AND NOT EXISTS(SELECT 1 FROM finance_cancellations c WHERE c.transaction_id=t.id) LIMIT 1""",
        arrayOf(amount.toString(),date)).use { it.moveToFirst() }
    private fun commandId(value:JSONObject):String=string(value,"commandId").also {
        if(runCatching { UUID.fromString(it).toString()==it }.getOrDefault(false)!=true) throw FinanceFailure("INVALID_FIELDS")
    }
    private fun resourceRoot(json:String,keys:Set<String>)=root(json,keys+"commandId")
    private fun ensureTracking() { if(!configured()) setupInternal("{\"openingDate\":\"0001-01-01\",\"openingBalanceCents\":0}") }
    private fun reserveBalance(id:String,until:String="9999-12-31"):Long {
        val voided=rows("finance_extended_cancellations").map { it["transaction_id"] }.toSet()
        val dates=rows("finance_extended_transactions").filter { it["id"] !in voided&&(it["effective_date"] as String)<=until }.associate { it["id"] to it["effective_date"] }
        return FinanceRules.sum(rows("finance_extended_entries").filter { it["resource"]=="reserve:$id"&&it["transaction_id"] in dates }.map { it["cents"] as Long })
    }
    private fun reservesName(id:String):String?=rows("finance_reserves").firstOrNull { it["id"]==id }?.get("name") as? String
    private fun normalizedExtendedRequest(request:JSONObject,amount:Long,kind:String,date:String,description:String,categoryId:String?,category:String,tagIds:List<String>):JSONObject =
        JSONObject(request.toString()).put("amountCents",amount).put("kind",kind).put("effectiveDate",date).put("description",description).put("category",category).put("categoryId",categoryId ?: JSONObject.NULL).put("tagIds",JSONArray(tagIds.sorted()))
    private fun replayExtended(command:String,expected:JSONObject):String? {
        val prior=db.rawQuery("SELECT id,request_json FROM finance_extended_transactions WHERE command_key=?",arrayOf("manual:$command")).use { if(it.moveToFirst()) it.getString(0) to JSONObject(it.getString(1)) else null } ?: return null
        val old=prior.second;val keys=expected.keys().asSequence().toSet()-"commandId"
        if((old.keys().asSequence().toSet()-"commandId")!=keys||keys.any { old.get(it).toString()!=expected.get(it).toString() }) throw FinanceFailure("CONFIRMATION_CONFLICT")
        return prior.first
    }
    private fun replayInput(command:String,request:JSONObject,keys:Set<String>):String? {
        val prior=db.rawQuery("SELECT id,request_json FROM finance_extended_transactions WHERE command_key=?",arrayOf("manual:$command")).use { if(it.moveToFirst()) it.getString(0) to JSONObject(it.getString(1)) else null } ?: return null
        if(keys.any { key->if(!request.has(key)||!prior.second.has(key)) true else request.get(key).toString()!=prior.second.get(key).toString() }) throw FinanceFailure("CONFIRMATION_CONFLICT")
        return prior.first
    }
    private fun recordExtended(command:String,kind:String,amount:Long,date:String,description:String,categoryId:String?=null,category:String="",resourceId:String?=null,resourceName:String?=null,commitmentId:String?=null,invoiceMonth:String?=null,direction:String?=null,request:JSONObject,legs:Map<String,Long>,tagIds:List<String> = emptyList()):String {
        val key="manual:$command"
        val prior=db.rawQuery("SELECT id,request_json FROM finance_extended_transactions WHERE command_key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) to it.getString(1) else null }
        val normalizedRequest=normalizedExtendedRequest(request,amount,kind,date,description,categoryId,category,tagIds)
        if(prior!=null) { if(JSONObject(prior.second).toString()!=normalizedRequest.toString()) throw FinanceFailure("CONFIRMATION_CONFLICT");return prior.first }
        if(legs.values.fold(java.math.BigInteger.ZERO) { sum,value -> sum+java.math.BigInteger.valueOf(value) }!=java.math.BigInteger.ZERO) throw FinanceFailure("UNBALANCED_ENTRIES")
        val id=UUID.randomUUID().toString()
        db.execSQL("INSERT INTO finance_extended_transactions VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(id,key,normalizedRequest.toString(),kind,amount,date,description,categoryId,category,resourceId,resourceName,commitmentId,invoiceMonth,direction,Instant.now().toString(),null,null))
        for((resource,cents) in legs) db.execSQL("INSERT INTO finance_extended_entries VALUES(?,?,?)",arrayOf(id,resource,cents))
        for(tag in tagIds.distinct().sorted()) db.execSQL("INSERT INTO finance_extended_tags VALUES(?,?)",arrayOf(id,tag))
        return id
    }
    fun createReserve(json:String):String=atomic {
        ensureTracking();val value=resourceRoot(json,setOf("name","modality","date","amountCents"));val command=commandId(value)
        val name=resourceText(string(value,"name"),80);val modality=resourceText(string(value,"modality"),40)
        val date=string(value,"date");currentDate(date);val amount=FinanceRules.cents(value.get("amountCents"),true);if(amount<0) throw FinanceFailure("INVALID_AMOUNT")
        val prior=db.rawQuery("SELECT id,name,modality,opening_cents,created_on FROM finance_reserves WHERE command_id=?",arrayOf(command)).use { if(it.moveToFirst()) mapOf("id" to it.getString(0),"name" to it.getString(1),"modality" to it.getString(2),"amount" to it.getLong(3),"date" to it.getString(4)) else null }
        if(prior!=null) { if(prior["name"]!=name||prior["modality"]!=modality||prior["amount"]!=amount||prior["date"]!=date) throw FinanceFailure("CONFIRMATION_CONFLICT");return@atomic prior.getValue("id") as String }
        val id=UUID.randomUUID().toString();db.execSQL("INSERT INTO finance_reserves VALUES(?,?,?,?,?,?,?)",arrayOf(id,command,name,modality,amount,date,date))
        if(amount>0) recordExtended(command,"reserve_opening",amount,date,"Abertura da caixinha: $name",resourceId=id,resourceName=name,direction="increase",request=value,legs=mapOf("reserve:$id" to amount,"equity" to -amount))
        id
    }
    fun createCard(json:String):String=atomic {
        ensureTracking();val value=resourceRoot(json,setOf("name","closingDay","dueDay"));val command=commandId(value);val name=resourceText(string(value,"name"),80)
        val close=value.optInt("closingDay",0);val due=value.optInt("dueDay",0);if(name.isBlank()||close !in 1..28||due !in 1..28) throw FinanceFailure("INVALID_FIELDS")
        val prior=db.rawQuery("SELECT id,name,closing_day,due_day FROM finance_cards WHERE command_id=?",arrayOf(command)).use { if(it.moveToFirst()) listOf(it.getString(0),it.getString(1),it.getInt(2).toString(),it.getInt(3).toString()) else null }
        if(prior!=null) { if(prior[1]!=name||prior[2]!=close.toString()||prior[3]!=due.toString()) throw FinanceFailure("CONFIRMATION_CONFLICT");return@atomic prior[0] }
        val id=UUID.randomUUID().toString();db.execSQL("INSERT INTO finance_cards VALUES(?,?,?,?,?)",arrayOf(id,command,name,close,due));id
    }
    fun moveReserve(json:String):String=atomic {
        ensureTracking();val value=resourceRoot(json,setOf("reserveId","direction","amountCents","date"));val id=string(value,"reserveId");val direction=string(value,"direction")
        if(direction !in setOf("deposit","withdraw")) throw FinanceFailure("INVALID_FIELDS")
        val reserve=rows("finance_reserves").firstOrNull { it["id"]==id } ?: throw FinanceFailure("NOT_FOUND");val amount=FinanceRules.cents(value.get("amountCents"));val date=string(value,"date");currentDate(date)
        if(date<(reserve["created_on"] as String)) throw FinanceFailure("DATE_BEFORE_OPENING")
        val description=if(direction=="deposit") "Guardar em ${reserve["name"]}" else "Resgatar de ${reserve["name"]}"
        replayExtended(commandId(value),normalizedExtendedRequest(value,amount,"reserve_move",date,description,null,"",emptyList()))?.let { return@atomic it }
        val signed=if(direction=="deposit") amount else -amount;val balance=FinanceRules.sum(listOf(reserveBalance(id,date),signed))
        if(balance<0) throw FinanceFailure("INSUFFICIENT_FUNDS")
        val transactionId=recordExtended(commandId(value),"reserve_move",amount,date,description,resourceId=id,resourceName=reserve["name"] as String,direction=direction,request=value,legs=mapOf("cash" to -signed,"reserve:$id" to signed))
        db.execSQL("UPDATE finance_reserves SET updated_on=? WHERE id=?",arrayOf(date,id));transactionId
    }
    fun transferReserve(json:String):String=atomic {
        ensureTracking();val value=resourceRoot(json,setOf("fromReserveId","toReserveId","amountCents","date"));val from=string(value,"fromReserveId");val to=string(value,"toReserveId")
        if(from==to) throw FinanceFailure("INVALID_FIELDS")
        val source=rows("finance_reserves").firstOrNull { it["id"]==from } ?: throw FinanceFailure("NOT_FOUND");val target=rows("finance_reserves").firstOrNull { it["id"]==to } ?: throw FinanceFailure("NOT_FOUND")
        val amount=FinanceRules.cents(value.get("amountCents"));val date=string(value,"date");currentDate(date);if(date<(source["created_on"] as String)||date<(target["created_on"] as String)) throw FinanceFailure("DATE_BEFORE_OPENING");val description="Transferência: ${source["name"]} → ${target["name"]}"
        replayExtended(commandId(value),normalizedExtendedRequest(value,amount,"transfer",date,description,null,"",emptyList()))?.let { return@atomic it }
        if(reserveBalance(from,date)<amount) throw FinanceFailure("INSUFFICIENT_FUNDS");FinanceRules.sum(listOf(reserveBalance(to,date),amount))
        val id=recordExtended(commandId(value),"transfer",amount,date,description,resourceId=from,resourceName=source["name"] as String,direction="reserve_transfer",request=value,legs=mapOf("reserve:$from" to -amount,"reserve:$to" to amount))
        db.execSQL("UPDATE finance_reserves SET updated_on=? WHERE id IN (?,?)",arrayOf(date,from,to));id
    }
    fun updateReserve(json:String):String=atomic {
        ensureTracking();val value=resourceRoot(json,setOf("reserveId","amountCents","date","note"));val id=string(value,"reserveId");val reserve=rows("finance_reserves").firstOrNull { it["id"]==id } ?: throw FinanceFailure("NOT_FOUND")
        val target=FinanceRules.cents(value.get("amountCents"),true);if(target<0) throw FinanceFailure("INVALID_AMOUNT")
        val date=string(value,"date");currentDate(date);val note=FinanceRules.text(string(value,"note").trim(),500);if(note.isBlank()) throw FinanceFailure("INVALID_FIELDS")
        if(date<(reserve["created_on"] as String)) throw FinanceFailure("DATE_BEFORE_OPENING")
        val command=commandId(value);val replay=replayInput(command,JSONObject(value.toString()).put("targetBalanceCents",target),setOf("reserveId","date","note","targetBalanceCents"));if(replay!=null) return@atomic replay
        val current=reserveBalance(id,date);val delta=FinanceRules.sum(listOf(target,-current));if(delta==0L) throw FinanceFailure("INVALID_FIELDS")
        val direction=if(delta>0) "increase" else "decrease";val request=JSONObject(value.toString()).put("targetBalanceCents",target)
        val transactionId=recordExtended(command,"reserve_adjustment",kotlin.math.abs(delta),date,note.ifBlank { "Ajuste da caixinha" },resourceId=id,resourceName=reserve["name"] as String,direction=direction,request=request,legs=mapOf("reserve:$id" to delta,"equity" to -delta))
        db.execSQL("UPDATE finance_reserves SET updated_on=? WHERE id=?",arrayOf(date,id));transactionId
    }
    fun createCommitment(json:String):String=atomic {
        ensureTracking();val value=resourceRoot(json,setOf("description","dueDate","amountCents","cardId"));val command=commandId(value);val description=resourceText(string(value,"description"),500);val due=FinanceRules.date(string(value,"dueDate"));val amount=FinanceRules.cents(value.get("amountCents"))
        val cardId=if(value.isNull("cardId")) null else string(value,"cardId");val card=cardId?.let { rows("finance_cards").firstOrNull { row -> row["id"]==it } ?: throw FinanceFailure("NOT_FOUND") }
        val prior=db.rawQuery("SELECT id,description,due_date,amount,card_id FROM finance_commitments WHERE command_id=?",arrayOf(command)).use { if(it.moveToFirst()) mapOf("id" to it.getString(0),"description" to it.getString(1),"dueDate" to it.getString(2),"amount" to it.getLong(3),"cardId" to it.getString(4)) else null }
        if(prior!=null) { if(prior["description"]!=description||prior["dueDate"]!=due||prior["amount"]!=amount||prior["cardId"]!=cardId) throw FinanceFailure("CONFIRMATION_CONFLICT");return@atomic prior.getValue("id") as String }
        val id=UUID.randomUUID().toString();db.execSQL("INSERT INTO finance_commitments VALUES(?,?,?,?,?,?,?)",arrayOf(id,command,cardId,description,due,null,amount))
        recordExtended(command,"commitment_opening",amount,due,description,resourceId=cardId,resourceName=card?.get("name") as? String,commitmentId=id,request=value,legs=mapOf("liability:$id" to -amount,"equity" to amount));id
    }
    private fun invoiceMonth(date:String,closingDay:Int):String {
        val parsed=LocalDate.parse(FinanceRules.date(date));return (if(parsed.dayOfMonth>closingDay) parsed.plusMonths(1) else parsed).toString().take(7)
    }
    private fun invoiceDue(month:String,closing:Int,due:Int):String { val m=java.time.YearMonth.parse(FinanceRules.month(month));return (if(due<=closing)m.plusMonths(1) else m).atDay(due).toString() }
    fun cardPurchase(json:String):String=atomic {
        ensureTracking();val value=resourceRoot(json,setOf("cardId","amountCents","effectiveDate","description","category","categoryId","tagIds","notes"));val command=commandId(value);val cardId=string(value,"cardId")
        val card=rows("finance_cards").firstOrNull { it["id"]==cardId } ?: throw FinanceFailure("NOT_FOUND");val amount=FinanceRules.cents(value.get("amountCents"));val date=string(value,"effectiveDate");currentDate(date)
        val description=resourceText(string(value,"description"),500);val categoryId=if(value.isNull("categoryId")) null else string(value,"categoryId");val category=resourceText(string(value,"category"),80);FinanceRules.text(string(value,"notes"),500);val tags=value.getJSONArray("tagIds");val tagIds=(0 until tags.length()).map { tags.getString(it) }.distinct().sorted();if(tagIds.size!=tags.length()||tagIds.size>20) throw FinanceFailure("INVALID_CLASSIFICATION")
        if(categoryId!=null && catalog().none { it["id"]==categoryId&&it["kind"]=="category"&&it["archived"]==false }) throw FinanceFailure("INVALID_CLASSIFICATION")
        if(tagIds.any { tag->catalog().none { it["id"]==tag&&it["kind"]=="tag"&&it["archived"]==false } }) throw FinanceFailure("INVALID_CLASSIFICATION")
        val closing=(card["closing_day"] as Long).toInt();val dueDay=(card["due_day"] as Long).toInt();val month=invoiceMonth(date,closing);val due=invoiceDue(month,closing,dueDay)
        val req=JSONObject(value.toString()).put("invoiceMonth",month).put("cardId",cardId)
        replayExtended(command,normalizedExtendedRequest(req,amount,"card_purchase",date,description,categoryId,category,tagIds))?.let { return@atomic it }
        val commitmentRow=rows("finance_commitments").firstOrNull { it["card_id"]==cardId&&it["invoice_month"]==month }
        val commitmentId=if(commitmentRow!=null) commitmentRow["id"] as String else UUID.randomUUID().toString()
        if(commitmentRow==null) db.execSQL("INSERT INTO finance_commitments VALUES(?,?,?,?,?,?,?)",arrayOf(commitmentId,"invoice:$cardId:$month",cardId,"Fatura ${card["name"]} $month",due,month,amount))
        else db.execSQL("UPDATE finance_commitments SET amount=? WHERE id=?",arrayOf(FinanceRules.sum(listOf(commitmentRow["amount"] as Long,amount)),commitmentId))
        recordExtended(command,"card_purchase",amount,date,description,categoryId,category,cardId,card["name"] as String,commitmentId,month,null,req,mapOf("expense" to amount,"liability:$commitmentId" to -amount),tagIds)
    }
    fun payCommitment(json:String):String=atomic {
        ensureTracking();val value=resourceRoot(json,setOf("commitmentId","amountCents","date"));val commitmentId=string(value,"commitmentId");val commitment=rows("finance_commitments").firstOrNull { it["id"]==commitmentId } ?: throw FinanceFailure("NOT_FOUND");val amount=FinanceRules.cents(value.get("amountCents"));val date=string(value,"date");currentDate(date)
        val description="Pagamento: ${commitment["description"]}"
        replayExtended(commandId(value),normalizedExtendedRequest(value,amount,"card_payment",date,description,null,"",emptyList()))?.let { return@atomic it }
        val paid=FinanceRules.sum(rows("finance_extended_transactions").filter { it["kind"]=="card_payment"&&it["commitment_id"]==commitmentId&&it["id"] !in rows("finance_extended_cancellations").map { c->c["transaction_id"] } }.map { it["amount"] as Long })
        if(FinanceRules.sum(listOf(paid,amount))>(commitment["amount"] as Long)) throw FinanceFailure("AMOUNT_LIMIT")
        val card=commitment["card_id"]?.let { id->rows("finance_cards").firstOrNull { it["id"]==id } }
        recordExtended(commandId(value),"card_payment",amount,date,description,resourceId=commitment["card_id"] as? String,resourceName=card?.get("name") as? String,commitmentId=commitmentId,invoiceMonth=commitment["invoice_month"] as? String,request=value,legs=mapOf("cash" to -amount,"liability:$commitmentId" to amount))
    }
    fun listMetadataRules()=MetadataRules.listRules(db)
    fun saveMetadataRule(json:String)=atomic { ensureTracking();MetadataRules.saveRule(db,json) }
    fun setMetadataRuleEnabled(id:String,enabled:Boolean)=atomic { ensureTracking();MetadataRules.setRuleEnabled(db,id,enabled) }
    fun previewMetadataRule(id:String)=MetadataRules.previewRule(db,id)
    fun suggestMetadata(description:String)=MetadataRules.suggestion(db,description)
    fun reclassifyMetadata(json:String):Map<String,Any?> = atomic {
        if(json.length>2*1024*1024) throw FinanceFailure("INVALID_FIELDS")
        val parsed=try { JSONObject(json) } catch (_:Exception) { throw FinanceFailure("INVALID_FIELDS") };val root=if(parsed.keys().asSequence().toSet() in setOf(setOf("items"),setOf("items","ruleId"))) parsed else throw FinanceFailure("INVALID_FIELDS");val arr=root.getJSONArray("items");if(arr.length() !in 1..1000) throw FinanceFailure("INVALID_FIELDS")
        val changes=(0 until arr.length()).map { index->val item=arr.getJSONObject(index);if(item.keys().asSequence().toSet() !in setOf(setOf("transactionId","categoryId","category","tagIds"),setOf("transactionId","scope","categoryId","category","tagIds"))) throw FinanceFailure("INVALID_FIELDS");MetadataRules.Change(string(item,"transactionId"),if(item.isNull("categoryId")) null else string(item,"categoryId"),string(item,"category"),(0 until item.getJSONArray("tagIds").length()).map { item.getJSONArray("tagIds").getString(it) },item.optString("scope","finance")) }
        val ruleId=if(root.isNull("ruleId")) null else string(root,"ruleId");mapOf("updated" to MetadataRules.reclassify(db,changes,ruleId))
    }
    fun recordBalance(json:String)=atomic {
        val value=root(json,setOf("date","amountCents"));val date=string(value,"date");currentDate(date)
        if(date<profile().first) throw FinanceFailure("DATE_BEFORE_OPENING")
        val amount=FinanceRules.cents(value.get("amountCents"),true)
        if(rows("finance_balance_checks").size>=1000) throw FinanceFailure("TRANSACTION_LIMIT")
        db.execSQL("INSERT INTO finance_balance_checks VALUES(?,?,?,?)",arrayOf<Any>(UUID.randomUUID().toString(),date,amount,Instant.now().toString()))
    }
    /** Voids legacy income/expense records. Resource/card corrections need compensating ledger entries; those are not exposed yet. */
    fun cancel(json:String)=atomic {
        val value=root(json,setOf("id","reason"));val id=string(value,"id");val reason=FinanceRules.text(string(value,"reason").trim(),500)
        if(reason.isEmpty()) throw FinanceFailure("INVALID_FIELDS")
        val transaction=rows("finance_transactions").firstOrNull { it["id"]==id } ?: if(rows("finance_extended_transactions").any { it["id"]==id }) throw FinanceFailure("UNSUPPORTED_MOVEMENT") else throw FinanceFailure("NOT_FOUND")
        if(transaction["kind"]=="opening") throw FinanceFailure("INVALID_FIELDS")
        val existing=rows("finance_cancellations").firstOrNull { it["transaction_id"]==id }
        if(existing!=null) { if(existing["reason"]!=reason) throw FinanceFailure("CONFIRMATION_CONFLICT");return@atomic }
        db.execSQL("INSERT INTO finance_cancellations VALUES(?,?,?)",arrayOf(id,reason,Instant.now().toString()))
    }
    /** Fixed table names only; read integers as Long, preserving exact cents across JSON. */
    fun rows(table:String):List<Map<String,Any?>> {
        check(table in tables)
        return db.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use { cursor -> buildList {
            while(cursor.moveToNext()) add(cursor.columnNames.mapIndexed { index,name -> name to when(cursor.getType(index)) {
                android.database.Cursor.FIELD_TYPE_NULL -> null
                android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                else -> throw FinanceFailure("INVALID_STATE")
            } }.toMap())
        } }
    }
    fun snapshot(month:String):Map<String,Any?> {
        FinanceRules.month(month);validate()
        val transactions=rows("finance_transactions");val tags=rows("finance_tags");val extended=rows("finance_extended_transactions");val extTags=rows("finance_extended_tags");val extEntries=rows("finance_extended_entries");val options=catalog().associateBy { it["id"] }
        val cancelled=rows("finance_cancellations").associateBy { it["transaction_id"] };val extCancelled=rows("finance_extended_cancellations").associateBy { it["transaction_id"] }
        val active=transactions.filter { it["id"] !in cancelled };val activeExtended=extended.filter { it["id"] !in extCancelled }
        val profile=if(configured()) profile() else null
        fun amount(row:Map<String,Any?>)=row["amount"] as Long
        val observedLinks=(rows("finance_observation_links")+rows("finance_extended_observation_links")).associateBy { it["transaction_id"] }
        fun sourceType(row:Map<String,Any?>):String {
            val sourceJson=(row["source_json"] as? String) ?: (observedLinks[row["id"]]?.get("source_json") as? String)
            if(sourceJson==null) return if(row["kind"]=="card_purchase") "card" else "manual"
            return if(JSONObject(sourceJson).optString("packageName")=="org.tique.statement") "statement" else "notification"
        }
        fun sourceEvent(row:Map<String,Any?>)=row["source_event_id"] ?: observedLinks[row["id"]]?.get("event_id")
        val allLegacy=transactions.filter { it["kind"]!="opening" && (it["effective_date"] as String).startsWith(month) }
        val allExt=extended.filter { (it["effective_date"] as String).startsWith(month) }
        val current=allLegacy.filter { it["id"] !in cancelled };val currentExt=allExt.filter { it["id"] !in extCancelled }
        val income=FinanceRules.sum(current.filter { it["kind"]=="income" }.map(::amount))
        val expense=FinanceRules.sum(current.filter { it["kind"]=="expense" }.map(::amount)+currentExt.filter { it["kind"]=="card_purchase" }.map(::amount))
        fun category(row:Map<String,Any?>)=options[row["category_id"]]?.get("name") ?: row["category_legacy"]
        val extCashByTransaction=extEntries.filter { it["resource"]=="cash" }.associate { it["transaction_id"] to (it["cents"] as Long) }
        fun balance(until:String?=null)=FinanceRules.sum(active.filter { until==null || (it["effective_date"] as String)<=until }.map {
            when(it["kind"]) { "opening" -> profile!!.second;"income" -> amount(it);else -> -amount(it) }
        }+activeExtended.filter { until==null || (it["effective_date"] as String)<=until }.mapNotNull { extCashByTransaction[it["id"]] })
        val check=rows("finance_balance_checks").lastOrNull()?.let { row ->
            val calculated=balance(row["effective_date"] as String);val observed=row["amount"] as Long
            mapOf("date" to row["effective_date"],"amountCents" to observed,"calculatedCents" to calculated,"differenceCents" to FinanceRules.sum(listOf(observed,-calculated)))
        }
        val legacyMaps=allLegacy.map { row->val tagIds=tags.filter { it["transaction_id"]==row["id"] }.map { it["tag_id"] }; mapOf("id" to row["id"],"scope" to "finance","kind" to row["kind"],"amountCents" to row["amount"],"effectiveDate" to row["effective_date"],"description" to row["description"],"categoryId" to row["category_id"],"category" to category(row),"tagIds" to tagIds,"tags" to tagIds.map { options[it]?.get("name") },"sourceEventId" to sourceEvent(row),"sourceType" to sourceType(row),"voided" to (row["id"] in cancelled),"cancellationReason" to cancelled[row["id"]]?.get("reason"),"resourceId" to null,"resourceName" to null,"targetResourceId" to null,"targetResourceName" to null,"commitmentId" to null,"invoiceMonth" to null,"direction" to null) }
        val extMaps=allExt.map { row->val tagIds=extTags.filter { it["transaction_id"]==row["id"] }.map { it["tag_id"] };val request=JSONObject(row["request_json"] as String);val targetId=request.optString("toReserveId").takeIf { it.isNotEmpty() };val targetName=targetId?.let { target->reservesName(target) };mapOf("id" to row["id"],"scope" to "extended","kind" to row["kind"],"amountCents" to row["amount"],"effectiveDate" to row["effective_date"],"description" to row["description"],"categoryId" to row["category_id"],"category" to category(row),"tagIds" to tagIds,"tags" to tagIds.map { options[it]?.get("name") },"sourceEventId" to sourceEvent(row),"sourceType" to sourceType(row),"voided" to (row["id"] in extCancelled),"cancellationReason" to extCancelled[row["id"]]?.get("reason"),"resourceId" to row["resource_id"],"resourceName" to row["resource_name"],"targetResourceId" to targetId,"targetResourceName" to targetName,"commitmentId" to row["commitment_id"],"invoiceMonth" to row["invoice_month"],"direction" to row["direction"]) }
        val allCurrent=legacyMaps+extMaps
        val expenseRows=current.filter { it["kind"]=="expense" }.map { it+mapOf("scope" to "finance") }+currentExt.filter { it["kind"]=="card_purchase" }.map { it+mapOf("scope" to "extended") }
        val byCategory=expenseRows.groupBy { if(it["category_id"]==null) "label:${it["category_legacy"]}" else "id:${it["category_id"]}" }.values.map { group ->
                mapOf("categoryId" to group.first()["category_id"],"name" to category(group.first()),"amountCents" to FinanceRules.sum(group.map(::amount)))
            }
        val reserveRows=rows("finance_reserves");val reserveSums=extEntries.filter { (it["resource"] as String).startsWith("reserve:") }.groupBy { (it["resource"] as String).removePrefix("reserve:") }.mapValues { (_,legs)->FinanceRules.sum(legs.map { it["cents"] as Long }) }
        val reserves=reserveRows.map { reserve->mapOf("id" to reserve["id"],"name" to reserve["name"],"modality" to reserve["modality"],"balanceCents" to (reserveSums[reserve["id"]] ?: 0L),"updatedOn" to reserve["updated_on"]) }
        val commitmentRows=rows("finance_commitments");val cardRows=rows("finance_cards");val cardNames=cardRows.associate { it["id"] to it["name"] };val paidSums=activeExtended.filter { it["kind"]=="card_payment" }.groupBy { it["commitment_id"] }.mapValues { (_,payments)->FinanceRules.sum(payments.map { it["amount"] as Long }) }
        val commitments=commitmentRows.mapNotNull { item->val paid=paidSums[item["id"]] ?: 0L;val remaining=(item["amount"] as Long)-paid;if(remaining<=0) null else mapOf("id" to item["id"],"description" to item["description"],"dueDate" to item["due_date"],"amountCents" to item["amount"],"paidCents" to paid,"remainingCents" to remaining,"cardId" to item["card_id"],"cardName" to cardNames[item["card_id"]],"invoiceMonth" to item["invoice_month"]) }
        val commitmentsByCard=commitmentRows.groupBy { it["card_id"] }
        val cards=cardRows.map { card->val debt=FinanceRules.sum(commitmentsByCard[card["id"]].orEmpty().map { commitment->(commitment["amount"] as Long)-(paidSums[commitment["id"]] ?: 0L) });mapOf("id" to card["id"],"name" to card["name"],"closingDay" to card["closing_day"],"dueDay" to card["due_day"],"debtCents" to debt) }
        val reservesCents=FinanceRules.sum(reserves.map { it["balanceCents"] as Long });val debtCents=FinanceRules.sum(commitments.map { it["remainingCents"] as Long })
        val ordered=allCurrent.sortedWith(compareByDescending<Map<String,Any?>> { it["effectiveDate"] as String }.thenByDescending { it["createdAt"] as? String ?: "" })
        return mapOf("configured" to (profile!=null),"profile" to profile?.let { mapOf("openingDate" to it.first,"openingBalanceCents" to it.second) },
            "balanceCents" to balance(),"month" to month,"incomeCents" to income,"expenseCents" to expense,"total" to allCurrent.size,
            "assetsCents" to FinanceRules.sum(listOf(balance(),reservesCents)),"debtCents" to debtCents,"reservesCents" to reservesCents,"reserves" to reserves,"cards" to cards,"commitments" to commitments,
            "byCategory" to byCategory,"transactions" to ordered,"balanceCheck" to check)
    }
    /** Also used to reject malformed restored books before activating them. */
    fun validate() {
        val profile=rows("finance_profile");val transactions=rows("finance_transactions");val entries=rows("finance_entries");val tags=rows("finance_tags");val options=catalog().associateBy { it["id"] }
        val cancellations=rows("finance_cancellations");val cancelled=cancellations.map { it["transaction_id"] }.toSet()
        if(profile.size>1 || transactions.size>10_000 || rows("finance_balance_checks").size>1000) throw FinanceFailure("INVALID_STATE")
        if(profile.isEmpty()) {
            val financialTables=listOf("finance_transactions","finance_entries","finance_tags","finance_balance_checks","finance_cancellations","finance_observation_links",
                "finance_reserves","finance_cards","finance_commitments","finance_extended_transactions","finance_extended_entries","finance_extended_tags","finance_extended_cancellations","finance_extended_observation_links","finance_metadata_rules","finance_metadata_audit")
            if(financialTables.any { rows(it).isNotEmpty() }) throw FinanceFailure("INVALID_STATE")
            return
        }
        val start=FinanceRules.date(profile.single()["opening_date"] as String);val initial=FinanceRules.cents(profile.single()["opening_cents"],true)
        if(transactions.count { it["kind"]=="opening" }!=1) throw FinanceFailure("INVALID_STATE")
        val totals=mutableListOf<Long>();val groupedEntries=entries.groupBy { it["transaction_id"] };val groupedTags=tags.groupBy { it["transaction_id"] }
        val transactionIds=transactions.map { it["id"] }.toSet()
        for(row in transactions) {
            val date=FinanceRules.date(row["effective_date"] as String)
            val opening=row["kind"]=="opening";val amount=FinanceRules.cents(row["amount"],signed=opening)
            val cash=when(row["kind"]) { "opening" -> initial;"income" -> amount;"expense" -> -amount;else -> throw FinanceFailure("INVALID_STATE") }
            if(opening && (date!=start || amount!=kotlin.math.abs(initial) || row["command_key"]!="opening" || row["request_json"]!="{}" || row["source_event_id"]!=null || row["source_json"]!=null || row["category_id"]!=null || row["category_legacy"]!="" || row["description"]!="Saldo de abertura" || !groupedTags[row["id"]].isNullOrEmpty())) throw FinanceFailure("INVALID_STATE")
            val legs=groupedEntries[row["id"]].orEmpty()
            if(legs.size!=2 || legs.singleOrNull { it["resource"]=="cash" }?.get("cents")!=cash || legs.singleOrNull { it["resource"]==row["kind"] }?.get("cents")!=-cash) throw FinanceFailure("UNBALANCED_ENTRIES")
            val category=row["category_id"];if(category!=null && options[category]?.get("kind")!="category") throw FinanceFailure("INVALID_STATE")
            FinanceRules.text(row["description"] as String,500);FinanceRules.text(row["category_legacy"] as String,80);Instant.parse(row["created_at"] as String)
            if(!opening) {
                val request=JSONObject(row["request_json"] as String);ClassificationReview.validate(request)
                if(request.keys().asSequence().toSet()!=setOf("amountCents","kind","effectiveDate","description","category","categoryId","tagIds")) throw FinanceFailure("INVALID_STATE")
                if(FinanceRules.cents(request.get("amountCents"))!=amount || request.getString("kind")!=row["kind"] || request.getString("effectiveDate")!=date || request.getString("description")!=row["description"] || (if(request.isNull("categoryId")) null else request.getString("categoryId"))!=category) throw FinanceFailure("INVALID_STATE")
                val selected=groupedTags[row["id"]].orEmpty().map { it["tag_id"] }.toSet()
                val selectedJson=request.getJSONArray("tagIds");if(selected!=(0 until selectedJson.length()).map { selectedJson.getString(it) }.toSet()) throw FinanceFailure("INVALID_STATE")
                if(row["source_event_id"]==null) {
                    val key=row["command_key"] as String
                    if(!key.startsWith("manual:") || UUID.fromString(key.removePrefix("manual:")).toString()!=key.removePrefix("manual:") || row["source_json"]!=null) throw FinanceFailure("INVALID_STATE")
                }
                else {
                    val source=JSONObject(row["source_json"] as String)
                    validateSource(source)
                    if(source.getString("eventId")!=row["source_event_id"] || row["command_key"]!="notification:${row["source_event_id"]}") throw FinanceFailure("INVALID_SOURCE")
                }
            }
            if(row["id"] !in cancelled) totals.add(cash)
        }
        for(row in cancellations) {
            if(transactions.none { it["id"]==row["transaction_id"] && it["kind"]!="opening" } || FinanceRules.text(row["reason"] as String,500).isBlank()) throw FinanceFailure("INVALID_STATE")
            Instant.parse(row["cancelled_at"] as String)
        }
        val linked=rows("finance_observation_links")
        if(linked.size>10_000) throw FinanceFailure("TRANSACTION_LIMIT")
        for(link in linked) {
            val event=link["event_id"] as String
            if(UUID.fromString(event).toString()!=event || link["transaction_id"] !in transactionIds || transactions.any { it["source_event_id"]==event }) throw FinanceFailure("INVALID_STATE")
            val source=JSONObject(link["source_json"] as String);validateSource(source)
            if(source.getString("eventId")!=event) throw FinanceFailure("INVALID_SOURCE")
        }
        val active=transactions.filter { it["id"] !in cancelled }
        if(entries.size!=transactions.size*2 || tags.any { options[it["tag_id"]]?.get("kind")!="tag" || it["transaction_id"] !in transactionIds }) throw FinanceFailure("INVALID_STATE")
        FinanceRules.sum(totals)
        for(group in active.filter { it["kind"]!="opening" }.groupBy { (it["effective_date"] as String).take(7)+it["kind"] }.values) FinanceRules.sum(group.map { it["amount"] as Long })
        // Check every day's cumulative balance, not only the final total.
        var accumulated=0L
        for(day in active.groupBy { it["effective_date"] as String }.toSortedMap().values) { accumulated=FinanceRules.sum(listOf(accumulated)+day.map { when(it["kind"]) { "opening" -> initial;"income" -> it["amount"] as Long;else -> -(it["amount"] as Long) } }) }
        for(check in rows("finance_balance_checks")) {
            val date=FinanceRules.date(check["effective_date"] as String);if(date<start) throw FinanceFailure("INVALID_STATE")
            val observed=FinanceRules.cents(check["amount"],true);val balance=FinanceRules.sum(active.filter { (it["effective_date"] as String)<=date }.map { when(it["kind"]) { "opening" -> initial;"income" -> it["amount"] as Long;else -> -(it["amount"] as Long) } })
            FinanceRules.sum(listOf(observed,-balance));Instant.parse(check["created_at"] as String)
        }
        val reserves=rows("finance_reserves");val cards=rows("finance_cards");val commitments=rows("finance_commitments");val extended=rows("finance_extended_transactions");val extEntries=rows("finance_extended_entries");val extTags=rows("finance_extended_tags");val extCancellations=rows("finance_extended_cancellations");val extLinks=rows("finance_extended_observation_links")
        if(reserves.size>1000||cards.size>1000||commitments.size>10_000||extended.size>20_000||extEntries.size>40_000||extTags.size>200_000||extCancellations.size>20_000||extLinks.size>20_000||rows("finance_metadata_rules").size>1000||rows("finance_metadata_audit").size>100_000) throw FinanceFailure("TRANSACTION_LIMIT")
        val reserveIds=reserves.map { it["id"] as String }.toSet();val cardIds=cards.map { it["id"] as String }.toSet();val commitmentIds=commitments.map { it["id"] as String }.toSet();val extIds=extended.map { it["id"] as String }.toSet();val extVoided=extCancellations.map { it["transaction_id"] }.toSet();val extById=extended.associateBy { it["id"] };val extEntryGroups=extEntries.groupBy { it["transaction_id"] };val extTagGroups=extTags.groupBy { it["transaction_id"] }
        if((reserveIds intersect transactionIds).isNotEmpty()||(cardIds intersect transactionIds).isNotEmpty()||(commitmentIds intersect extIds).isNotEmpty()||(extIds intersect transactionIds).isNotEmpty()) throw FinanceFailure("INVALID_STATE")
        for(reserve in reserves) {
            uuidCheck(reserve["id"]);uuidCheck(reserve["command_id"]);resourceText(reserve["name"] as String,80);resourceText(reserve["modality"] as String,40)
            val opening=FinanceRules.cents(reserve["opening_cents"],true);if(opening<0) throw FinanceFailure("INVALID_STATE");val created=FinanceRules.date(reserve["created_on"] as String);FinanceRules.date(reserve["updated_on"] as String)
            val entriesForReserve=extended.filter { it["kind"]=="reserve_opening"&&it["resource_id"]==reserve["id"] }
            if((opening==0L&&entriesForReserve.isNotEmpty())||(opening>0L&&(entriesForReserve.size!=1||entriesForReserve.single()["amount"]!=opening||entriesForReserve.single()["effective_date"]!=created))) throw FinanceFailure("INVALID_STATE")
        }
        for(card in cards) { uuidCheck(card["id"]);uuidCheck(card["command_id"]);FinanceRules.text(card["name"] as String,80);if((card["closing_day"] as Long) !in 1..28||(card["due_day"] as Long) !in 1..28) throw FinanceFailure("INVALID_STATE") }
        val purchaseTotals=extended.filter { it["kind"]=="card_purchase"&&it["id"] !in extVoided }.groupBy { it["commitment_id"] }.mapValues { (_,rows)->FinanceRules.sum(rows.map { it["amount"] as Long }) }
        val paymentTotals=extended.filter { it["kind"]=="card_payment"&&it["id"] !in extVoided }.groupBy { it["commitment_id"] }.mapValues { (_,rows)->FinanceRules.sum(rows.map { it["amount"] as Long }) }
        for(item in commitments) {
            uuidCheck(item["id"]);if(item["command_id"] !is String||item["command_id"].toString().length>128) throw FinanceFailure("INVALID_STATE")
            if(item["card_id"]!=null && item["card_id"] !in cardIds) throw FinanceFailure("INVALID_STATE")
            FinanceRules.text(item["description"] as String,500);val due=FinanceRules.date(item["due_date"] as String);val invoice=item["invoice_month"] as? String;if(invoice!=null&&FinanceRules.month(invoice)!=invoice) throw FinanceFailure("INVALID_STATE")
            val amount=FinanceRules.cents(item["amount"]);if(invoice!=null&&item["card_id"]==null) throw FinanceFailure("INVALID_STATE")
            val posted=purchaseTotals[item["id"]] ?: 0L
            if(invoice!=null&&posted!=amount) throw FinanceFailure("INVALID_STATE")
            val paid=paymentTotals[item["id"]] ?: 0L;if(paid>amount) throw FinanceFailure("INVALID_STATE")
            if(due.isBlank()) throw FinanceFailure("INVALID_STATE")
        }
        for(row in extended) {
            val id=row["id"] as String;uuidCheck(id);val kind=row["kind"] as String;val amount=FinanceRules.cents(row["amount"]);val date=FinanceRules.date(row["effective_date"] as String);val description=FinanceRules.text(row["description"] as String,500)
            FinanceRules.text(row["category_legacy"] as String,80);FinanceRules.text(row["resource_name"] as? String ?: "",80);Instant.parse(row["created_at"] as String)
            if(kind !in setOf("reserve_opening","reserve_move","reserve_adjustment","transfer","card_purchase","commitment_opening","card_payment")) throw FinanceFailure("INVALID_STATE")
            val request=JSONObject(row["request_json"] as String);val required=setOf("amountCents","kind","effectiveDate","description","category","categoryId","tagIds");val requestKeys=request.keys().asSequence().toSet()
            if(!requestKeys.containsAll(required)||request.getString("kind")!=kind||FinanceRules.cents(request.get("amountCents"))!=amount||request.getString("effectiveDate")!=date||request.getString("description")!=description) throw FinanceFailure("INVALID_STATE")
            val legs=extEntryGroups[id].orEmpty();if(legs.size<2||FinanceRules.sum(legs.map { it["cents"] as Long })!=0L) throw FinanceFailure("UNBALANCED_ENTRIES")
            val cat=row["category_id"];if(cat!=null&&options[cat]?.get("kind")!="category") throw FinanceFailure("INVALID_STATE")
            val tagsFor=extTagGroups[id].orEmpty().map { it["tag_id"] }.toSet();if(extTagGroups[id].orEmpty().size>20||tagsFor.any { options[it]?.get("kind")!="tag" }) throw FinanceFailure("INVALID_STATE")
            val requestTags=request.optJSONArray("tagIds")?.let { a->(0 until a.length()).map { a.getString(it) }.toSet() } ?: throw FinanceFailure("INVALID_STATE");if(requestTags!=tagsFor) throw FinanceFailure("INVALID_STATE")
            when(kind) {
                "reserve_opening" -> { val reserve=reserves.firstOrNull { it["id"]==row["resource_id"] };if(reserve==null||date!=reserve["created_on"]||reserve["opening_cents"]!=amount||legs.singleOrNull { it["resource"]=="reserve:${row["resource_id"]}" }?.get("cents")!=amount||legs.singleOrNull { it["resource"]=="equity" }?.get("cents")!=-amount) throw FinanceFailure("INVALID_STATE") }
                "reserve_move" -> { val direction=row["direction"];val reserve=reserves.firstOrNull { it["id"]==row["resource_id"] };if(reserve==null||date<(reserve["created_on"] as String)||direction !in setOf("deposit","withdraw")) throw FinanceFailure("INVALID_STATE");val signed=if(direction=="deposit") amount else -amount;if(legs.singleOrNull { it["resource"]=="reserve:${row["resource_id"]}" }?.get("cents")!=signed||legs.singleOrNull { it["resource"]=="cash" }?.get("cents")!=-signed) throw FinanceFailure("INVALID_STATE") }
                "reserve_adjustment" -> { val direction=row["direction"];val reserve=reserves.firstOrNull { it["id"]==row["resource_id"] };if(reserve==null||date<(reserve["created_on"] as String)||direction !in setOf("increase","decrease")) throw FinanceFailure("INVALID_STATE");val delta=if(direction=="increase") amount else -amount;if(legs.singleOrNull { it["resource"]=="reserve:${row["resource_id"]}" }?.get("cents")!=delta||legs.singleOrNull { it["resource"]=="equity" }?.get("cents")!=-delta) throw FinanceFailure("INVALID_STATE") }
                "transfer" -> { val from=request.optString("fromReserveId");val to=request.optString("toReserveId");val source=reserves.firstOrNull { it["id"]==from };val target=reserves.firstOrNull { it["id"]==to };if(row["direction"]!="reserve_transfer"||from==to||from!=row["resource_id"]||source==null||target==null||date<(source["created_on"] as String)||date<(target["created_on"] as String)||legs.singleOrNull { it["resource"]=="reserve:$from" }?.get("cents")!=-amount||legs.singleOrNull { it["resource"]=="reserve:$to" }?.get("cents")!=amount) throw FinanceFailure("INVALID_STATE") }
                "card_purchase" -> { if(row["resource_id"] !in cardIds||row["commitment_id"] !in commitmentIds||legs.singleOrNull { it["resource"]=="expense" }?.get("cents")!=amount||legs.singleOrNull { it["resource"]=="liability:${row["commitment_id"]}" }?.get("cents")!=-amount||row["invoice_month"] !is String) throw FinanceFailure("INVALID_STATE") }
                "commitment_opening" -> { if(row["commitment_id"] !in commitmentIds||legs.singleOrNull { it["resource"]=="liability:${row["commitment_id"]}" }?.get("cents")!=-amount||legs.singleOrNull { it["resource"]=="equity" }?.get("cents")!=amount) throw FinanceFailure("INVALID_STATE") }
                "card_payment" -> { if(row["commitment_id"] !in commitmentIds||legs.singleOrNull { it["resource"]=="cash" }?.get("cents")!=-amount||legs.singleOrNull { it["resource"]=="liability:${row["commitment_id"]}" }?.get("cents")!=amount) throw FinanceFailure("INVALID_STATE") }
            }
        }
        val extDate=extended.filter { it["id"] !in extVoided }.associate { it["id"] to (it["effective_date"] as String) }
        for(reserveId in reserveIds) {
            var balance=0L
            val dated=extEntries.filter { it["resource"]=="reserve:$reserveId"&&it["transaction_id"] in extDate }
                .groupBy { extDate[it["transaction_id"]] as String }.toSortedMap()
            for(day in dated.values) { balance=FinanceRules.sum(listOf(balance)+day.map { it["cents"] as Long });if(balance<0) throw FinanceFailure("INVALID_STATE") }
        }
        if(extEntries.any { it["transaction_id"] !in extIds }||extTags.any { it["transaction_id"] !in extIds }) throw FinanceFailure("INVALID_STATE")
        for(id in extVoided) { val row=extById[id] ?: throw FinanceFailure("INVALID_STATE");FinanceRules.text(extCancellations.single { it["transaction_id"]==id }["reason"] as String,500);if(row["kind"] in setOf("commitment_opening","card_purchase")) throw FinanceFailure("INVALID_STATE") }
        val legacyLinkEvents=rows("finance_observation_links").map { it["event_id"] }.toSet()
        for(link in extLinks) { val event=link["event_id"] as String;uuidCheck(event);if(link["transaction_id"] !in extIds||link["transaction_id"] in extVoided||event in legacyLinkEvents) throw FinanceFailure("INVALID_STATE");val source=JSONObject(link["source_json"] as String);validateSource(source);if(source.getString("eventId")!=event) throw FinanceFailure("INVALID_SOURCE") }
        val allEvents=transactions.mapNotNull { it["source_event_id"] }+legacyLinkEvents+extended.mapNotNull { it["source_event_id"] }+extLinks.map { it["event_id"] }
        if(allEvents.distinct().size!=allEvents.size) throw FinanceFailure("INVALID_STATE")
        val rules=rows("finance_metadata_rules");val audits=rows("finance_metadata_audit");val ruleIds=rules.map { it["id"] as String }.toSet()
        fun validateAuditTags(raw:Any?) {
            val json=try { JSONArray(raw as String) } catch (_:Exception) { throw FinanceFailure("INVALID_STATE") }
            if(json.length()>20) throw FinanceFailure("INVALID_STATE")
            val ids=(0 until json.length()).map { json.getString(it) };if(ids!=ids.distinct().sorted()) throw FinanceFailure("INVALID_STATE")
            for(tag in ids) if(options[tag]?.get("kind")!="tag") throw FinanceFailure("INVALID_STATE")
        }
        for(rule in rules) {
            uuidCheck(rule["id"]);FinanceRules.text(rule["description"] as String,500);if(MetadataRules.matchKey(rule["description"] as String)!=rule["match_key"]) throw FinanceFailure("INVALID_STATE")
            val categoryId=rule["category_id"];val label=FinanceRules.text(rule["category_legacy"] as String,80)
            if(categoryId!=null && options[categoryId]?.get("kind")!="category") throw FinanceFailure("INVALID_STATE")
            if(rule["enabled"] !in setOf(0L,1L)) throw FinanceFailure("INVALID_STATE");validateAuditTags(rule["tag_ids_json"]);Instant.parse(rule["created_at"] as String);Instant.parse(rule["updated_at"] as String)
        }
        for(audit in audits) {
            uuidCheck(audit["id"]);val scope=audit["transaction_scope"] as? String ?: throw FinanceFailure("INVALID_STATE");if(scope !in setOf("finance","extended")) throw FinanceFailure("INVALID_STATE")
            val txId=audit["transaction_id"] as String;uuidCheck(txId)
            if(scope=="finance" && transactions.none { it["id"]==txId&&it["kind"] in setOf("income","expense") } || scope=="extended" && extended.none { it["id"]==txId&&it["kind"]=="card_purchase" }) throw FinanceFailure("INVALID_STATE")
            val ruleId=audit["rule_id"];if(ruleId!=null) uuidCheck(ruleId)
            for(prefix in listOf("previous","next")) {
                val categoryId=audit["${prefix}_category_id"];val category=FinanceRules.text(audit["${prefix}_category"] as String,80)
                if(categoryId!=null && options[categoryId]?.get("kind")!="category") throw FinanceFailure("INVALID_STATE")
                validateAuditTags(audit["${prefix}_tag_ids_json"])
            }
            Instant.parse(audit["changed_at"] as String)
        }
        for(group in extended.filter { it["id"] !in extVoided }.groupBy { (it["effective_date"] as String).take(7)+it["kind"] }.values) FinanceRules.sum(group.map { it["amount"] as Long })
        FinanceRules.sum(reserves.map { reserveBalance(it["id"] as String) });FinanceRules.sum(cards.map { card->FinanceRules.sum(commitments.filter { it["card_id"]==card["id"] }.map { it["amount"] as Long }) })
    }
    private fun uuidCheck(value:Any?) { val text=value as? String ?: throw FinanceFailure("INVALID_STATE");if(runCatching { UUID.fromString(text).toString()==text }.getOrDefault(false)!=true) throw FinanceFailure("INVALID_STATE") }
    private fun validateSource(source:JSONObject) {
        val keys=setOf("eventId","packageName","sourceKey","revision","publishedAt","capturedAt","parserVersion","fieldEvidence","confidence")
        if(source.keys().asSequence().toSet()!=keys || source.getString("packageName") !in setOf(DiagnosticPolicy.PACKAGE,"org.tique.statement") || source.toString().length>4096) throw FinanceFailure("INVALID_SOURCE")
        val id=source.getString("eventId");if(UUID.fromString(id).toString()!=id) throw FinanceFailure("INVALID_SOURCE")
        val revision=source.get("revision");if((revision !is Int && revision !is Long) || (revision as Number).toLong()<1) throw FinanceFailure("INVALID_SOURCE")
        FinanceRules.text(source.getString("sourceKey"),1024);FinanceRules.text(source.getString("parserVersion"),80)
        Instant.parse(source.getString("publishedAt"));Instant.parse(source.getString("capturedAt"))
        val confidence=source.getJSONObject("confidence")
        if(confidence.keys().asSequence().toSet()!=setOf("movement","amount","nature","account","date","category") || confidence.keys().asSequence().any { confidence.get(it) !in setOf("certain","uncertain","missing") }) throw FinanceFailure("INVALID_SOURCE")
        val evidence=source.getJSONObject("fieldEvidence")
        if(evidence.keys().asSequence().any { it!="amountCents" }) throw FinanceFailure("INVALID_SOURCE")
        if(evidence.has("amountCents")) {
            val item=evidence.getJSONObject("amountCents")
            if(item.keys().asSequence().toSet()!=setOf("value","sourceField","observedText","start","end")) throw FinanceFailure("INVALID_SOURCE")
            FinanceRules.text(item.getString("value"),32);FinanceRules.text(item.getString("sourceField"),64);FinanceRules.text(item.getString("observedText"),256)
            if(item.get("start") !is Int || item.get("end") !is Int || item.getInt("start")<0 || item.getInt("end")<=item.getInt("start")) throw FinanceFailure("INVALID_SOURCE")
        }
    }
}
