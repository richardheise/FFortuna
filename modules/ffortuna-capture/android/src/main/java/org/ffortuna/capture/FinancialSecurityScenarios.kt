package org.ffortuna.capture

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Invented finance fixtures only; each case uses and removes an isolated encrypted namespace. */
@Suppress("UNCHECKED_CAST")
class FinancialSecurityScenarios(private val context: Context) {
    private fun expect(condition: Boolean, code: String) { if (!condition) throw SecurityCheckFailure(code) }

    private fun failureCode(error: Exception): String? = when (error) {
        is FinanceFailure -> error.code
        is BackupFailure -> error.problem.name
        is ClassificationFailure -> error.code
        is LocalRecoveryFailure -> error.code
        else -> null
    }

    private fun fails(expected: String, action: () -> Unit) {
        val error = try { action(); null } catch (caught: Exception) { caught }
        expect(error != null && failureCode(error) == expected, "FINANCE_EXPECT_$expected")
    }

    private fun case(id: String, title: String, action: (SecuritySandbox) -> Unit) = SecurityScenario(id, title) {
        SecuritySandbox(context).use { sandbox ->
            try { action(sandbox) }
            catch (error: SecurityCheckFailure) { throw error }
            catch (error: FinanceFailure) { throw SecurityCheckFailure("FINANCE_${error.code}") }
            catch (error: BackupFailure) { throw SecurityCheckFailure("FINANCE_BACKUP_${error.problem.name}") }
            catch (error: LocalRecoveryFailure) { throw SecurityCheckFailure("FINANCE_RECOVERY_${error.code}") }
            catch (error: ClassificationFailure) { throw SecurityCheckFailure("FINANCE_CATALOG_${error.code}") }
            catch (_: Exception) { throw SecurityCheckFailure("FINANCE_SCENARIO") }
        }
    }

    private fun boxes(sandbox: SecuritySandbox): FinanceBoxes {
        val preferences = sandbox.newName()
        val default = sandbox.newName()
        return FinanceBoxes(sandbox.context, preferences, default, sandbox::newName)
    }

    private fun today() = LocalDate.now().toString()

    private fun setup(ledger: FinancialLedger, balance: Long = 0, date: String = today()) {
        ledger.setup(JSONObject().put("openingDate", date).put("openingBalanceCents", balance).toString())
    }

    private fun movement(
        command: String = UUID.randomUUID().toString(), amount: Long = 100,
        kind: String = "expense", date: String = today(), description: String = "Compra fictícia",
        category: String = "A classificar", categoryId: String? = null, tagIds: List<String> = emptyList()
    ): String = JSONObject().put("commandId", command).put("amountCents", amount).put("kind", kind)
        .put("effectiveDate", date).put("description", description).put("category", category)
        .put("categoryId", categoryId ?: JSONObject.NULL).put("tagIds", JSONArray(tagIds)).toString()

    private fun month() = today().take(7)

