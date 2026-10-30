package org.ffortuna.capture

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

/** One permanent BRL book, on the catalog owner's connection and serial executor. */
class FinancialLedger(private val db:SQLiteDatabase) {
    companion object {
        val tables=listOf("classifications","finance_profile","finance_transactions","finance_entries","finance_tags","finance_balance_checks","finance_cancellations")
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
        buildMap { while(rows.moveToNext()) put(rows.getString(0),rows.getString(1)) }
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
    private fun currentDate(date:String) {
        FinanceRules.date(date)
        if(date>LocalDate.now().toString()) throw FinanceFailure("FUTURE_DATE")
    }
    fun setup(json:String) = atomic {
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
    fun transactionForEvent(id:String):Map<String,Any?>?=db.rawQuery("SELECT id,request_json,category_legacy FROM finance_transactions WHERE source_event_id=?",arrayOf(id)).use {
        if(!it.moveToFirst()) null else {
            val fields=JSONObject(it.getString(1));val categoryId=if(fields.isNull("categoryId")) null else fields.getString("categoryId")
            val label=catalog().firstOrNull { option -> option["id"]==categoryId }?.get("name") ?: it.getString(2)
            fields.put("category",label)
            mapOf("id" to it.getString(0),"fields" to jsonMap(fields))
        }
    }
    fun post(json:String,source:Map<String,Any?>?=null):String = atomic {
        val opening=profile().first
        val manual=source==null
        val keys=setOf("amountCents","kind","effectiveDate","description","category","categoryId","tagIds")+(if(manual) setOf("commandId") else emptySet())
        val root=root(json,keys)
        val command=if(manual) string(root,"commandId").also { if(runCatching { UUID.fromString(it).toString()==it }.getOrDefault(false)!=true) throw FinanceFailure("INVALID_FIELDS") } else source!!["eventId"] as String
        val key=(if(manual) "manual:" else "notification:")+command
        val amount=FinanceRules.cents(root.get("amountCents"));val kind=string(root,"kind")
        if(kind !in setOf("income","expense")) throw FinanceFailure("UNSUPPORTED_MOVEMENT")
        val date=string(root,"effectiveDate");currentDate(date)
        if(date<opening) throw FinanceFailure("DATE_BEFORE_OPENING")
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
            return@atomic existing.first
        }
        if(rows("finance_transactions").size>=10_000) throw FinanceFailure("TRANSACTION_LIMIT")
        val id=UUID.randomUUID().toString();val sourceJson=source?.let { JSONObject(it).toString() }
        if(sourceJson!=null && sourceJson.length>4096) throw FinanceFailure("INVALID_SOURCE")
        db.execSQL("INSERT INTO finance_transactions VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(id,key,request,kind,amount,date,description,categoryId,category,Instant.now().toString(),if(manual) null else command,sourceJson))
        entries(id,if(kind=="income") amount else -amount,kind)
        for(tag in ids) db.execSQL("INSERT INTO finance_tags VALUES(?,?)",arrayOf(id,tag))
        id
    }
    fun recordBalance(json:String)=atomic {
        val value=root(json,setOf("date","amountCents"));val date=string(value,"date");currentDate(date)
        if(date<profile().first) throw FinanceFailure("DATE_BEFORE_OPENING")
        val amount=FinanceRules.cents(value.get("amountCents"),true)
        if(rows("finance_balance_checks").size>=1000) throw FinanceFailure("TRANSACTION_LIMIT")
        db.execSQL("INSERT INTO finance_balance_checks VALUES(?,?,?,?)",arrayOf<Any>(UUID.randomUUID().toString(),date,amount,Instant.now().toString()))
    }
    /** Voids an erroneous record, keeping its balanced pair, origin and reason for audit. Not a bank refund. */
    fun cancel(json:String)=atomic {
        val value=root(json,setOf("id","reason"));val id=string(value,"id");val reason=FinanceRules.text(string(value,"reason").trim(),500)
        if(reason.isEmpty()) throw FinanceFailure("INVALID_FIELDS")
        val transaction=rows("finance_transactions").firstOrNull { it["id"]==id } ?: throw FinanceFailure("NOT_FOUND")
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
        val transactions=rows("finance_transactions");val tags=rows("finance_tags");val options=catalog().associateBy { it["id"] }
        val cancelled=rows("finance_cancellations").associateBy { it["transaction_id"] }
        val active=transactions.filter { it["id"] !in cancelled }
        val profile=if(configured()) profile() else null
        fun amount(row:Map<String,Any?>)=row["amount"] as Long
        val allCurrent=transactions.filter { it["kind"]!="opening" && (it["effective_date"] as String).startsWith(month) }
        val current=allCurrent.filter { it["id"] !in cancelled }
        val income=FinanceRules.sum(current.filter { it["kind"]=="income" }.map(::amount))
        val expense=FinanceRules.sum(current.filter { it["kind"]=="expense" }.map(::amount))
        fun category(row:Map<String,Any?>)=options[row["category_id"]]?.get("name") ?: row["category_legacy"]
        fun balance(until:String?=null)=FinanceRules.sum(active.filter { until==null || (it["effective_date"] as String)<=until }.map {
            when(it["kind"]) { "opening" -> profile!!.second;"income" -> amount(it);else -> -amount(it) }
        })
        val check=rows("finance_balance_checks").lastOrNull()?.let { row ->
            val calculated=balance(row["effective_date"] as String);val observed=row["amount"] as Long
            mapOf("date" to row["effective_date"],"amountCents" to observed,"calculatedCents" to calculated,"differenceCents" to FinanceRules.sum(listOf(observed,-calculated)))
        }
        return mapOf("configured" to (profile!=null),"profile" to profile?.let { mapOf("openingDate" to it.first,"openingBalanceCents" to it.second) },
            "balanceCents" to balance(),"month" to month,"incomeCents" to income,"expenseCents" to expense,"total" to allCurrent.size,
            "byCategory" to current.filter { it["kind"]=="expense" }.groupBy { if(it["category_id"]==null) "label:${it["category_legacy"]}" else "id:${it["category_id"]}" }.values.map { group ->
                mapOf("categoryId" to group.first()["category_id"],"name" to category(group.first()),"amountCents" to FinanceRules.sum(group.map(::amount)))
            },"transactions" to allCurrent.sortedWith(compareByDescending<Map<String,Any?>> { it["effective_date"] as String }.thenByDescending { it["created_at"] as String }).map { row ->
                val tagIds=tags.filter { it["transaction_id"]==row["id"] }.map { it["tag_id"] }
                mapOf("id" to row["id"],"kind" to row["kind"],"amountCents" to row["amount"],"effectiveDate" to row["effective_date"],"description" to row["description"],
                    "categoryId" to row["category_id"],"category" to category(row),"tagIds" to tagIds,"tags" to tagIds.map { options[it]?.get("name") },"sourceEventId" to row["source_event_id"],"voided" to (row["id"] in cancelled),"cancellationReason" to cancelled[row["id"]]?.get("reason"))
            },"balanceCheck" to check)
    }
    /** Also used to reject malformed restored books before activating them. */
    fun validate() {
        val profile=rows("finance_profile");val transactions=rows("finance_transactions");val entries=rows("finance_entries");val tags=rows("finance_tags");val options=catalog().associateBy { it["id"] }
        val cancellations=rows("finance_cancellations");val cancelled=cancellations.map { it["transaction_id"] }.toSet()
        if(profile.size>1 || transactions.size>10_000 || rows("finance_balance_checks").size>1000) throw FinanceFailure("INVALID_STATE")
        if(profile.isEmpty()) { if(transactions.isNotEmpty() || entries.isNotEmpty() || tags.isNotEmpty() || cancellations.isNotEmpty() || rows("finance_balance_checks").isNotEmpty()) throw FinanceFailure("INVALID_STATE");return }
        val start=FinanceRules.date(profile.single()["opening_date"] as String);val initial=FinanceRules.cents(profile.single()["opening_cents"],true)
        if(transactions.count { it["kind"]=="opening" }!=1) throw FinanceFailure("INVALID_STATE")
        val totals=mutableListOf<Long>();val groupedEntries=entries.groupBy { it["transaction_id"] };val groupedTags=tags.groupBy { it["transaction_id"] }
        val transactionIds=transactions.map { it["id"] }.toSet()
        for(row in transactions) {
            val date=FinanceRules.date(row["effective_date"] as String);if(date<start) throw FinanceFailure("INVALID_STATE")
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
    }
    private fun validateSource(source:JSONObject) {
        val keys=setOf("eventId","packageName","sourceKey","revision","publishedAt","capturedAt","parserVersion","fieldEvidence","confidence")
        if(source.keys().asSequence().toSet()!=keys || source.getString("packageName")!=DiagnosticPolicy.PACKAGE || source.toString().length>4096) throw FinanceFailure("INVALID_SOURCE")
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
