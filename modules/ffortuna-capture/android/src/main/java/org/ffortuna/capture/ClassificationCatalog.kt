package org.ffortuna.capture

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

class ClassificationFailure(val code: String) : IllegalStateException(code)

/** Permanent encrypted category/tag options; owned by CaptureRuntime's serial executor. */
class ClassificationCatalog(
    context: Context,
    private val name: String = DEFAULT_NAME,
    allowCreate: Boolean = true
) : AutoCloseable {
    companion object {
        const val DEFAULT_NAME = "ffortuna-classifications-v1"
        private const val VERSION = 3
        private val seedCategories = listOf("Comida", "Lazer", "Contas", "Transporte", "Saúde", "Educação", "Compras", "Outros")

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE classifications(
                id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL CHECK(kind IN ('category','tag')),
                name TEXT NOT NULL,
                name_key TEXT NOT NULL,
                archived INTEGER NOT NULL CHECK(archived IN (0,1))
            )""")
            db.execSQL("CREATE UNIQUE INDEX classifications_kind_name ON classifications(kind,name_key)")
        }
    }

    private val appContext = context.applicationContext ?: context
    private val file: File
    private val vault: KeyVault
    private val db: SQLiteDatabase
    private var closed = false

    init {
        if (!name.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) throw ClassificationFailure("INVALID_NAMESPACE")
        file = appContext.getDatabasePath("$name.db")
        vault = KeyVault(appContext, name)
        SqlCipherPolicy.initialize()
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) { "CLASSIFICATION_STORAGE" }
        val sidecars = listOf("-wal", "-shm", "-journal").map { File(file.path + it) }
        if (!file.exists()) check(allowCreate && !vault.hasStoredMaterial() && sidecars.none { it.exists() }) { "CLASSIFICATION_MISSING_DB" }
        else check(file.isFile && file.length() >= 16) { "CLASSIFICATION_INVALID_DB" }

        val key = vault.load(file.exists())
        db = try {
            SQLiteDatabase.openOrCreateDatabase(file, key, null, SqlCipherPolicy.failClosed, SqlCipherPolicy.hook)
        } finally { key.fill(0) }
        try {
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery("PRAGMA secure_delete=ON", null).use { check(it.moveToFirst() && it.getInt(0) == 1) }
            db.execSQL("PRAGMA synchronous=FULL")
            db.execSQL("PRAGMA cipher_memory_security=ON")
            db.beginTransaction()
            try {
                check(db.version <= VERSION) { "CLASSIFICATION_FUTURE_SCHEMA" }
                if (db.version == 0) {
                    createSchema(db)
                    for (label in seedCategories) {
                        val normalized = normalizeName(label)
                        db.execSQL("INSERT INTO classifications VALUES(?,?,?,?,0)", arrayOf(UUID.randomUUID().toString(), "category", normalized, nameKey(normalized)))
                    }
                    db.version = 1
                }
                if(db.version==1) { FinancialLedger.createSchema(db);db.version=2 }
                if(db.version==2) { FinancialLedger.createLinksSchema(db);db.version=3 }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            verify()
        } catch (error: Throwable) {
            db.close()
            throw error
        }
    }

    fun snapshot(): List<Map<String, Any?>> {
        checkOpen()
        val items = mutableListOf<Map<String, Any?>>()
        db.rawQuery("SELECT id,kind,name,archived FROM classifications ORDER BY CASE kind WHEN 'category' THEN 0 ELSE 1 END,archived,name_key,id", null).use { rows ->
            while (rows.moveToNext()) items += mapOf(
                "id" to rows.getString(0), "kind" to rows.getString(1), "name" to rows.getString(2), "archived" to (rows.getInt(3) == 1)
            )
        }
        return items
    }

    fun identity()=name
    fun ledger()=FinancialLedger(db)
    fun exportFinance():ByteArray { validateCatalog();ledger().validate();return FinancialBackup.encode(ledger()) }
    fun importFinance(plain:ByteArray) { FinancialBackup.restore(db,plain);validateCatalog();verify() }
    fun validateCatalog() {
        val rows=ledger().rows("classifications")
        if(rows.size>200) throw FinanceFailure("INVALID_STATE")
        for(row in rows) {
            val id=row["id"] as String
            if(UUID.fromString(id).toString()!=id || row["kind"] !in setOf("category","tag") || row["archived"] !in setOf(0L,1L)) throw FinanceFailure("INVALID_STATE")
            val label=row["name"] as String
            if(normalizeName(label)!=label || nameKey(label)!=row["name_key"]) throw FinanceFailure("INVALID_STATE")
        }
    }

    fun create(kind: String, name: String): List<Map<String, Any?>> {
        checkOpen()
        requireKind(kind)
        val normalized = normalizeName(name)
        val key = nameKey(normalized)
        db.beginTransaction()
        try {
            if (count() >= 200) throw ClassificationFailure("ITEM_LIMIT")
            if (exists(kind, key)) throw ClassificationFailure("DUPLICATE_NAME")
            db.execSQL("INSERT INTO classifications VALUES(?,?,?,?,0)", arrayOf(UUID.randomUUID().toString(), kind, normalized, key))
            FinancialBackup.encode(ledger()).fill(0)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return snapshot()
    }

    fun rename(id: String, name: String): List<Map<String, Any?>> {
        checkOpen()
        val normalized = normalizeName(name)
        db.beginTransaction()
        try {
            val kind = db.rawQuery("SELECT kind FROM classifications WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) it.getString(0) else throw ClassificationFailure("ITEM_MISSING")
            }
            if (exists(kind, nameKey(normalized), id)) throw ClassificationFailure("DUPLICATE_NAME")
            db.execSQL("UPDATE classifications SET name=?,name_key=? WHERE id=?", arrayOf(normalized, nameKey(normalized), id))
            FinancialBackup.encode(ledger()).fill(0)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return snapshot()
    }

    fun setArchived(id: String, archived: Boolean): List<Map<String, Any?>> {
        checkOpen()
        db.beginTransaction()
        try {
            val exists = db.rawQuery("SELECT id FROM classifications WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
            if (!exists) throw ClassificationFailure("ITEM_MISSING")
            db.execSQL("UPDATE classifications SET archived=? WHERE id=?", arrayOf<Any>(if (archived) 1 else 0, id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return snapshot()
    }

    private fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM classifications", null).use { it.moveToFirst(); it.getInt(0) }
    private fun exists(kind: String, key: String, excluding: String? = null): Boolean {
        // rawQuery binds every selection argument with bindString; null is not a SQL NULL here.
        val sql = "SELECT id FROM classifications WHERE kind=? AND name_key=?" +
            (if (excluding == null) "" else " AND id!=?") + " LIMIT 1"
        val args: Array<String> = if (excluding == null) arrayOf(kind, key) else arrayOf(kind, key, excluding)
        return db.rawQuery(sql, args).use { it.moveToFirst() }
    }

    private fun normalizeName(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && value[start].isSpace()) start++
        while (end > start && value[end - 1].isSpace()) end--
        val normalized = Normalizer.normalize(value.substring(start, end), Normalizer.Form.NFC)
        if (normalized.isBlank() || normalized.length > 80 || normalized.any { Character.isISOControl(it) })
            throw ClassificationFailure("INVALID_NAME")
        return normalized
    }

    private fun nameKey(value: String): String = Normalizer.normalize(
        value.uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFC
    )

    private fun Char.isSpace() = Character.isWhitespace(this) || Character.isSpaceChar(this)
    private fun requireKind(kind: String) { if (kind != "category" && kind != "tag") throw ClassificationFailure("INVALID_KIND") }
    private fun checkOpen() { if (closed) throw ClassificationFailure("CLOSED") }

    fun verifyForUse() { verify();ledger().validate() }
    private fun verify() {
        db.rawQuery("PRAGMA cipher_integrity_check", null).use { check(!it.moveToFirst()) { "CLASSIFICATION_INTEGRITY" } }
        check(file.inputStream().use { input ->
            val header = ByteArray(16)
            input.read(header) == header.size && !header.contentEquals("SQLite format 3\u0000".toByteArray())
        }) { "CLASSIFICATION_PLAINTEXT" }
    }

    override fun close() {
        if (!closed) { closed = true; db.close() }
    }
}
