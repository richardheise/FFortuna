package org.ffortuna.capture

import android.content.Context
import java.io.File
import java.security.KeyStore

/** Invented labels only; never uses personal or financial user data. */
class ClassificationSecurityScenarios(private val context: Context) {
    private fun expect(value: Boolean, code: String) { if (!value) throw SecurityCheckFailure(code) }
    private fun fails(code: String, action: () -> Unit) {
        try { action() } catch (_: Exception) { return }
        throw SecurityCheckFailure(code)
    }
    private fun case(id: String, title: String, action: (SecuritySandbox) -> Unit) = SecurityScenario(id, title) {
        SecuritySandbox(context).use { sandbox ->
            try { action(sandbox) }
            catch (error: SecurityCheckFailure) { throw error }
            catch (_: Exception) { throw SecurityCheckFailure("CLASSIFICATION_SCENARIO") }
        }
    }
    private fun item(items: List<Map<String, Any?>>, kind: String, name: String) =
        items.single { it["kind"] == kind && it["name"] == name }

    fun cases(): List<SecurityScenario> = listOf(
        case("classification-seeds", "Categorias iniciais locais e sem tags pré-criadas") { sandbox ->
            val name = sandbox.newName()
            ClassificationCatalog(sandbox.context, name).use { catalog ->
                val items = catalog.snapshot()
                expect(items.size == 8 && items.all { it["kind"] == "category" && it["archived"] == false }, "CLASSIFICATION_SEED_COUNT")
                expect(items.map { it["name"] }.toSet() == setOf("Comida", "Lazer", "Contas", "Transporte", "Saúde", "Educação", "Compras", "Outros"), "CLASSIFICATION_SEED_NAMES")
            }
        },
        case("classification-lifecycle", "Criação, renomeação, arquivamento e reativação mantêm ID") { sandbox ->
            val name = sandbox.newName()
            ClassificationCatalog(sandbox.context, name).use { catalog ->
                var items = catalog.create("category", "  Categoria sintética  ")
                val created = item(items, "category", "Categoria sintética")
                val id = created["id"] as String
                items = catalog.rename(id, "Categoria renomeada")
                expect(item(items, "category", "Categoria renomeada")["id"] == id, "CLASSIFICATION_RENAME_ID")
                items = catalog.setArchived(id, true)
                expect(item(items, "category", "Categoria renomeada")["archived"] == true, "CLASSIFICATION_ARCHIVE")
                items = catalog.setArchived(id, false)
                expect(item(items, "category", "Categoria renomeada")["id"] == id && item(items, "category", "Categoria renomeada")["archived"] == false, "CLASSIFICATION_REACTIVATE")
                items = catalog.create("tag", "Viagem de teste")
                expect(item(items, "tag", "Viagem de teste")["kind"] == "tag", "CLASSIFICATION_TAG_KIND")
            }
        },
        case("classification-persistence", "Opções e IDs persistem após fechar e reabrir") { sandbox ->
            val name = sandbox.newName()
            val first = ClassificationCatalog(sandbox.context, name)
            val created = first.create("tag", "Rótulo inventado")
            val id = item(created, "tag", "Rótulo inventado")["id"] as String
            first.setArchived(id, true)
            first.close()
            ClassificationCatalog(sandbox.context, name).use { reopened ->
                val saved = item(reopened.snapshot(), "tag", "Rótulo inventado")
                expect(saved["id"] == id && saved["archived"] == true, "CLASSIFICATION_REOPEN")
            }
        },
        case("classification-duplicates", "Nomes equivalentes são únicos por tipo mesmo arquivados") { sandbox ->
            ClassificationCatalog(sandbox.context, sandbox.newName()).use { catalog ->
                val items = catalog.create("tag", "  Café  ")
                val id = item(items, "tag", "Café")["id"] as String
                catalog.setArchived(id, true)
                catalog.create("tag", "Outros")
                fails("CLASSIFICATION_DUPLICATE_ACCEPTED") { catalog.create("tag", "CAFE\u0301") }
                expect(item(catalog.create("category", "Café"), "category", "Café")["kind"] == "category", "CLASSIFICATION_KIND_SCOPE")
                fails("CLASSIFICATION_RENAME_DUPLICATE_ACCEPTED") { catalog.rename(id, "OUTROS") }
            }
        },
        case("classification-validation", "Rótulos inválidos e acima do limite são rejeitados") { sandbox ->
            ClassificationCatalog(sandbox.context, sandbox.newName()).use { catalog ->
                fails("CLASSIFICATION_BLANK_ACCEPTED") { catalog.create("tag", " \u00a0 ") }
                fails("CLASSIFICATION_CONTROL_ACCEPTED") { catalog.create("tag", "teste\u0000inválido") }
                fails("CLASSIFICATION_LONG_ACCEPTED") { catalog.create("tag", "x".repeat(81)) }
                fails("CLASSIFICATION_KIND_ACCEPTED") { catalog.create("other", "Rótulo") }
                for (index in 0 until 192) catalog.create("tag", "Tag sintética $index")
                expect(catalog.snapshot().size == 200, "CLASSIFICATION_LIMIT_COUNT")
                fails("CLASSIFICATION_LIMIT_IGNORED") { catalog.create("tag", "Última tag") }
            }
        },
        case("classification-missing-material", "Banco ausente com chave e banco sem chave falham sem substituir material") { sandbox ->
            val context = sandbox.context
            val name = sandbox.newName()
            ClassificationCatalog(context, name).use { it.snapshot() }
            val database = context.getDatabasePath("$name.db")
            val wrapped = File(context.noBackupFilesDir, "$name.wrapped")
            val wrappedBefore = wrapped.readBytes()
            expect(context.deleteDatabase("$name.db"), "CLASSIFICATION_DB_DELETE_FIXTURE")
            fails("CLASSIFICATION_MISSING_DB_RECREATED") { ClassificationCatalog(context, name).close() }
            expect(!database.exists() && wrappedBefore.contentEquals(wrapped.readBytes()), "CLASSIFICATION_MISSING_DB_CHANGED")

            val keyName = sandbox.newName()
            ClassificationCatalog(context, keyName).use { it.snapshot() }
            val keyedDatabase = context.getDatabasePath("$keyName.db")
            val keyedWrapped = File(context.noBackupFilesDir, "$keyName.wrapped")
            val databaseBefore = keyedDatabase.readBytes()
            val wrappedBeforeKeyLoss = keyedWrapped.readBytes()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("ffortuna.$keyName.wrap.v1") }
            fails("CLASSIFICATION_MISSING_KEY_ACCEPTED") { ClassificationCatalog(context, keyName).close() }
            expect(databaseBefore.contentEquals(keyedDatabase.readBytes()) && wrappedBeforeKeyLoss.contentEquals(keyedWrapped.readBytes()), "CLASSIFICATION_MISSING_KEY_CHANGED")
        },
        case("classification-isolation", "Limpar diagnóstico não remove catálogo permanente separado") { sandbox ->
            val catalogName = sandbox.newName()
            ClassificationCatalog(sandbox.context, catalogName).use { it.create("tag", "Persistente sintético") }
            val diagnosticName = sandbox.newName()
            val capture = DiagnosticCapture(sandbox.context, diagnosticName)
            val source = mapOf("installed" to true, "enabled" to true, "signer" to "a".repeat(64))
            val now = DiagnosticSource.moment(sandbox.context)
            capture.start("a".repeat(64), source, now)
            capture.stop()
            capture.clear()
            ClassificationCatalog(sandbox.context, catalogName).use { catalog ->
                expect(item(catalog.snapshot(), "tag", "Persistente sintético")["archived"] == false, "CLASSIFICATION_CLEAR_ISOLATION")
            }
        },
        case("classification-encryption", "Banco e sidecars não revelam nomes de rótulos") { sandbox ->
            val name = sandbox.newName()
            val canary = "CATALOG-PRIVATE-${java.util.UUID.randomUUID()}"
            ClassificationCatalog(sandbox.context, name).use { it.create("tag", canary) }
            val files = listOf(sandbox.context.getDatabasePath("$name.db"),
                File(sandbox.context.getDatabasePath("$name.db").path + "-wal"),
                File(sandbox.context.getDatabasePath("$name.db").path + "-shm"),
                File(sandbox.context.getDatabasePath("$name.db").path + "-journal"),
                File(sandbox.context.noBackupFilesDir, "$name.wrapped"))
            expect(files.filter { it.exists() }.all { file -> !file.readBytes().toString(Charsets.ISO_8859_1).contains(canary) }, "CLASSIFICATION_PLAINTEXT")
            expect(files.first().exists(), "CLASSIFICATION_DB_MISSING")
        }
    )
}