    fun cases(): List<SecurityScenario> = listOf(
        case("finance-opening-ledger", "Saldos de abertura zero e negativo fecham com receitas e despesas") { sandbox ->
            boxes(sandbox).use { finance ->
                val zero = finance.current().ledger()
                setup(zero, 0)
                expect(zero.snapshot(month())["balanceCents"] == 0L, "FINANCE_ZERO_OPENING")
                expect(zero.rows("finance_entries").size == 2, "FINANCE_OPENING_LEGS")

                val negativeName = sandbox.newName()
                ClassificationCatalog(sandbox.context, negativeName).use { catalog ->
                    val ledger = catalog.ledger()
                    setup(ledger, -500)
                    val open = ledger.snapshot(month())
                    expect(open["balanceCents"] == -500L, "FINANCE_NEGATIVE_OPENING")
                    ledger.post(movement(amount = 1000, kind = "income", date = today()))
                    ledger.post(movement(amount = 250, kind = "expense", date = today()))
                    expect(ledger.snapshot(month())["balanceCents"] == 250L, "FINANCE_SIGNED_LEDGER")
                    ledger.validate()
                }
            }
        },
        case("finance-validation-atomic", "Valores inválidos e totais excessivos não alteram o livro") { sandbox ->
            boxes(sandbox).use { finance ->
                val ledger = finance.current().ledger()
                setup(ledger, FinanceRules.MAX)
                val opening = today()
                fails("INVALID_AMOUNT") { ledger.post(movement(amount = 0, date = opening)) }
                fails("INVALID_AMOUNT") { ledger.post(movement(amount = -1, date = opening)) }
                fails("UNSUPPORTED_MOVEMENT") { ledger.post(movement(amount = 10, kind = "transfer", date = LocalDate.now().minusDays(1).toString())) }
                fails("INVALID_DATE") { ledger.post(movement(amount = 10, date = "2026-02-30")) }
                fails("FUTURE_DATE") { ledger.post(movement(amount = 10, date = LocalDate.now().plusDays(1).toString())) }
                fails("AMOUNT_LIMIT") { ledger.post(movement(amount = 1, kind = "income", date = opening)) }
                expect(ledger.rows("finance_transactions").size == 1, "FINANCE_INVALID_PARTIAL_ROW")
                expect(ledger.snapshot(month())["balanceCents"] == FinanceRules.MAX, "FINANCE_INVALID_CHANGED_BALANCE")
            }
        },
        case("finance-manual-idempotency", "Repetir o mesmo comando é idempotente e conflito é rejeitado") { sandbox ->
            boxes(sandbox).use { finance ->
                val ledger = finance.current().ledger(); setup(ledger)
                val command = UUID.randomUUID().toString()
                val request = movement(command = command, amount = 375)
                val first = ledger.post(request)
                expect(ledger.post(request) == first, "FINANCE_RETRY_ID")
                fails("CONFIRMATION_CONFLICT") { ledger.post(movement(command = command, amount = 375, description = "Outro texto fictício")) }
                val cancellation = JSONObject().put("id", first).put("reason", "Valor informado por engano").toString()
                ledger.cancel(cancellation)
                ledger.cancel(cancellation)
                expect(ledger.rows("finance_cancellations").size == 1, "FINANCE_CANCEL_NOT_IDEMPOTENT")
                expect(ledger.rows("finance_entries").size == 4, "FINANCE_CANCEL_REMOVED_AUDIT_LEGS")
                expect(ledger.snapshot(month())["expenseCents"] == 0L && ledger.snapshot(month())["balanceCents"] == 0L, "FINANCE_CANCEL_TOTALS")
                expect(ledger.post(request) == first, "FINANCE_CANCEL_RETRY_ID")
                val canceled = (ledger.snapshot(month())["transactions"] as List<Map<String, Any?>>).single()
                expect(canceled["voided"] == true && canceled["cancellationReason"] == "Valor informado por engano", "FINANCE_CANCEL_RETRY_REACTIVATED")
                expect(ledger.rows("finance_transactions").size == 2, "FINANCE_RETRY_DUPLICATED")
                expect(ledger.snapshot(month())["balanceCents"] == 0L, "FINANCE_RETRY_BALANCE")
            }
        },
        case("finance-equal-purchases", "Compras fictícias iguais com comandos distintos permanecem separadas") { sandbox ->
            boxes(sandbox).use { finance ->
                val ledger = finance.current().ledger(); setup(ledger)
                val first = ledger.post(movement(command = UUID.randomUUID().toString(), amount = 1299, description = "Compra fictícia"))
                val second = ledger.post(movement(command = UUID.randomUUID().toString(), amount = 1299, description = "Compra fictícia"))
                expect(first != second, "FINANCE_EQUAL_ID_COLLISION")
                expect(ledger.snapshot(month())["total"] == 2, "FINANCE_EQUAL_COLLAPSED")
                expect(ledger.snapshot(month())["balanceCents"] == -2598L, "FINANCE_EQUAL_BALANCE")
            }
        },
        case("finance-source-idempotency", "Publicação de origem completa é vinculada uma única vez") { sandbox ->
            boxes(sandbox).use { finance ->
                val ledger = finance.current().ledger(); setup(ledger)
                val event = UUID.randomUUID().toString()
                val source = mapOf(
                    "eventId" to event, "packageName" to DiagnosticPolicy.PACKAGE, "sourceKey" to "synthetic-source",
                    "revision" to 1, "publishedAt" to Instant.now().toString(), "capturedAt" to Instant.now().toString(),
                    "parserVersion" to BrlNotificationParser.VERSION, "fieldEvidence" to emptyMap<String, Any?>(),
                    "confidence" to mapOf("movement" to "certain", "amount" to "certain", "nature" to "certain",
                        "account" to "missing", "date" to "missing", "category" to "missing")
                )
                val requestObject = JSONObject(movement(command = UUID.randomUUID().toString(), amount = 700, kind = "income"))
                requestObject.remove("commandId")
                val request = requestObject.toString()
                val id = ledger.post(request, source)
                expect(ledger.post(request, source) == id, "FINANCE_SOURCE_RETRY")
                expect(ledger.transactionForEvent(event)?.get("id") == id, "FINANCE_SOURCE_LINK")
                expect(ledger.confirmedEvents()[event] == id && ledger.confirmedEvents().size == 1, "FINANCE_SOURCE_INBOX_INDEX")
                expect(ledger.rows("finance_transactions").size == 2, "FINANCE_SOURCE_DUPLICATE")
                ledger.validate()
            }
        },
        case("finance-diagnostic-clear-isolated", "Limpar diagnóstico preserva o livro e os saldos") { sandbox ->
            boxes(sandbox).use { finance ->
                val ledger = finance.current().ledger(); setup(ledger, 5000)
                ledger.post(movement(amount = 350, description = "Despesa de teste"))
                val before = ledger.snapshot(month())
                val diagnosticName = sandbox.newName()
                val signer = "a".repeat(64)
                val oldWall = Instant.now().minusSeconds(8 * 86400L)
                val oldMoment = DiagnosticMoment(oldWall, 10_000, 1)
                DiagnosticStore(sandbox.context, diagnosticName, true).use { store ->
                    store.start(signer, oldMoment)
                    store.observe(DiagnosticPolicy.PACKAGE, signer, "synthetic-retention-source", oldWall.toEpochMilli(), null, false, oldMoment, oldMoment) {
                        DiagnosticFields(title = "Fixture fictícia", text = "Texto inventado") to false
                    }
                }
                val now = DiagnosticMoment(Instant.now(), 10_000 + 8 * 86400_000L, 1)
                DiagnosticStore(sandbox.context, diagnosticName, false).use { store ->
                    expect(store.state(now)["count"] == 0, "FINANCE_DIAGNOSTIC_RETENTION")
                }
                val diagnostic = DiagnosticCapture(sandbox.context, diagnosticName)
                diagnostic.clear()
                expect(!sandbox.context.getDatabasePath("$diagnosticName.db").exists(), "FINANCE_DIAGNOSTIC_REMAINS")
                expect(ledger.snapshot(month()) == before, "FINANCE_DIAGNOSTIC_CLEAR_CHANGED_BOOK")
                expect(ledger.rows("finance_entries").size == 4, "FINANCE_DIAGNOSTIC_CLEAR_REMOVED_ENTRIES")
            }
        },
        case("finance-portable-backup", "Backup cifrado rejeita senha e dados inválidos e preserva caixa anterior") { sandbox ->
            boxes(sandbox).use { finance ->
                val ledger = finance.current().ledger(); setup(ledger, 1000)
                val backedTransaction = ledger.post(movement(command = UUID.randomUUID().toString(), amount = 200, description = "Antes do backup"))
                ledger.cancel(JSONObject().put("id", backedTransaction).put("reason", "Registro fictício anulado antes do backup").toString())
                val activeBefore = finance.activeName()
                val password = "senha-sintetica-segura".toCharArray()
                val plain = finance.current().exportFinance()
                val encrypted = try { BackupCodec.encrypt(plain, password) } finally { plain.fill(0) }
                fails("AUTH_FAILED") { BackupCodec.decrypt(encrypted, "senha-incorreta-sintetica".toCharArray()).fill(0) }
                val restoredPlain = BackupCodec.decrypt(encrypted, password)
                try {
                    fails("INVALID_DATA") { finance.restore("invalid synthetic finance payload".toByteArray()) }
                    expect(finance.activeName() == activeBefore && !finance.hasPrevious(), "FINANCE_BAD_BACKUP_CHANGED_ACTIVE")
                    ledger.post(movement(command = UUID.randomUUID().toString(), amount = 50, description = "Depois do backup"))
                    expect(finance.restore(restoredPlain) == 1, "FINANCE_BACKUP_COUNT")
                    expect(finance.activeName() != activeBefore && finance.hasPrevious(), "FINANCE_BACKUP_NOT_STAGED")
                    val restoredLedger = finance.current().ledger()
                    val restoredItem = (restoredLedger.snapshot(month())["transactions"] as List<Map<String, Any?>>).single()
                    expect(restoredItem["id"] == backedTransaction && restoredItem["voided"] == true && restoredItem["cancellationReason"] == "Registro fictício anulado antes do backup", "FINANCE_BACKUP_CANCELLATION_LOST")
                    expect(restoredLedger.snapshot(month())["balanceCents"] == 1000L, "FINANCE_BACKUP_CANCELLED_BALANCE")
                    finance.switchPrevious()
                    expect(finance.activeName() == activeBefore, "FINANCE_PREVIOUS_NOT_SWITCHED")
                    expect(finance.current().ledger().snapshot(month())["balanceCents"] == 750L, "FINANCE_PREVIOUS_DATA_LOST")
                } finally { restoredPlain.fill(0); password.fill('\u0000') }
            }
        },
        case("finance-local-recovery", "Recuperação usa payload financeiro, respeita 24 horas e limita três cópias") { sandbox ->
            boxes(sandbox).use { finance ->
                val ledger = finance.current().ledger(); setup(ledger, 0)
                val namespace = "${sandbox.defaultName}-rec"
                val keyName = sandbox.newName()
                val start = Instant.parse("2026-10-01T00:00:00Z")
                fun recovery(at: Instant) = LocalRecoveryStore(sandbox.context, Clock.fixed(at, ZoneOffset.UTC), keyName, namespace, FinancialBackup::info)
                fun save(at: Instant) {
                    val plain = finance.current().exportFinance()
                    try { recovery(at).maybeSavePayload(plain) } finally { plain.fill(0) }
                }
                save(start)
                ledger.post(movement(amount = 10, description = "Movimento 1"))
                save(start.plusSeconds(23 * 3600L))
                expect(recovery(start).entries().size == 1, "FINANCE_RECOVERY_EARLY_COPY")
                save(start.plusSeconds(24 * 3600L))
                for (day in 2L..4L) {
                    ledger.post(movement(amount = day * 10, description = "Movimento $day"))
                    save(start.plusSeconds(day * 86400L))
                }
                val entries = recovery(start.plusSeconds(4 * 86400L)).entries()
                expect(entries.size == 3 && entries.first().count == 5, "FINANCE_RECOVERY_ROTATION")
                val recovered = recovery(start.plusSeconds(4 * 86400L)).loadPayload(entries.last().id)
                try {
                    expect(FinancialBackup.decode(recovered).getValue("finance_transactions").size == 3, "FINANCE_RECOVERY_OLD_SNAPSHOT")
                    expect(finance.restore(recovered) == 2, "FINANCE_RECOVERY_RESTORE")
                    expect(finance.current().ledger().snapshot(month())["total"] == 2, "FINANCE_RECOVERY_RESTORED_STATE")
                } finally { recovered.fill(0) }
            }
        },
        case("finance-key-loss-restore", "Backup restaura em chave nova e preserva o banco antigo sem chave") { sandbox ->
            val prefsName = sandbox.newName(); val defaultName = sandbox.newName()
            var finance = FinanceBoxes(sandbox.context, prefsName, defaultName, sandbox::newName)
            val oldName = finance.activeName()
            val plain = try {
                setup(finance.current().ledger(), 2500)
                finance.current().exportFinance()
            } finally { finance.close() }
            try {
                KeyVault(sandbox.context, oldName).erase()
                expect(sandbox.context.getDatabasePath("$oldName.db").exists(), "FINANCE_KEY_LOSS_DELETED_DB")
                finance = FinanceBoxes(sandbox.context, prefsName, defaultName, sandbox::newName)
                failsAny("FINANCE_KEY_LOSS_OPENED") { finance.current() }
                expect(!KeyVault(sandbox.context, oldName).hasStoredMaterial(), "FINANCE_KEY_LOSS_KEY_REGENERATED")
                finance.close()
                finance = FinanceBoxes(sandbox.context, prefsName, defaultName, sandbox::newName)
                expect(finance.restore(plain) == 0, "FINANCE_KEY_LOSS_RESTORE_COUNT")
                val fresh = finance.activeName()
                expect(fresh != oldName && finance.hasPrevious(), "FINANCE_KEY_LOSS_NO_STAGED_BOX")
                expect(KeyVault(sandbox.context, fresh).hasStoredMaterial(), "FINANCE_KEY_LOSS_NO_FRESH_KEY")
                expect(finance.current().ledger().snapshot(month())["balanceCents"] == 2500L, "FINANCE_KEY_LOSS_RESTORE_DATA")
                expect(sandbox.context.getDatabasePath("$oldName.db").exists(), "FINANCE_KEY_LOSS_OLD_DB_DELETED")
            } finally { plain.fill(0); finance.close() }
        },
        case("finance-staging-recovery", "Estágio financeiro incompleto é limpo sem alterar a caixa ativa") { sandbox ->
            val prefsName = sandbox.newName(); val defaultName = sandbox.newName(); val staged = sandbox.newName()
            val prefs = sandbox.context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            FinanceBoxes(sandbox.context, prefsName, defaultName, sandbox::newName).use { finance ->
                setup(finance.current().ledger(), 4200)
            }
            ClassificationCatalog(sandbox.context, staged).use { partial -> setup(partial.ledger(), 7700) }
            expect(prefs.edit().putString("pending", staged).commit(), "FINANCE_PENDING_SETUP")
            FinanceBoxes(sandbox.context, prefsName, defaultName, sandbox::newName).use { recovered ->
                expect(prefs.getString("pending", null) == null, "FINANCE_PENDING_REMAINS")
                expect(recovered.activeName() == defaultName && !recovered.hasPrevious(), "FINANCE_PENDING_CHANGED_POINTER")
                expect(!sandbox.context.getDatabasePath("$staged.db").exists() && !KeyVault(sandbox.context, staged).hasStoredMaterial(), "FINANCE_PENDING_FILES_REMAIN")
                expect(recovered.current().ledger().snapshot(month())["balanceCents"] == 4200L, "FINANCE_PENDING_LOST_ACTIVE")
                val categoryId = recovered.current().snapshot().first { it["kind"] == "category" }["id"]
                val oldBox = recovered.activeName()
                recovered.restartBook()
                expect(recovered.activeName() != oldBox && recovered.hasPrevious(), "FINANCE_RESTART_NOT_STAGED")
                expect(!recovered.current().ledger().configured(), "FINANCE_RESTART_NOT_EMPTY")
                expect(recovered.current().snapshot().any { it["id"] == categoryId }, "FINANCE_RESTART_LOST_CATALOG_ID")
                recovered.switchPrevious()
                expect(recovered.activeName() == oldBox && recovered.current().ledger().snapshot(month())["balanceCents"] == 4200L, "FINANCE_RESTART_PREVIOUS_DATA_LOST")
            }
        },
        case("finance-schema-migration", "Migração de catálogo v1 preserva chave encapsulada e IDs") { sandbox ->
            val name = sandbox.newName(); val file = sandbox.context.getDatabasePath("$name.db")
            check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
            SqlCipherPolicy.initialize()
            val vault = KeyVault(sandbox.context, name)
            val key = vault.load(false)
            val stableId = UUID.randomUUID().toString()
            try {
                SQLiteDatabase.openOrCreateDatabase(file, key, null, SqlCipherPolicy.failClosed, SqlCipherPolicy.hook).use { db ->
                    db.execSQL("CREATE TABLE classifications(id TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,name TEXT NOT NULL,name_key TEXT NOT NULL,archived INTEGER NOT NULL)")
                    db.execSQL("CREATE UNIQUE INDEX classifications_kind_name ON classifications(kind,name_key)")
                    db.execSQL("INSERT INTO classifications VALUES(?,?,?,?,0)", arrayOf(stableId, "category", "Legacy", "legacy"))
                    db.version = 1
                }
            } finally { key.fill(0) }
            val wrappedFile = File(sandbox.context.noBackupFilesDir, "$name.wrapped")
            val wrappedBefore = wrappedFile.readBytes()
            ClassificationCatalog(sandbox.context, name, allowCreate = false).use { catalog ->
                expect(catalog.snapshot().any { it["id"] == stableId && it["name"] == "Legacy" }, "FINANCE_MIGRATION_ID_LOST")
                expect(!catalog.ledger().configured() && catalog.ledger().rows("finance_transactions").isEmpty(), "FINANCE_MIGRATION_UNEXPECTED_PROFILE")
                setup(catalog.ledger(), 75)
                expect(catalog.ledger().snapshot(month())["balanceCents"] == 75L, "FINANCE_MIGRATION_LEDGER")
            }
            expect(wrappedBefore.contentEquals(wrappedFile.readBytes()), "FINANCE_MIGRATION_REWRAPPED_KEY")
        },
        case("finance-balance-catalog", "Conferência não muda totais e vínculos arquivados acompanham renomeações") { sandbox ->
            boxes(sandbox).use { finance ->
                val catalog = finance.current(); val ledger = catalog.ledger(); setup(ledger, 1000)
                val categoryId = catalog.create("category", "Compras fictícias").first { it["name"] == "Compras fictícias" }["id"] as String
                val tagId = catalog.create("tag", "Casa fictícia").first { it["name"] == "Casa fictícia" }["id"] as String
                val command = UUID.randomUUID().toString()
                val request = movement(command, 300, "expense", today(), "Compra fictícia", "Compras fictícias", categoryId, listOf(tagId))
                ledger.post(request)
                val countBefore = ledger.rows("finance_transactions").size
                ledger.recordBalance(JSONObject().put("date", today()).put("amountCents", 900).toString())
                val afterCheck = ledger.snapshot(month())
                expect(afterCheck["balanceCents"] == 700L && afterCheck["incomeCents"] == 0L && afterCheck["expenseCents"] == 300L, "FINANCE_BALANCE_CHECK_CHANGED_TOTALS")
                val check = afterCheck["balanceCheck"] as Map<*, *>
                expect(check["calculatedCents"] == 700L && check["differenceCents"] == 200L, "FINANCE_BALANCE_CHECK_DIFFERENCE")
                expect(ledger.rows("finance_transactions").size == countBefore, "FINANCE_BALANCE_CHECK_ADDED_ENTRY")
                catalog.setArchived(categoryId, true); catalog.setArchived(tagId, true)
                catalog.rename(categoryId, "Compras renomeadas")
                catalog.rename(tagId, "Casa renomeada")
                expect(ledger.post(request).isNotBlank(), "FINANCE_ARCHIVED_RETRY_REJECTED")
                val transaction = ledger.snapshot(month())["transactions"] as List<Map<String, Any?>>
                expect(transaction.single()["category"] == "Compras renomeadas", "FINANCE_RENAMED_CATEGORY_LINK")
                expect(transaction.single()["tags"] == listOf("Casa renomeada"), "FINANCE_RENAMED_TAG_LINK")
                expect(ledger.rows("finance_transactions").size == countBefore, "FINANCE_ARCHIVED_RETRY_DUPLICATED")
                ledger.validate()
            }
        }
    )

    private fun failsAny(code: String, action: () -> Unit) {
        val failed = try { action(); false } catch (_: Exception) { true }
        expect(failed, code)
    }
}
