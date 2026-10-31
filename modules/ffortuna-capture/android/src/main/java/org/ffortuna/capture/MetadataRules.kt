package org.ffortuna.capture

import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Deterministic description rules and metadata-only correction helpers for the existing finance DB. */
object MetadataRules {
    data class Change(
        val transactionId: String,
        val categoryId: String?,
        val category: String,
        val tagIds: List<String>,
        val scope: String = "finance"
    )

    fun createSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE finance_metadata_rules(
            id TEXT PRIMARY KEY NOT NULL,
            description TEXT NOT NULL,
            match_key TEXT NOT NULL UNIQUE,
            category_id TEXT REFERENCES classifications(id),
            category_legacy TEXT NOT NULL,
            tag_ids_json TEXT NOT NULL,
            enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE finance_metadata_audit(
            id TEXT PRIMARY KEY NOT NULL,
            transaction_scope TEXT NOT NULL,
            transaction_id TEXT NOT NULL,
            rule_id TEXT,
            previous_category_id TEXT,
            previous_category TEXT NOT NULL,
            previous_tag_ids_json TEXT NOT NULL,
            next_category_id TEXT,
            next_category TEXT NOT NULL,
            next_tag_ids_json TEXT NOT NULL,
            changed_at TEXT NOT NULL
        )""")
        db.execSQL("CREATE INDEX finance_metadata_audit_transaction ON finance_metadata_audit(transaction_scope,transaction_id,changed_at)")
    }

    /** Caller owns the DB transaction and backup boundary for every mutation. */
    fun listRules(db: SQLiteDatabase): List<Map<String, Any?>> = db.rawQuery(
        "SELECT id,description,category_id,category_legacy,tag_ids_json,enabled,created_at,updated_at FROM finance_metadata_rules ORDER BY updated_at DESC,id",
        null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(ruleMap(cursor, db))
    } }

    fun saveRule(db: SQLiteDatabase, json: String): Map<String, Any?> {
        val root = parseObject(json, 8192)
        val allowed = setOf("description", "categoryId", "category", "tagIds", "enabled")
        if (root.keys().asSequence().toSet() !in setOf(allowed, allowed + "id")) throw FinanceFailure("INVALID_FIELDS")
        val id = if (root.has("id") && !root.isNull("id")) uuid(root.opt("id")) else UUID.randomUUID().toString()
        val description = cleanDescription(root.opt("description"))
        val categoryId = optionalString(root, "categoryId")
        val category = cleanLabel(root.opt("category"))
        val tagIds = parseTags(root.opt("tagIds"))
        val enabled = if (root.has("enabled")) root.opt("enabled") as? Boolean ?: throw FinanceFailure("INVALID_FIELDS") else null
        val key = matchKey(description)
        val previous = findRule(db, id)
        val now = Instant.now().toString()
        validateMetadata(db, categoryId, category, tagIds, previous?.get("categoryId") as? String, previous?.get("tagIds") as? List<String>)
        val duplicate = db.rawQuery("SELECT id FROM finance_metadata_rules WHERE match_key=? AND id!=? LIMIT 1", arrayOf(key, id)).use { it.moveToFirst() }
        if (duplicate) throw FinanceFailure("RULE_DUPLICATE")
        val selectedEnabled = enabled ?: (previous?.get("enabled") as? Boolean ?: true)
        if (previous == null) {
            db.execSQL("INSERT INTO finance_metadata_rules VALUES(?,?,?,?,?,?,?,?,?)", arrayOf(id, description, key, categoryId, category, JSONArray(tagIds).toString(), if (selectedEnabled) 1 else 0, now, now))
        } else {
            db.execSQL("UPDATE finance_metadata_rules SET description=?,match_key=?,category_id=?,category_legacy=?,tag_ids_json=?,enabled=?,updated_at=? WHERE id=?",
                arrayOf(description, key, categoryId, category, JSONArray(tagIds).toString(), if (selectedEnabled) 1 else 0, now, id))
        }
        return findRule(db, id) ?: throw FinanceFailure("INVALID_STATE")
    }

    fun setRuleEnabled(db: SQLiteDatabase, id: String, enabled: Boolean): Map<String, Any?> {
        uuid(id)
        val current = findRule(db, id) ?: throw FinanceFailure("RULE_MISSING")
        if (current["enabled"] != enabled) {
            db.execSQL("UPDATE finance_metadata_rules SET enabled=?,updated_at=? WHERE id=?", arrayOf<Any>(if (enabled) 1 else 0, Instant.now().toString(), id))
        }
        return findRule(db, id) ?: throw FinanceFailure("INVALID_STATE")
    }

    /** Exact description suggestion for an unreviewed candidate; inactive options never auto-apply. */
    fun suggestion(db: SQLiteDatabase, description: String): Map<String, Any?>? {
        val key = try { matchKey(description) } catch (_: Exception) { return null }
        val id = db.rawQuery("SELECT id FROM finance_metadata_rules WHERE match_key=? AND enabled=1", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        val rule = findRule(db, id) ?: return null
        val categoryId = rule["categoryId"] as? String ?: return null
        val category = db.rawQuery("SELECT name,kind,archived FROM classifications WHERE id=?", arrayOf(categoryId)).use {
            if (it.moveToFirst() && it.getString(1) == "category" && it.getInt(2) == 0) it.getString(0) else null
        } ?: return null
        val tags = rule["tagIds"] as? List<String> ?: return null
        for (tag in tags) {
            val active = db.rawQuery("SELECT kind,archived FROM classifications WHERE id=?", arrayOf(tag)).use {
                it.moveToFirst() && it.getString(0) == "tag" && it.getInt(1) == 0
            }
            if (!active) return null
        }
        return mapOf("ruleId" to id, "description" to (rule["description"] as String), "categoryId" to categoryId,
            "category" to category, "tagIds" to tags)
    }

    /** Exact normalized description match; only live, confirmed income/expense records are returned. */
    fun previewRule(db: SQLiteDatabase, id: String): List<Map<String, Any?>> {
        uuid(id)
        val rule = findRule(db, id) ?: throw FinanceFailure("RULE_MISSING")
        val key = rule["matchKey"] as String
        val rows = mutableListOf<Map<String, Any?>>()
        val sources = listOf(
            Triple("finance", "finance_transactions", "finance_cancellations"),
            Triple("extended", "finance_extended_transactions", "finance_extended_cancellations")
        )
        for ((scope, table, cancellations) in sources) db.rawQuery("""SELECT t.id,t.kind,t.amount,t.effective_date,t.description,t.category_id,t.category_legacy,t.source_event_id,t.source_json
            FROM $table t WHERE t.kind IN (${if (scope == "finance") "'income','expense'" else "'card_purchase'"})
            AND NOT EXISTS(SELECT 1 FROM $cancellations c WHERE c.transaction_id=t.id)
            ORDER BY t.effective_date DESC,t.created_at DESC,t.id""", null).use { cursor ->
            while (cursor.moveToNext()) {
                val description = cursor.getString(4)
                if (matchKey(description) != key) continue
                val transactionId = cursor.getString(0)
                val sourceType = if (cursor.isNull(8)) "manual" else try {
                    if (JSONObject(cursor.getString(8)).optString("packageName") == "org.tique.statement") "statement" else "notification"
                } catch (_: Exception) { throw FinanceFailure("INVALID_STATE") }
                val tagTable = if (scope == "finance") "finance_tags" else "finance_extended_tags"
                val tags = db.rawQuery("SELECT tag_id FROM $tagTable WHERE transaction_id=? ORDER BY tag_id", arrayOf(transactionId)).use { tagCursor ->
                    buildList { while (tagCursor.moveToNext()) add(tagCursor.getString(0)) }
                }
                val tagNames = tags.map { tagId ->
                    db.rawQuery("SELECT name FROM classifications WHERE id=?", arrayOf(tagId)).use { tagCursor ->
                        if (!tagCursor.moveToFirst()) throw FinanceFailure("INVALID_STATE")
                        tagCursor.getString(0)
                    }
                }
                val categoryId = cursor.getString(5).takeUnless { cursor.isNull(5) }
                val category = if (categoryId == null) cursor.getString(6) else db.rawQuery("SELECT name FROM classifications WHERE id=?", arrayOf(categoryId)).use { categoryCursor ->
                    if (!categoryCursor.moveToFirst()) cursor.getString(6) else categoryCursor.getString(0)
                }
                rows += mapOf(
                    "id" to transactionId, "kind" to cursor.getString(1), "amountCents" to cursor.getLong(2),
                    "effectiveDate" to cursor.getString(3), "description" to description,
                    "categoryId" to categoryId, "category" to category,
                    "tagIds" to tags, "tags" to tagNames, "sourceEventId" to cursor.getString(7).takeUnless { cursor.isNull(7) },
                    "sourceType" to if (scope == "extended") "card" else sourceType,
                    "scope" to scope, "voided" to false, "cancellationReason" to null
                )
            }
        }
        return rows
    }

    /** Caller must be inside FinancialLedger.atomic; writes only category/tags and an audit row. */
    fun reclassify(db: SQLiteDatabase, changes: List<Change>, ruleId: String? = null): Int {
        if (changes.isEmpty() || changes.size > 1000 || changes.map { it.transactionId }.distinct().size != changes.size) throw FinanceFailure("INVALID_FIELDS")
        val rule = ruleId?.let { uuid(it); findRule(db, it) ?: throw FinanceFailure("RULE_MISSING") }
        if (rule != null && rule["enabled"] != true) throw FinanceFailure("RULE_DISABLED")
        val key = rule?.get("matchKey") as? String
        val now = Instant.now().toString()
        var updated = 0
        for (change in changes) {
            uuid(change.transactionId)
            if (change.scope !in setOf("finance", "extended")) throw FinanceFailure("INVALID_FIELDS")
            val description: String
            val oldCategoryId: String?
            val oldCategory: String
            val oldTags: List<String>
            val request: JSONObject
            val kind: String
            val table = if (change.scope == "finance") "finance_transactions" else "finance_extended_transactions"
            val tagTable = if (change.scope == "finance") "finance_tags" else "finance_extended_tags"
            val cancellationTable = if (change.scope == "finance") "finance_cancellations" else "finance_extended_cancellations"
            db.rawQuery("SELECT kind,description,category_id,category_legacy,request_json FROM $table WHERE id=?", arrayOf(change.transactionId)).use { cursor ->
                if (!cursor.moveToFirst()) throw FinanceFailure("NOT_FOUND")
                kind = cursor.getString(0)
                val allowedKinds = if (change.scope == "finance") setOf("income", "expense") else setOf("card_purchase")
                if (kind !in allowedKinds) throw FinanceFailure("INVALID_FIELDS")
                description = cursor.getString(1)
                oldCategoryId = cursor.getString(2).takeUnless { cursor.isNull(2) }
                oldCategory = cursor.getString(3)
                request = try { JSONObject(cursor.getString(4)) } catch (_: Exception) { throw FinanceFailure("INVALID_STATE") }
            }
            val voided = db.rawQuery("SELECT transaction_id FROM $cancellationTable WHERE transaction_id=?", arrayOf(change.transactionId)).use { it.moveToFirst() }
            if (voided) throw FinanceFailure("TRANSACTION_VOIDED")
            oldTags = db.rawQuery("SELECT tag_id FROM $tagTable WHERE transaction_id=? ORDER BY tag_id", arrayOf(change.transactionId)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            val category = cleanLabel(change.category)
            val tags = change.tagIds.distinct().sorted()
            if (tags.size != change.tagIds.size || tags.size > 20) throw FinanceFailure("INVALID_CLASSIFICATION")
            validateMetadata(db, change.categoryId, category, tags, oldCategoryId, oldTags)
            if (rule != null) {
                if (matchKey(description) != key || change.categoryId != rule["categoryId"] || category != rule["category"] || tags != rule["tagIds"]) throw FinanceFailure("RULE_MISMATCH")
            }
            if (change.categoryId == oldCategoryId && category == oldCategory && tags == oldTags.sorted()) continue

            val nextRequest = JSONObject(request.toString())
                .put("category", category)
                .put("categoryId", change.categoryId ?: JSONObject.NULL)
                .put("tagIds", JSONArray(tags))
            db.execSQL("UPDATE $table SET category_id=?,category_legacy=?,request_json=? WHERE id=?",
                arrayOf(change.categoryId, category, nextRequest.toString(), change.transactionId))
            db.execSQL("DELETE FROM $tagTable WHERE transaction_id=?", arrayOf(change.transactionId))
            for (tag in tags) db.execSQL("INSERT INTO $tagTable(transaction_id,tag_id) VALUES(?,?)", arrayOf(change.transactionId, tag))
            db.execSQL("INSERT INTO finance_metadata_audit VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf(
                UUID.randomUUID().toString(), change.scope, change.transactionId, ruleId, oldCategoryId, oldCategory, JSONArray(oldTags.sorted()).toString(),
                change.categoryId, category, JSONArray(tags).toString(), now
            ))
            updated++
        }
        return updated
    }

    fun matchKey(value: String): String {
        val normalized = Normalizer.normalize(collapseWhitespace(value).lowercase(Locale.ROOT), Normalizer.Form.NFC)
        if (normalized.isBlank() || normalized.length > 500 || normalized.any { Character.isISOControl(it) }) throw FinanceFailure("INVALID_RULE_DESCRIPTION")
        return normalized
    }

    private fun findRule(db: SQLiteDatabase, id: String): Map<String, Any?>? = db.rawQuery(
        "SELECT id,description,category_id,category_legacy,tag_ids_json,enabled,created_at,updated_at,match_key FROM finance_metadata_rules WHERE id=?", arrayOf(id)
    ).use { if (it.moveToFirst()) ruleMap(it, db) else null }

    private fun ruleMap(cursor: android.database.Cursor, db: SQLiteDatabase): Map<String, Any?> {
        val id = cursor.getString(0)
        val description = cursor.getString(1)
        val categoryId = cursor.getString(2).takeUnless { cursor.isNull(2) }
        val savedCategory = cursor.getString(3)
        val category = if(categoryId==null) savedCategory else db.rawQuery("SELECT name FROM classifications WHERE id=? AND kind='category'",arrayOf(categoryId)).use { if(it.moveToFirst()) it.getString(0) else savedCategory }
        val tags = parseTagsJson(cursor.getString(4))
        val enabled = cursor.getInt(5) == 1
        val createdAt = cursor.getString(6)
        val updatedAt = cursor.getString(7)
        val key = if (cursor.columnCount > 8) cursor.getString(8) else matchKey(description)
        return mapOf("id" to id, "description" to description, "categoryId" to categoryId, "category" to category,
            "tagIds" to tags, "enabled" to enabled, "createdAt" to createdAt, "updatedAt" to updatedAt, "matchKey" to key)
    }

    private fun validateMetadata(db: SQLiteDatabase, categoryId: String?, category: String, tags: List<String>, oldCategoryId: String?, oldTags: List<String>?) {
        if (categoryId != null) {
            uuid(categoryId)
            db.rawQuery("SELECT name,archived,kind FROM classifications WHERE id=?", arrayOf(categoryId)).use {
                if (!it.moveToFirst() || it.getString(2) != "category" || it.getString(0) != category || (it.getInt(1) != 0 && categoryId != oldCategoryId)) throw FinanceFailure("INVALID_CLASSIFICATION")
            }
        } else cleanLabel(category)
        if (tags.distinct().size != tags.size || tags.size > 20) throw FinanceFailure("INVALID_CLASSIFICATION")
        for (tagId in tags) {
            uuid(tagId)
            db.rawQuery("SELECT archived,kind FROM classifications WHERE id=?", arrayOf(tagId)).use {
                if (!it.moveToFirst() || it.getString(1) != "tag" || (it.getInt(0) != 0 && tagId !in oldTags.orEmpty())) throw FinanceFailure("INVALID_CLASSIFICATION")
            }
        }
    }

    private fun parseTags(value: Any?): List<String> {
        val array = value as? JSONArray ?: throw FinanceFailure("INVALID_CLASSIFICATION")
        if (array.length() > 20) throw FinanceFailure("INVALID_CLASSIFICATION")
        val values = (0 until array.length()).map { uuid(array.opt(it)) }
        if (values.distinct().size != values.size) throw FinanceFailure("INVALID_CLASSIFICATION")
        return values.sorted()
    }

    private fun parseTagsJson(value: String): List<String> {
        val array = try { JSONArray(value) } catch (_: Exception) { throw FinanceFailure("INVALID_STATE") }
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun parseObject(json: String, max: Int): JSONObject {
        if (json.length > max) throw FinanceFailure("INVALID_FIELDS")
        return try { JSONObject(json) } catch (_: Exception) { throw FinanceFailure("INVALID_FIELDS") }
    }

    private fun optionalString(root: JSONObject, key: String): String? {
        val value = root.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        return value as? String ?: throw FinanceFailure("INVALID_CLASSIFICATION")
    }

    private fun cleanDescription(raw: Any?): String {
        val value = raw as? String ?: throw FinanceFailure("INVALID_RULE_DESCRIPTION")
        val cleaned = collapseWhitespace(value)
        if (cleaned.isBlank() || cleaned.length > 500 || cleaned.any { Character.isISOControl(it) }) throw FinanceFailure("INVALID_RULE_DESCRIPTION")
        return Normalizer.normalize(cleaned, Normalizer.Form.NFC)
    }

    private fun collapseWhitespace(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        val result = StringBuilder(normalized.length)
        var pendingSpace = false
        for (character in normalized) {
            if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                pendingSpace = result.isNotEmpty()
            } else {
                if (pendingSpace) result.append(' ')
                result.append(character)
                pendingSpace = false
            }
        }
        return result.toString()
    }

    private fun cleanLabel(raw: Any?): String {
        val value = raw as? String ?: throw FinanceFailure("INVALID_CLASSIFICATION")
        if (value.isBlank() || value.length > 80 || value.any { Character.isISOControl(it) }) throw FinanceFailure("INVALID_CLASSIFICATION")
        return value
    }

    private fun uuid(value: Any?): String {
        val id = value as? String ?: throw FinanceFailure("INVALID_FIELDS")
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) != true) throw FinanceFailure("INVALID_FIELDS")
        return id
    }
}
