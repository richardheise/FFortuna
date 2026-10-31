package org.ffortuna.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Invented values exercise durable reserve/card journals and their accounting rules. */
@RunWith(AndroidJUnit4::class)
class FinancialResourcesTest {
    private fun uuid() = UUID.randomUUID().toString()
    private fun json(command: String, fields: JSONObject) = fields.put("commandId", command).toString()

    @Test fun reserveMovesAndCardInvoiceDoNotDoubleCountExpense() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            ClassificationCatalog(sandbox.context, sandbox.newName(), true).use { catalog ->
                val ledger = catalog.ledger()
                val date = LocalDate.now().toString()
                val month = date.take(7)
                ledger.setup(JSONObject().put("openingDate", date).put("openingBalanceCents", 20_000).toString())

                val createCommand = uuid()
                val create = JSONObject().put("name", "Viagem fictícia").put("modality", "goal").put("date", date).put("amountCents", 5_000)
                val reserveId = ledger.createReserve(json(createCommand, JSONObject(create.toString())))
                assertEquals(reserveId, ledger.createReserve(json(createCommand, JSONObject(create.toString()))))
                val depositCommand = uuid()
                val deposit = JSONObject().put("reserveId", reserveId).put("direction", "deposit").put("amountCents", 3_000).put("date", date)
                val moveId = ledger.moveReserve(json(depositCommand, JSONObject(deposit.toString())))
                assertEquals(moveId, ledger.moveReserve(json(depositCommand, JSONObject(deposit.toString()))))
                assertEquals(17_000L, ledger.snapshot(month)["balanceCents"])
                @Suppress("UNCHECKED_CAST")
                var reserves = ledger.snapshot(month)["reserves"] as List<Map<String, Any?>>
                assertEquals(8_000L, reserves.single()["balanceCents"])

                val adjustmentCommand = uuid()
                val adjustment = JSONObject().put("reserveId", reserveId).put("amountCents", 7_000).put("date", date).put("note", "Conferência fictícia")
                val adjustmentId = ledger.updateReserve(json(adjustmentCommand, JSONObject(adjustment.toString())))
                assertEquals(adjustmentId, ledger.updateReserve(json(adjustmentCommand, JSONObject(adjustment.toString()))))
                assertEquals(17_000L, ledger.snapshot(month)["balanceCents"])
                reserves = ledger.snapshot(month)["reserves"] as List<Map<String, Any?>>
                assertEquals(7_000L, reserves.single()["balanceCents"])

                val secondReserve = ledger.createReserve(json(uuid(), JSONObject().put("name", "Emergência fictícia")
                    .put("modality", "goal").put("date", date).put("amountCents", 1_000)))
                val transfer = JSONObject().put("fromReserveId", reserveId).put("toReserveId", secondReserve).put("amountCents", 2_000).put("date", date)
                val transferCommand = uuid()
                val transferId = ledger.transferReserve(json(transferCommand, JSONObject(transfer.toString())))
                assertEquals(transferId, ledger.transferReserve(json(transferCommand, JSONObject(transfer.toString()))))
                assertEquals(17_000L, ledger.snapshot(month)["balanceCents"])
                @Suppress("UNCHECKED_CAST")
                reserves = ledger.snapshot(month)["reserves"] as List<Map<String, Any?>>
                assertEquals(5_000L, reserves.single { it["id"] == reserveId }["balanceCents"])
                assertEquals(3_000L, reserves.single { it["id"] == secondReserve }["balanceCents"])
                assertEquals(0L, ledger.snapshot(month)["incomeCents"])
                assertEquals(0L, ledger.snapshot(month)["expenseCents"])
            }
        }
    }

    @Test fun cardAndReserveResourcesStartWithInternalZeroWithoutManualOpeningBalance() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            ClassificationCatalog(sandbox.context, sandbox.newName(), true).use { catalog ->
                val ledger = catalog.ledger()
                val date = LocalDate.now().toString()
                val reserveId = ledger.createReserve(json(uuid(), JSONObject().put("name", "Reserva inicial fictícia")
                    .put("modality", "goal").put("date", date).put("amountCents", 0)))
                ledger.moveReserve(json(uuid(), JSONObject().put("reserveId", reserveId).put("direction", "deposit")
                    .put("amountCents", 50).put("date", date)))
                val cardId = ledger.createCard(json(uuid(), JSONObject().put("name", "Cartão sem abertura")
                    .put("closingDay", 18).put("dueDay", 8)))
                val purchase = JSONObject().put("cardId", cardId).put("amountCents", 250).put("effectiveDate", date)
                    .put("description", "Compra fictícia sem caixa").put("category", "Compras").put("categoryId", JSONObject.NULL)
                    .put("tagIds", JSONArray()).put("notes", "")
                ledger.cardPurchase(json(uuid(), purchase))
                val snapshot = ledger.snapshot(date.take(7))
                assertEquals(true, snapshot["configured"])
                assertEquals(-50L, snapshot["balanceCents"])
                @Suppress("UNCHECKED_CAST")
                val reserves = snapshot["reserves"] as List<Map<String, Any?>>
                assertEquals(50L, reserves.single()["balanceCents"])
                assertEquals(250L, snapshot["expenseCents"])
            }
        }
    }

    @Test fun historicalTransactionsBeforeOpeningDateRemainVisibleAndOpeningAppliesOnItsDate() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            ClassificationCatalog(sandbox.context, sandbox.newName(), true).use { catalog ->
                val ledger = catalog.ledger()
                val openingDate = LocalDate.now().toString()
                ledger.setup(JSONObject().put("openingDate", openingDate).put("openingBalanceCents", 1_000).toString())
                val historical = JSONObject().put("commandId", uuid()).put("amountCents", 250).put("kind", "expense")
                    .put("effectiveDate", "2020-01-02").put("description", "Compra histórica fictícia")
                    .put("category", "Compras").put("categoryId", JSONObject.NULL).put("tagIds", JSONArray())
                ledger.post(historical.toString())
                assertEquals(-250L, ledger.snapshot("2020-01")["balanceCents"])
                assertEquals(750L, ledger.snapshot(openingDate.take(7))["balanceCents"])
            }
        }
    }

    @Test fun cardPurchaseAndPaymentSurviveV3BackupAndMetadataReclassification() {
        SecuritySandbox(InstrumentationRegistry.getInstrumentation().targetContext).use { sandbox ->
            val date = LocalDate.now().toString()
            val month = date.take(7)
            val book = sandbox.newName()
            var backup = ByteArray(0)
            var purchaseId = ""
            var cardId = ""
            var commitmentId = ""
            var observedEventId = ""
            ClassificationCatalog(sandbox.context, book, true).use { catalog ->
                val ledger = catalog.ledger()
                ledger.setup(JSONObject().put("openingDate", date).put("openingBalanceCents", 20_000).toString())
                cardId = ledger.createCard(json(uuid(), JSONObject().put("name", "Cartão fictício").put("closingDay", 20).put("dueDay", 5)))
                val purchase = JSONObject().put("cardId", cardId).put("amountCents", 4_500).put("effectiveDate", date)
                    .put("description", "Compra teste mercado").put("category", "Compras").put("categoryId", JSONObject.NULL)
                    .put("tagIds", JSONArray()).put("notes", "")
                val command = uuid()
                purchaseId = ledger.cardPurchase(json(command, JSONObject(purchase.toString())))
                assertEquals(purchaseId, ledger.cardPurchase(json(command, JSONObject(purchase.toString()))))
                observedEventId = uuid()
                val observedAt = java.time.Instant.now().toString()
                val source = mapOf("eventId" to observedEventId, "packageName" to DiagnosticPolicy.PACKAGE,
                    "sourceKey" to "invented-card-observation", "revision" to 1, "publishedAt" to observedAt,
                    "capturedAt" to observedAt, "parserVersion" to BrlNotificationParser.VERSION,
                    "fieldEvidence" to emptyMap<String, Any?>(), "confidence" to mapOf("movement" to "certain",
                        "amount" to "certain", "nature" to "certain", "account" to "missing", "date" to "certain", "category" to "missing"))
                ledger.linkObservation(observedEventId, purchaseId, source)
                ledger.linkObservation(observedEventId, purchaseId, source)
                assertEquals(purchaseId, ledger.transactionForEvent(observedEventId)?.get("id"))
                assertEquals("extended", ledger.transactionForEvent(observedEventId)?.get("scope"))
                val beforePay = ledger.snapshot(month)
                assertEquals(4_500L, beforePay["expenseCents"])
                assertEquals(20_000L, beforePay["balanceCents"])
                @Suppress("UNCHECKED_CAST")
                val pending = (beforePay["commitments"] as List<Map<String, Any?>>).single()
                commitmentId = pending["id"] as String
                assertEquals(4_500L, pending["remainingCents"])
                val expectedInvoice = (if (LocalDate.parse(date).dayOfMonth > 20) LocalDate.parse(date).plusMonths(1) else LocalDate.parse(date)).toString().take(7)
                assertEquals(expectedInvoice, pending["invoiceMonth"])
                assertEquals(java.time.YearMonth.parse(expectedInvoice).plusMonths(1).atDay(5).toString(), pending["dueDate"])

                val paymentCommand = uuid()
                val payment = JSONObject().put("commitmentId", commitmentId).put("amountCents", 1_000).put("date", date)
                val paymentId = ledger.payCommitment(json(paymentCommand, JSONObject(payment.toString())))
                assertEquals(paymentId, ledger.payCommitment(json(paymentCommand, JSONObject(payment.toString()))))
                val afterPay = ledger.snapshot(month)
                assertEquals(4_500L, afterPay["expenseCents"])
                assertEquals(19_000L, afterPay["balanceCents"])
                @Suppress("UNCHECKED_CAST")
                val partlyPaid = (afterPay["commitments"] as List<Map<String, Any?>>).single()
                assertEquals(1_000L, partlyPaid["paidCents"])
                assertEquals(3_500L, partlyPaid["remainingCents"])

                val categoryId = catalog.snapshot().first { it["kind"] == "category" }["id"] as String
                val tagId = catalog.create("tag", "Fictícia").first { it["kind"] == "tag" }["id"] as String
                val rule = ledger.saveMetadataRule(JSONObject().put("description", "Compra teste mercado")
                    .put("categoryId", categoryId).put("category", catalog.snapshot().first { it["id"] == categoryId }["name"])
                    .put("tagIds", JSONArray().put(tagId)).toString())
                assertEquals(1, ledger.previewMetadataRule(rule["id"] as String).size)
                val categoryName = catalog.snapshot().first { it["id"] == categoryId }["name"] as String
                val updated = ledger.reclassifyMetadata(JSONObject().put("items", JSONArray().put(JSONObject()
                    .put("transactionId", purchaseId).put("scope", "extended").put("categoryId", categoryId)
                    .put("category", categoryName).put("tagIds", JSONArray().put(tagId)))).put("ruleId", rule["id"]).toString())
                assertEquals(1, updated["updated"])
                assertEquals(1, ledger.rows("finance_metadata_audit").size)
                assertEquals("extended", ledger.rows("finance_metadata_audit").single()["transaction_scope"])
                val renamedCategory = "Categoria fictícia renomeada"
                catalog.rename(categoryId, renamedCategory)
                assertEquals(renamedCategory, ledger.listMetadataRules().single()["category"])
                assertEquals(renamedCategory, ledger.suggestMetadata("Compra teste mercado")?.get("category"))
                assertEquals(categoryName, ledger.rows("finance_metadata_audit").single()["next_category"])
                val currentFields=ledger.transactionForEvent(observedEventId)?.get("fields") as Map<*, *>
                assertEquals(categoryId,currentFields["categoryId"])
                assertEquals(renamedCategory,currentFields["category"])
                assertEquals(listOf(tagId),currentFields["tagIds"])
                catalog.setArchived(categoryId, true)
                assertEquals(null, ledger.suggestMetadata("Compra teste mercado"))
                backup = catalog.exportFinance()
                assertEquals(3, JSONObject(backup.toString(Charsets.UTF_8)).getInt("version"))
            }
            try {
                ClassificationCatalog(sandbox.context, sandbox.newName(), true).use { restored ->
                    restored.importFinance(backup)
                    val snapshot = restored.ledger().snapshot(month)
                    assertEquals(4_500L, snapshot["expenseCents"])
                    assertEquals(19_000L, snapshot["balanceCents"])
                    @Suppress("UNCHECKED_CAST")
                    val transactions = snapshot["transactions"] as List<Map<String, Any?>>
                    val restoredPurchase = transactions.single { it["id"] == purchaseId }
                    assertEquals("extended", restoredPurchase["scope"])
                    assertEquals("card_purchase", restoredPurchase["kind"])
                    assertEquals(cardId, restoredPurchase["resourceId"])
                    assertNotNull(restored.ledger().rows("finance_metadata_audit").singleOrNull())
                    @Suppress("UNCHECKED_CAST")
                    val cards = snapshot["cards"] as List<Map<String, Any?>>
                    assertEquals(3_500L, cards.single()["debtCents"])
                    assertTrue(snapshot["commitments"] is List<*>)
                    assertEquals(purchaseId, restored.ledger().transactionForEvent(observedEventId)?.get("id"))
                    assertEquals(1, restored.ledger().rows("finance_extended_observation_links").size)
                }
                val v2 = JSONObject(backup.toString(Charsets.UTF_8)).put("version", 2)
                val oldTables = setOf("finance_reserves", "finance_cards", "finance_commitments", "finance_extended_transactions", "finance_extended_entries",
                    "finance_extended_tags", "finance_extended_cancellations", "finance_extended_observation_links", "finance_metadata_rules", "finance_metadata_audit")
                oldTables.forEach(v2.getJSONObject("data")::remove)
                val legacy = v2.toString().toByteArray(Charsets.UTF_8)
                try {
                    ClassificationCatalog(sandbox.context, sandbox.newName(), true).use { oldRestore ->
                        oldRestore.importFinance(legacy)
                        val oldSnapshot = oldRestore.ledger().snapshot(month)
                        assertEquals(0, (oldSnapshot["cards"] as List<*>).size)
                        assertTrue(oldSnapshot["configured"] == true)
                    }
                } finally { legacy.fill(0) }
            } finally { backup.fill(0) }
        }
    }
}
