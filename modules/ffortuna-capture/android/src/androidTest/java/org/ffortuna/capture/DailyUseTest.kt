package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

/** Isolated public store behavior using invented examples, never real notification samples. */
@RunWith(AndroidJUnit4::class)
class DailyUseTest {
    private val signer = "b".repeat(64)
    private val start = DiagnosticMoment(Instant.parse("2026-10-30T12:00:00Z"), 1_000, 7)
    private fun at(hours: Long = 0, days: Long = 0, elapsed: Long = start.elapsed, boot: Int = start.boot) =
        DiagnosticMoment(start.wall.plusSeconds(hours * 3600 + days * 86_400), elapsed, boot)
    private fun expense(commandId: String, date: String) = JSONObject().put("commandId", commandId)
        .put("amountCents", 1234).put("kind", "expense").put("effectiveDate", date)
        .put("description", "Compra fictícia").put("category", "A classificar")
        .put("categoryId", JSONObject.NULL).put("tagIds", org.json.JSONArray()).toString()
    private fun batchPost(id: String, date: String, categoryId: String?, category: String, tagIds: List<String>, amount: Long = 1234) =
        ObservedBatchPost(id, amount, "expense", date, "Movimento fictício", category, categoryId, tagIds,
            mapOf("eventId" to id, "packageName" to DiagnosticPolicy.PACKAGE, "sourceKey" to "batch-fixture-$id", "revision" to 1,
                "publishedAt" to start.wall.toString(), "capturedAt" to start.wall.toString(), "parserVersion" to BrlNotificationParser.VERSION,
                "fieldEvidence" to emptyMap<String, Any?>(), "confidence" to mapOf("movement" to "certain", "amount" to "certain",
                    "nature" to "certain", "account" to "missing", "date" to "certain", "category" to "uncertain")))

    @Test fun dailyConsentSurvivesElapsedWindowAndRebootWhileOrdinaryConsentExpires() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            val dailyName = sandbox.newName()
            DiagnosticStore(sandbox.context, dailyName, true).use { it.start(signer, start, daily = true) }
            DiagnosticStore(sandbox.context, dailyName).use { reopened ->
                val afterReboot = at(hours = 30, elapsed = 50, boot = start.boot + 1)
                val state = reopened.state(afterReboot)
                assertEquals("daily", state["mode"])
                assertEquals(true, state["active"])
                assertNull(state["expiresAt"])
                assertTrue(reopened.observe(DiagnosticPolicy.PACKAGE, signer, "invented-key", afterReboot.wall.toEpochMilli(), null,
                    false, afterReboot, afterReboot) { DiagnosticFields(text = "Compra inventada R$ 9,90") to false })
            }

