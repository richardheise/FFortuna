package org.ffortuna.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Invented diagnostic text and labels only. Exercises candidate persistence without a global runtime. */
class ClassificationReviewSecurityScenarios(private val context: Context) {
    private val signer = "a".repeat(64)
    private val now = DiagnosticMoment(Instant.parse("2026-10-30T12:00:00Z"), 1000, 4)
    private fun expect(value: Boolean, code: String) { if (!value) throw SecurityCheckFailure(code) }
    private fun fails(code: String, action: () -> Unit) {
        try { action() } catch (_: Exception) { return }
        throw SecurityCheckFailure(code)
    }
    private fun case(id: String, title: String, action: (SecuritySandbox) -> Unit) = SecurityScenario(id, title) {
        SecuritySandbox(context).use { sandbox ->
            try { action(sandbox) }
            catch (error: SecurityCheckFailure) { throw error }
            catch (_: Exception) { throw SecurityCheckFailure("CLASSIFICATION_REVIEW_SCENARIO") }
        }
    }
    private fun candidate(store: DiagnosticStore): String {
        store.start(signer, now)
        store.observe(DiagnosticPolicy.PACKAGE, signer, "invented-source", now.wall.toEpochMilli(), null, false, now, now) {
            DiagnosticFields(title = "Fixture fictícia", text = "Aviso sintético R$ 10,00") to false
        }
        val id = store.samples(now).first()["id"] as String
        store.processCandidates(now)
        store.candidate(id, now)
        return id
    }
    private fun fields(category: String = "Categoria legada", tagIds: JSONArray? = null): JSONObject =
        JSONObject().put("amountCents", 1000).put("kind", "expense").put("effectiveDate", JSONObject.NULL)
            .put("description", "Correção fictícia").put("category", category).put("status", "reviewed")
            .put("categoryId", JSONObject.NULL).put("tagIds", tagIds ?: JSONArray())
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>

