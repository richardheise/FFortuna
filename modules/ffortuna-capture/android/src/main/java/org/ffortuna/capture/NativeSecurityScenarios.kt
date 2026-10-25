package org.ffortuna.capture

import android.content.Context
import android.database.sqlite.SQLiteException
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.sqlcipher.SQLiteDatabase

data class SecurityScenario(val id: String, val title: String, val run: () -> Unit)

/** Runnable synthetic security checks shared by instrumentation tests and the on-device checker. */
class NativeSecurityScenarios(context: Context) {
    private val appContext = context

    fun cases(): List<SecurityScenario> = listOf(
        SecurityScenario("legacy-setup", "Regressão da abertura preserva banco e chave") { isolated {
            val name = it.defaultName
            val vault = KeyVault(it.context, name)
            var store: CaptureStore? = null
            try {
                System.loadLibrary("sqlcipher"); Logger.setTarget(NoopTarget())
                val file = it.context.getDatabasePath("$name.db")
                file.parentFile!!.mkdirs()
                val key = vault.load(false)
                try { SQLiteDatabase.openOrCreateDatabase(file, key, null, null).use { legacy ->
                    legacy.execSQL("CREATE TABLE security_legacy_probe(value TEXT)")
                    legacy.execSQL("INSERT INTO security_legacy_probe VALUES ('fixture')")
                    expectFailure<SQLiteException>("LEGACY_SETUP") { legacy.execSQL("PRAGMA secure_delete=ON") }
                } } finally { key.fill(0) }
                val wrapped = File(it.context.noBackupFilesDir, "$name.wrapped")
                val before = wrapped.readBytes()
                store = CaptureStore(it.context, name)
                expect(store.snapshot()["schemaVersion"] == 2, "LEGACY_SCHEMA")
                store.observe("after-upgrade", payload(), 1792756800000)
                expect(store.processPending() == 1, "LEGACY_PROCESS")
                expect(store.verifyEncrypted(), "LEGACY_ENCRYPTION")
                expect(before.contentEquals(wrapped.readBytes()), "LEGACY_KEY_CHANGED")
                val probeKey=vault.load(true)
                try { SQLiteDatabase.openOrCreateDatabase(file,probeKey,null,SqlCipherPolicy.failClosed,SqlCipherPolicy.hook).use { probe ->
                    probe.rawQuery("SELECT value FROM security_legacy_probe",null).use { rows ->
                        expect(rows.moveToFirst() && rows.getString(0)=="fixture","LEGACY_DATA_CHANGED")
                    }
                } } finally { probeKey.fill(0) }
            } finally { store?.close() }
        } },
        SecurityScenario("storage-reopen", "Persistência, revisões e processamento interrompido") { isolated {
            val name = it.defaultName; var store: CaptureStore? = null
            try {
                store = CaptureStore(it.context, name)
                val id = store.observe("source-one", payload(), 1792756800000)
                expect(store.snapshot()["pending"] == 1, "REOPEN_PENDING")
                store.close(); store = CaptureStore(it.context, name)
                expect(store.snapshot()["total"] == 1, "REOPEN_TOTAL")
                expect(store.processPending() == 1 && store.snapshot()["pending"] == 0, "REOPEN_PROCESS")
                expect(id == store.observe("source-one", payload(), 1792756800000), "REOPEN_IDENTITY")
                expect(store.snapshot()["total"] == 1, "REOPEN_DUPLICATE")
                store.observe("source-one", payload(2990), 1792756800001)
                store.observe("source-two", payload(), 1792756800000); store.processPending()
                expect(store.snapshot()["total"] == 3, "REOPEN_REVISIONS")
                expect(store.verifyEncrypted(), "REOPEN_ENCRYPTION")
            } finally { store?.close() }
        } },
        SecurityScenario("expired-pending", "Bruto expirado é limpo e a observação permanece") { isolated {
            val name = it.defaultName
            CaptureStore(it.context, name).use { store ->
                store.observe("old-source", payload(), 1792756800000, Instant.now().minusSeconds(31L * 86400).toString())
            }
            CaptureStore(it.context, name).use { store ->
                expect(store.snapshot()["total"] == 1, "EXPIRED_TOTAL")
                expect(store.snapshot()["unavailablePending"] == 1, "EXPIRED_MARKED")
                expect(store.processPending() == 0, "EXPIRED_NOT_PROCESSED")
            }
        } },
        SecurityScenario("missing-key", "Chave ausente não é substituída") { isolated {
            val name = it.defaultName; val context = it.context; val vault = KeyVault(context, name)
            try {
                val first = vault.load(false); val second = vault.load(true)
                expect(first.contentEquals(second), "KEY_STABLE"); first.fill(0); second.fill(0)
                val wrapped = File(context.noBackupFilesDir, "$name.wrapped"); val before = wrapped.readBytes()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("ffortuna.$name.wrap.v1") }
                expectFailure<IllegalStateException>("KEY_MISSING") { vault.load(true) }
                expect(before.contentEquals(wrapped.readBytes()), "KEY_WRAPPED_CHANGED")
            } finally { vault.erase() }
        } },
        SecurityScenario("tampered-key", "Chave encapsulada adulterada bloqueia o acesso") { isolated {
            val name = it.defaultName; val context = it.context; val vault = KeyVault(context, name)
            try {
                vault.load(false).fill(0)
                val wrapped = File(context.noBackupFilesDir, "$name.wrapped")
                val bytes = wrapped.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); wrapped.writeBytes(bytes)
                expectFailure<Exception>("KEY_TAMPERED") { vault.load(true) }
                expect(bytes.contentEquals(wrapped.readBytes()), "KEY_TAMPER_WRITE")
            } finally { vault.erase() }
        } },
        SecurityScenario("atomic-key-recovery", "Recuperação de gravação atômica da chave") { isolated {
            val name = it.defaultName; val context = it.context; val vault = KeyVault(context, name)
            try {
                val first = vault.load(false); val file = File(context.noBackupFilesDir, "$name.wrapped")
                expect(file.renameTo(File(file.path + ".bak")), "KEY_BACKUP_RENAME")
                val recovered = vault.load(true)
                expect(first.contentEquals(recovered) && file.exists(), "KEY_RECOVERY")
                first.fill(0); recovered.fill(0)
            } finally { vault.erase() }
        } },
        SecurityScenario("backup-portable", "Backup portátil sem bruto preserva resultados") { isolated {
            withBoxes(it) { boxes, _, _ ->
                val source = boxes.current(); val id = source.observe("one", payload(), 1792886400000); source.processPending()
                source.observe("pending", payload(), 1792886400001)
                val original = source.exportBackup(); val plain = original.encode()
                expect(!plain.toString(Charsets.UTF_8).contains("raw-only-canary"), "BACKUP_RAW_PRESENT")
                val password = passphrase()
                try {
                    val encrypted = BackupCodec.encrypt(plain, password); plain.fill(0)
                    val decrypted = BackupCodec.decrypt(encrypted, password)
                    val backup = InboxBackup.decode(decrypted); decrypted.fill(0)
                    expect(boxes.restore(backup) == 2, "BACKUP_RESTORE_COUNT")
                    val restored = boxes.current(); val snapshot = restored.snapshot()
                    expect(snapshot["total"] == 2 && snapshot["pending"] == 0 && snapshot["unavailablePending"] == 1, "BACKUP_SNAPSHOT")
                    expect(restored.processPending() == 0 && restored.verifyEncrypted(), "BACKUP_RESTORED_SECURITY")
                    expect(id == restored.observe("one", payload(), 1792886400000), "BACKUP_IDENTITY")
                    expect(original == restored.exportBackup().copy(createdAt = original.createdAt), "BACKUP_ROUNDTRIP")
                    @Suppress("UNCHECKED_CAST") val rows = snapshot["observations"] as List<Map<String, Any?>>
                    expect(rows.all { row -> row["payload"] == null }, "BACKUP_PAYLOAD_PRESENT")
                } finally { password.fill('\u0000') }
            }
        } },
        SecurityScenario("backup-missing-key", "Restauração sem chave antiga preserva arquivos") { isolated {
            withBoxes(it) { boxes, name, prefsName ->
                boxes.current().observe("source", payload(), 1792886400000); boxes.current().processPending()
                val password = passphrase()
                val encrypted = try { BackupCodec.encrypt(boxes.current().exportBackup().encode(), password) } finally { password.fill('\u0000') }
                boxes.close(); val context = it.context
                val wrapped = File(context.noBackupFilesDir, "$name.wrapped"); val wrappedBefore = wrapped.readBytes()
                val file = context.getDatabasePath("$name.db"); val fileBefore = file.readBytes()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("ffortuna.$name.wrap.v1") }
                expectFailure<CaptureStorageFailure>("BACKUP_MISSING_KEY") { boxes.current() }
                val wrong = "outra senha errada longa".toCharArray()
                try { expectFailure<BackupFailure>("BACKUP_WRONG_PASSWORD") { BackupCodec.decrypt(encrypted, wrong) } } finally { wrong.fill('\u0000') }
                expect(fileBefore.contentEquals(file.readBytes()) && wrappedBefore.contentEquals(wrapped.readBytes()), "BACKUP_OLD_FILES_CHANGED")
                val password2 = passphrase()
                val backup = try { val plain = BackupCodec.decrypt(encrypted, password2); try { InboxBackup.decode(plain) } finally { plain.fill(0) } } finally { password2.fill('\u0000') }
                boxes.restore(backup)
                expect(boxes.hasPrevious() && fileBefore.contentEquals(file.readBytes()) && wrappedBefore.contentEquals(wrapped.readBytes()), "BACKUP_RETAINED_CHANGED")
                expect(boxes.current().snapshot()["total"] == 1, "BACKUP_NEW_BOX")
                val active = boxes.activeName(); boxes.close()
                CaptureBoxes(context, prefsName, name, nameFactory = it::newName).use { reopened ->
                    expect(active == reopened.activeName() && reopened.current().snapshot()["total"] == 1, "BACKUP_REOPEN")
                }
                expectFailure<CaptureStorageFailure>("BACKUP_SWITCH_MISSING_KEY") { boxes.switchPrevious() }
                expect(active == boxes.activeName(), "BACKUP_ACTIVE_CHANGED")
                expectFailure<BackupFailure>("BACKUP_SECOND_RESTORE") { boxes.restore(backup) }
                boxes.discardPrevious(); expect(!file.exists(), "BACKUP_DISCARD")
                boxes.restore(backup); expect(boxes.current().snapshot()["total"] == 1, "BACKUP_RESTORE_AGAIN")
            }
        } },
        SecurityScenario("backup-retained", "Alternância de caixas sem mesclar registros") { isolated {
            withBoxes(it) { boxes, _, prefsName ->
                val prefs = it.context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                boxes.current().observe("first", payload(), 1792886400000); boxes.current().processPending()
                val backup = boxes.current().exportBackup()
                boxes.current().observe("second", payload(), 1792886400001); boxes.current().processPending()
                expect(prefs.edit().putBoolean("enabled", true).commit(), "RETAINED_PREF_WRITE")
                boxes.restore(backup); expect(!prefs.getBoolean("enabled", true), "RETAINED_CONSENT")
                expect(boxes.current().snapshot()["total"] == 1, "RETAINED_RESTORE")
                boxes.switchPrevious(); expect(boxes.current().snapshot()["total"] == 2, "RETAINED_SWITCH_OLD")
                boxes.switchPrevious(); expect(boxes.current().snapshot()["total"] == 1, "RETAINED_SWITCH_NEW")
            }
        } },
        SecurityScenario("backup-staging", "Recuperação de restauração interrompida") { isolated {
            withBoxes(it) { boxes, name, prefsName ->
                boxes.current().observe("active", payload(), 1792886400000); boxes.current().processPending()
                val backup = boxes.current().exportBackup(); boxes.close()
                val pending = it.newName(); val prefs = it.context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                expect(prefs.edit().putString("pending-box", pending).commit(), "STAGING_PREF_WRITE")
                CaptureStore(it.context, pending).use { store -> store.importBackup(backup) }
                CaptureBoxes(it.context, prefsName, name, nameFactory = it::newName).use { recovered ->
                    expect(recovered.activeName() == name && recovered.current().snapshot()["total"] == 1, "STAGING_ACTIVE")
                    expect(!it.context.getDatabasePath("$pending.db").exists(), "STAGING_DATABASE")
                    expect(!File(it.context.noBackupFilesDir, "$pending.wrapped").exists(), "STAGING_WRAPPED")
                    expect(!prefs.contains("pending-box"), "STAGING_POINTER")
                }
            }
        } },
        SecurityScenario("backup-plaintext", "Arquivos cifrados não expõem sondas sintéticas") { isolated {
            withBoxes(it) { boxes, _, _ ->
                val marker = "FFORTUNA-SYNTHETIC-PROBE-${UUID.randomUUID()}"
                boxes.current().observe("probe", payload(description=marker), 1792886400000); boxes.current().processPending()
                val password = passphrase()
                val encrypted = try { BackupCodec.encrypt(boxes.current().exportBackup().encode(), password) } finally { password.fill('\u0000') }
                expect(!encrypted.toString(Charsets.ISO_8859_1).contains(marker), "PLAINTEXT_BACKUP")
                expect(boxes.current().verifyEncrypted(), "PLAINTEXT_CIPHER")
                val context = it.context; val file = context.getDatabasePath("${boxes.activeName()}.db")
                val wrapped = File(context.noBackupFilesDir, "${boxes.activeName()}.wrapped")
                for (path in listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"), wrapped, File(wrapped.path + ".bak"), File(wrapped.path + ".new")))
                    if (path.exists()) expect(!path.readBytes().toString(Charsets.ISO_8859_1).contains(marker), "PLAINTEXT_FILE")
            }
        } },
        SecurityScenario("backup-malformed", "Backup inválido ou futuro é rejeitado") { isolated {
            val empty = InboxBackup("2026-10-25T00:00:00Z", emptyList()).encode()
            expect(InboxBackup.decode(empty).observations.isEmpty(), "MALFORMED_EMPTY")
            val future = JSONObject(empty.toString(Charsets.UTF_8)).put("version", 2).toString().toByteArray()
            expectFailure<BackupFailure>("MALFORMED_FUTURE") { InboxBackup.decode(future) }
            expectFailure<BackupFailure>("MALFORMED_NESTED") { InboxBackup.decode("[".repeat(5000).toByteArray()) }
            expectFailure<BackupFailure>("MALFORMED_UTF8") { InboxBackup.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        } },
        SecurityScenario("backup-sidecars", "Journal e WAL não expõem sondas sintéticas") { isolated {
            val name = it.newName(); val context = it.context; val vault = KeyVault(context, name)
            val file = context.getDatabasePath("$name.db"); file.parentFile!!.mkdirs()
            val marker = "FFORTUNA-ROLLBACK-PROBE-${UUID.randomUUID()}"; val changed = "FFORTUNA-WAL-PROBE-${UUID.randomUUID()}"
            System.loadLibrary("sqlcipher"); Logger.setTarget(NoopTarget()); val key = vault.load(false)
            try {
                SQLiteDatabase.openOrCreateDatabase(file, key, null, null).use { db ->
                    db.execSQL("CREATE TABLE probe(value TEXT)"); db.execSQL("INSERT INTO probe VALUES (?)", arrayOf(marker))
                    db.beginTransaction()
                    try {
                        db.execSQL("UPDATE probe SET value=?", arrayOf(changed))
                        val journal = File(file.path + "-journal")
                        expect(journal.exists(), "SIDECAR_JOURNAL_MISSING")
                        val journalData = journal.readBytes().toString(Charsets.ISO_8859_1)
                        expect(!journalData.contains(marker) && !journalData.contains(changed), "SIDECAR_JOURNAL_PLAINTEXT")
                    } finally { db.endTransaction() }
                    expect(db.enableWriteAheadLogging(), "SIDECAR_WAL_ENABLE")
                    db.execSQL("INSERT INTO probe VALUES (?)", arrayOf(changed))
                    val wal = File(file.path + "-wal"); expect(wal.exists(), "SIDECAR_WAL_MISSING")
                    for (path in listOf(file, wal, File(file.path + "-shm"))) if (path.exists()) {
                        val data = path.readBytes().toString(Charsets.ISO_8859_1)
                        expect(!data.contains(marker) && !data.contains(changed), "SIDECAR_PLAINTEXT")
                    }
                }
            } finally { key.fill(0); vault.erase() }
        } }
    )

    private class ScenarioFailure(code: String) : SecurityCheckFailure(code)

    private inline fun <reified T : Throwable> expectFailure(code: String, block: () -> Unit) {
        try { block() } catch (error: Throwable) { if (error is T) return; throw ScenarioFailure(code) }
        throw ScenarioFailure(code)
    }

    private fun expect(condition: Boolean, code: String) { if (!condition) throw ScenarioFailure(code) }

    private inline fun isolated(block: (SecuritySandbox) -> Unit) {
        val sandbox = SecuritySandbox(appContext)
        try {
            try { block(sandbox) } catch (failure: ScenarioFailure) { throw failure }
            catch (_: Throwable) { throw ScenarioFailure("SCENARIO_UNEXPECTED") }
        } finally { sandbox.close() }
    }

    private inline fun withBoxes(sandbox: SecuritySandbox, block: (CaptureBoxes, String, String) -> Unit) {
        val boxes = sandbox.newBoxes()
        try { block(boxes, sandbox.defaultName, sandbox.preferencesName) } finally { boxes.close() }
    }

    private fun payload(amount: Long = 1990, description: String = "Café fictício") = JSONObject()
        .put("marker", "ffortuna.synthetic.v1").put("amountCents", amount).put("kind", "expense")
        .put("effectiveDate", "2026-10-25").put("description", description).put("category", "Alimentação")
        .put("accountRef", "demo-main").put("externalId", "fixture").put("rawOnlyCanary", "raw-only-canary").toString()

    private fun passphrase() = "senha sintética de teste".toCharArray()
}