            val ordinaryName = sandbox.newName()
            DiagnosticStore(sandbox.context, ordinaryName, true).use { ordinary ->
                ordinary.start(signer, start)
                val state = ordinary.state(at(hours = 25, elapsed = start.elapsed + 25 * 3600_000L))
                assertEquals("diagnostic", state["mode"])
                assertEquals(false, state["active"])
                assertNotNull(state["expiresAt"])
            }
        }
    }

    @Test fun dailyRedeliveryDeduplicatesButEqualPurchasesFromDifferentSourcesRemainDistinct() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            DiagnosticStore(sandbox.context, sandbox.newName(), true).use { db ->
                db.start(signer, start, daily = true)
                val fields = DiagnosticFields(title = "Fixture inventada", text = "Compra R$ 18,40")
                fun observe(key: String) = db.observe(DiagnosticPolicy.PACKAGE, signer, key, start.wall.toEpochMilli(), null,
                    false, start, start) { fields to false }
                assertTrue(observe("fixture-source-a"))
                assertFalse(observe("fixture-source-a"))
                assertTrue(observe("fixture-source-b"))
                assertEquals(2, db.samples(start).size)
            }
        }
    }

    @Test fun dailyRawExpiresAfterThirtyDaysButParsedCandidateAndCorrectionRemain() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            DiagnosticStore(sandbox.context, sandbox.newName(), true).use { db ->
                db.start(signer, start, daily = true)
                db.observe(DiagnosticPolicy.PACKAGE, signer, "invented-candidate", start.wall.toEpochMilli(), null, false, start, start) {
                    DiagnosticFields(title = "Fixture inventada", text = "Compra R$ 99,90") to false
                }
                val id = db.samples(start).single()["id"] as String
                assertEquals(1, db.processCandidates(start))
                val correction = JSONObject().put("amountCents", 12345).put("kind", "transfer")
                    .put("effectiveDate", "2026-10-29").put("description", "Correção inventada")
                    .put("category", "Teste manual").put("status", "reviewed").toString()
                db.saveCandidate(id, correction, start)

                val expired = at(days = 30, elapsed = start.elapsed + 30 * 24 * 3600_000L)
                db.processCandidates(expired) // Pending candidates are processed before maintenance removes raw text.
                @Suppress("UNCHECKED_CAST")
                val candidate = db.candidate(id, expired)
                assertEquals(false, candidate["originalAvailable"])
                assertEquals("Correção inventada", (candidate["review"] as Map<String, Any?>)["description"])
                assertEquals(12345L, ((candidate["review"] as Map<String, Any?>)["amountCents"] as Number).toLong())
            }
        }
    }

    @Test fun statementStagingIsIdempotentPreservesEqualRowsAndDoesNotEnableCapture() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            DiagnosticStore(sandbox.context, sandbox.newName(), true).use { db ->
                val identicalPurchase = StatementRow("2026-10-30", -9900, "Compra inventada", null, "-99,00", "30/10/2026")
                val document = StatementDocument("CSV", null, listOf(identicalPurchase, identicalPurchase.copy()))
                val digest = "c".repeat(64)
                assertEquals(2, db.stageStatement(document, digest, start)["count"])
                val retry = db.stageStatement(document, digest, start)
                assertEquals(0, retry["count"])
                assertEquals(2, retry["existing"])
                assertEquals(2, db.samples(start).size)
                assertEquals(false, db.state(start)["active"])
                assertEquals(1, db.statementBatches().size)
            }
        }
    }

    @Test fun internalOpeningAllowsHistoricalEqualExpensesAndLinkedObservationSurvivesBackupV2() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            val sourceName = sandbox.newName()
            val eventId = UUID.randomUUID().toString()
            var firstId = ""
            var secondId = ""
            var backup = ByteArray(0)
            ClassificationCatalog(sandbox.context, sourceName, true).use { catalog ->
                val ledger = catalog.ledger()
                ledger.startTracking()
                firstId = ledger.post(expense(UUID.randomUUID().toString(), "2020-06-15"))
                secondId = ledger.post(expense(UUID.randomUUID().toString(), "2020-06-15"))
                val historic = ledger.snapshot("2020-06")
                assertEquals(2, historic["total"])
                assertEquals(-2468L, historic["balanceCents"])
                assertTrue(firstId != secondId)

                val source = mapOf(
                    "eventId" to eventId,
                    "packageName" to DiagnosticPolicy.PACKAGE,
                    "sourceKey" to "invented-observation-source",
                    "revision" to 1,
                    "publishedAt" to start.wall.toString(),
                    "capturedAt" to start.wall.toString(),
                    "parserVersion" to BrlNotificationParser.VERSION,
                    "fieldEvidence" to emptyMap<String, Any?>(),
                    "confidence" to mapOf(
                        "movement" to "certain", "amount" to "certain", "nature" to "certain",
                        "account" to "missing", "date" to "missing", "category" to "missing"
                    )
                )
                ledger.linkObservation(eventId, firstId, source)
                ledger.linkObservation(eventId, firstId, source)
                assertEquals(firstId, ledger.transactionForEvent(eventId)?.get("id"))
                assertEquals(1, ledger.rows("finance_observation_links").size)
                backup = catalog.exportFinance()
                assertEquals(2, JSONObject(backup.toString(Charsets.UTF_8)).get("version"))
            }

            try {
                ClassificationCatalog(sandbox.context, sandbox.newName(), true).use { restored ->
                    restored.importFinance(backup)
                    val ledger = restored.ledger()
                    assertEquals(firstId, ledger.transactionForEvent(eventId)?.get("id"))
                    assertEquals(1, ledger.rows("finance_observation_links").size)
                    assertEquals(2, ledger.snapshot("2020-06")["total"])
                    assertEquals(-2468L, ledger.snapshot("2020-06")["balanceCents"])
                    assertTrue(ledger.rows("finance_transactions").any { it["id"] == secondId })
                }
            } finally { backup.fill(0) }
        }
    }

    @Test fun financialBackupV1WithoutLinksRestoresManualTransactionsAndCreatesEmptyLinkTable() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            var backup = ByteArray(0)
            var transactionId = ""
            val today = LocalDate.now().toString()
            ClassificationCatalog(sandbox.context, sandbox.newName(), true).use { catalog ->
                catalog.ledger().startTracking()
                transactionId = catalog.ledger().post(expense(UUID.randomUUID().toString(), today))
                backup = catalog.exportFinance()
            }

            try {
                val oldRoot = JSONObject(backup.toString(Charsets.UTF_8))
                oldRoot.put("version", 1)
                oldRoot.getJSONObject("data").remove("finance_observation_links")
                val legacyBackup = oldRoot.toString().toByteArray(Charsets.UTF_8)
                try {
                    ClassificationCatalog(sandbox.context, sandbox.newName(), true).use { restored ->
                        restored.importFinance(legacyBackup)
                        val ledger = restored.ledger()
                        assertTrue(ledger.rows("finance_transactions").any { it["id"] == transactionId })
                        assertNull(ledger.transactionForEvent(UUID.randomUUID().toString()))
                        assertTrue(ledger.rows("finance_observation_links").isEmpty())
                    }
                } finally { legacyBackup.fill(0) }
            } finally { backup.fill(0) }
        }
    }

    @Test fun observedBatchIsAtomicIdempotentKeepsEqualRowsAndReportsMonths() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            ClassificationCatalog(sandbox.context, sandbox.newName(), true).use { catalog ->
                val ledger = catalog.ledger()
                ledger.startTracking()
                val category = catalog.snapshot().first { it["kind"] == "category" }
                val categoryId = category["id"] as String
                val categoryName = category["name"] as String
                catalog.create("tag", "Etiqueta de lote")
                val tagId = catalog.snapshot().first { it["kind"] == "tag" }["id"] as String
                val items = listOf(
                    batchPost(UUID.randomUUID().toString(), "2020-01-14", categoryId, categoryName, listOf(tagId)),
                    batchPost(UUID.randomUUID().toString(), "2020-01-14", categoryId, categoryName, listOf(tagId)),
                    batchPost(UUID.randomUUID().toString(), "2021-02-15", categoryId, categoryName, listOf(tagId))
                )
                val first = ledger.confirmObservedBatch(items)
                assertEquals(3, first["registered"])
                assertEquals(listOf("2020-01", "2021-02"), first["months"])
                assertEquals(2, ledger.snapshot("2020-01")["total"])
                assertEquals(1, ledger.snapshot("2021-02")["total"])

                catalog.rename(categoryId, "Categoria fictícia renomeada")
                catalog.rename(tagId, "Etiqueta fictícia renomeada")
                val retry = ledger.confirmObservedBatch(items)
                assertEquals(3, retry["registered"])
                assertEquals(first["months"], retry["months"])
                assertEquals(4, ledger.rows("finance_transactions").size) // Includes the internal opening row.

                val before = ledger.rows("finance_transactions").size
                val invalidCategory = UUID.randomUUID().toString()
                val invalidBatch = listOf(
                    batchPost(UUID.randomUUID().toString(), "2022-03-16", categoryId, "Categoria fictícia renomeada", listOf(tagId)),
                    batchPost(UUID.randomUUID().toString(), "2022-04-17", invalidCategory, "A classificar", emptyList())
                )
                val error = try { ledger.confirmObservedBatch(invalidBatch);null } catch (failure: DiagnosticFailure) { failure.code }
                assertEquals("CLASSIFICATION_MISSING", error)
                assertEquals(before, ledger.rows("finance_transactions").size)
            }
        }
    }
}