    fun cases(): List<SecurityScenario> = listOf(
        case("classification-review-legacy", "Revisão legada sem IDs mantém seu rótulo") { sandbox ->
            val nowLocal = now
            DiagnosticStore(sandbox.context, sandbox.newName(), true).use { store ->
                val id = candidate(store)
                val legacy = JSONObject().put("amountCents", 1000).put("kind", "expense")
                    .put("effectiveDate", JSONObject.NULL).put("description", "Correção antiga fictícia")
                    .put("category", "Rótulo legado").put("status", "reviewed").toString()
                store.saveCandidate(id, legacy, nowLocal)
                val saved = store.candidate(id, nowLocal)
                expect(map(saved["review"])["category"] == "Rótulo legado" && map(saved["review"])["categoryId"] == null, "CLASSIFICATION_LEGACY_LABEL")
                expect(ClassificationReview.detail(saved, emptyList()).let { map(it["review"])["category"] } == "Rótulo legado", "CLASSIFICATION_LEGACY_DETAIL")
            }
        },
        case("classification-review-persist", "IDs de categoria e tags persistem por reprocessamento e renomeação") { sandbox ->
            val dbName = sandbox.newName()
            val catalogName = sandbox.newName()
            val catalog = ClassificationCatalog(sandbox.context, catalogName)
            val store = DiagnosticStore(sandbox.context, dbName, true)
            try {
                val id = candidate(store)
                val options = catalog.snapshot()
                val categoryId = options.single { it["kind"] == "category" && it["name"] == "Comida" }["id"] as String
                val tagId = catalog.create("tag", "Rótulo de teste").single { it["kind"] == "tag" && it["name"] == "Rótulo de teste" }["id"] as String
                val json = fields("Comida", JSONArray().put(tagId)).put("categoryId", categoryId).toString()
                val prepared = ClassificationReview.prepare(json, catalog.snapshot(), null)
                store.saveCandidate(id, prepared, now)
                val updatedName = "Alimentação fictícia"
                catalog.rename(categoryId, updatedName)
                val beforeClose = store.candidate(id, now, true)
                expect(map(beforeClose["review"])["categoryId"] == categoryId && map(beforeClose["review"])["tagIds"] == listOf(tagId), "CLASSIFICATION_REVIEW_IDS")
                expect(map(ClassificationReview.detail(beforeClose, catalog.snapshot())["review"])["category"] == updatedName, "CLASSIFICATION_REVIEW_RENAMED_LABEL")
                store.close(); catalog.close()
                DiagnosticStore(sandbox.context, dbName).use { reopened ->
                    ClassificationCatalog(sandbox.context, catalogName).use { savedCatalog ->
                        val after = reopened.candidate(id, now, true)
                        expect(map(after["review"])["tagIds"] == listOf(tagId), "CLASSIFICATION_REVIEW_REOPEN_TAG")
                        expect(map(ClassificationReview.detail(after, savedCatalog.snapshot())["review"])["category"] == updatedName, "CLASSIFICATION_REVIEW_REOPEN_CATEGORY")
                    }
                }
            } finally {
                try { store.close() } catch (_: Exception) { }
                try { catalog.close() } catch (_: Exception) { }
            }
        },
        case("classification-review-archived", "Seleção arquivada existente é preservada e nova seleção é bloqueada") { sandbox ->
            val catalogName = sandbox.newName()
            ClassificationCatalog(sandbox.context, catalogName).use { catalog ->
                DiagnosticStore(sandbox.context, sandbox.newName(), true).use { store ->
                    val id = candidate(store)
                    val categoryId = catalog.snapshot().first { it["kind"] == "category" && it["name"] == "Comida" }["id"] as String
                    val selectedTag = catalog.create("tag", "Tag selecionada").first { it["kind"] == "tag" && it["name"] == "Tag selecionada" }["id"] as String
                    val newArchivedCategory = catalog.create("category", "Categoria arquivada").first { it["name"] == "Categoria arquivada" }["id"] as String
                    val newArchivedTag = catalog.create("tag", "Tag arquivada").first { it["kind"] == "tag" && it["name"] == "Tag arquivada" }["id"] as String
                    val savedJson = fields("Comida", JSONArray().put(selectedTag)).put("categoryId", categoryId).toString()
                    store.saveCandidate(id, ClassificationReview.prepare(savedJson, catalog.snapshot(), null), now)
                    catalog.setArchived(categoryId, true); catalog.setArchived(selectedTag, true)
                    catalog.setArchived(newArchivedCategory, true); catalog.setArchived(newArchivedTag, true)
                    val previous = map(store.candidate(id, now)["review"])
                    expect(ClassificationReview.prepare(savedJson, catalog.snapshot(), previous).isNotBlank(), "CLASSIFICATION_ARCHIVED_EXISTING_BLOCKED")
                    val newCategory = fields().put("categoryId", newArchivedCategory).toString()
                    fails("CLASSIFICATION_ARCHIVED_CATEGORY_SELECTED") { ClassificationReview.prepare(newCategory, catalog.snapshot(), previous) }
                    val newTag = fields("Comida", JSONArray().put(selectedTag).put(newArchivedTag)).put("categoryId", categoryId).toString()
                    fails("CLASSIFICATION_ARCHIVED_TAG_SELECTED") { ClassificationReview.prepare(newTag, catalog.snapshot(), previous) }
                }
            }
        },
        case("classification-review-invalid", "IDs inexistentes, tipo invertido e listas inválidas não alteram a revisão") { sandbox ->
            val catalogName = sandbox.newName()
            ClassificationCatalog(sandbox.context, catalogName).use { catalog ->
                DiagnosticStore(sandbox.context, sandbox.newName(), true).use { store ->
                    val id = candidate(store)
                    val items = catalog.snapshot()
                    val categoryId = items.first { it["kind"] == "category" }["id"] as String
                    val tagId = catalog.create("tag", "Tag válida").first { it["kind"] == "tag" }["id"] as String
                    val good = fields("Categoria manual", JSONArray().put(tagId)).put("categoryId", categoryId).toString()
                    store.saveCandidate(id, ClassificationReview.prepare(good, catalog.snapshot(), null), now)
                    val before = store.candidate(id, now)["review"]
                    val tooMany = JSONArray().also { array -> repeat(21) { array.put(java.util.UUID.randomUUID().toString()) } }
                    val badInputs = listOf(
                        "not-json",
                        fields().put("categoryId", "not-an-id").toString(),
                        fields().put("categoryId", java.util.UUID.randomUUID().toString()).toString(),
                        fields("Categoria manual", JSONArray().put(categoryId)).toString(),
                        fields("Categoria manual", JSONArray().put(java.util.UUID.randomUUID().toString())).put("categoryId", categoryId).toString(),
                        fields("Categoria manual", JSONArray().put(tagId).put(tagId)).put("categoryId", categoryId).toString(),
                        fields("Categoria manual", tooMany).put("categoryId", categoryId).toString(),
                        fields().put("tagIds", "malformed").put("categoryId", categoryId).toString(),
                        fields().put("tagIds", JSONObject.NULL).put("categoryId", categoryId).toString()
                    )
                    for (bad in badInputs) fails("CLASSIFICATION_INVALID_ACCEPTED") {
                        val prepared = ClassificationReview.prepare(bad, catalog.snapshot(), map(before))
                        store.saveCandidate(id, prepared, now)
                    }
                    expect(store.candidate(id, now)["review"] == before, "CLASSIFICATION_INVALID_CHANGED_REVIEW")
                }
            }
        }
    )
}
