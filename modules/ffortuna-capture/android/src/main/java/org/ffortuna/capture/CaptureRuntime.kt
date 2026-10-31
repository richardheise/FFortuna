package org.ffortuna.capture

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import java.util.concurrent.Executors

/** One native writer for migrations, observations, recovery and reads. No JS callback dependency. */
object CaptureRuntime {
    private const val RETENTION_WARNING="Limpeza de bruto bloqueada; captura pausada. Confira a retenção abaixo."
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ffortuna-capture") }
    private var diagnostic:DiagnosticCapture?=null
    private var financeBoxes:FinanceBoxes?=null
    private var financeRecovery:LocalRecoveryStore?=null
    private var financeRecoveryError:String?=null
    private fun financeBoxes(context:Context)=financeBoxes ?: FinanceBoxes(context.applicationContext).also { financeBoxes=it }
    private fun catalog(context:Context)=financeBoxes(context).current()
    private fun financeRecovery(context:Context)=financeRecovery ?: LocalRecoveryStore(context.applicationContext,
        keyName="tique-finance-recovery",fileNamespace="finance-recovery",payloadInfo=FinancialBackup::info).also { financeRecovery=it }
    private fun financialRecoveryState(context:Context):Map<String,Any?> {
        val status=financeRecovery(context).status()
        return financeRecoveryError?.let { status+mapOf("status" to "blocked","failureCode" to it) } ?: status
    }
    private fun financialRecovery(context:Context) {
        try {
            if(!catalog(context).ledger().configured()) return
            val recovery=financeRecovery(context)
            if(recovery.needsSnapshot()) {
                val plain=catalog(context).exportFinance()
                try { recovery.maybeSavePayload(plain) } finally { plain.fill(0) }
            }
            financeRecoveryError=null
        } catch(error:Exception) { financeRecoveryError=if(error is LocalRecoveryFailure) error.code else "LOCAL_RECOVERY_FAILED" }
    }
    private fun financeState(context:Context,month:String=java.time.LocalDate.now().toString().take(7)):Map<String,Any?> {
        val snapshot=catalog(context).ledger().snapshot(month)
        financialRecovery(context)
        return snapshot+mapOf("bookId" to catalog(context).identity(),"recovery" to financialRecoveryState(context),"previousAvailable" to financeBoxes(context).hasPrevious(),"securityChecksPassed" to true)
    }
    fun financeSnapshot(context:Context,month:String)=call { financeState(context,month) }
    fun startTracking(context:Context)=call {
        catalog(context).verifyForUse();catalog(context).ledger().startTracking();financeState(context)
    }
    fun dailyState(context:Context)=call {
        val status=diagnosticStatus(context)
        val ledger=catalog(context).ledger();val confirmed=ledger.confirmedEvents()
        status+mapOf("pendingCount" to diagnostic(context).pendingIds().count { it !in confirmed },"configured" to ledger.configured(),"imports" to diagnostic(context).statementBatches(),"incomingFile" to StatementIntake.hasPending())
    }
    fun startDailyCapture(context:Context,signer:String)=call {
        if(!authorized(context) || !connected) throw DiagnosticFailure("ACCESS_REQUIRED")
        catalog(context).verifyForUse()
        if(financialRecoveryState(context)["status"]=="blocked") throw FinanceFailure("RECOVERY_BLOCKED")
        catalog(context).ledger().startTracking()
        diagnostic(context).start(signer,DiagnosticSource.inspect(context),DiagnosticSource.moment(context),daily=true)
        financialRecovery(context)
        NotificationListenerService.requestRebind(ComponentName(context,SyntheticNotificationListener::class.java))
    }
    fun stageStatement(context:Context,bytes:ByteArray)=call {
        val document=StatementParser.parse(bytes)
        catalog(context).verifyForUse();val ledger=catalog(context).ledger();ledger.startTracking()
        val staged=diagnostic(context).stageStatement(document,StatementStaging.digest(bytes),DiagnosticSource.moment(context))
        val ids=(staged["candidateIds"] as? List<*>)?.filterIsInstance<String>()?.distinct().orEmpty()
        val confirmed=ledger.confirmedEvents()
        (staged-"candidateIds")+mapOf("confirmed" to ids.count { it in confirmed },"pending" to ids.count { it !in confirmed })
    }
    fun recoverObserved(context:Context) { executor.execute { try { diagnostic(context).recover(DiagnosticSource.moment(context)) } catch (_:Exception) { } } }
    fun setupFinance(context:Context,json:String)=call {
        catalog(context).verifyForUse()
        if(financialRecoveryState(context)["status"]=="blocked") throw FinanceFailure("RECOVERY_BLOCKED")
        catalog(context).ledger().setup(json);financeState(context)
    }
    fun createFinanceTransaction(context:Context,json:String)=call {
        val id=catalog(context).ledger().post(json)
        mapOf("transactionId" to id,"snapshot" to financeState(context))
    }
    fun createFinanceReserve(context:Context,json:String)=call { val id=catalog(context).ledger().createReserve(json);financialRecovery(context);mapOf("id" to id,"snapshot" to financeState(context)) }
    fun moveFinanceReserve(context:Context,json:String)=call { val id=catalog(context).ledger().moveReserve(json);financialRecovery(context);mapOf("transactionId" to id,"snapshot" to financeState(context)) }
    fun transferFinanceReserve(context:Context,json:String)=call { val id=catalog(context).ledger().transferReserve(json);financialRecovery(context);mapOf("transactionId" to id,"snapshot" to financeState(context)) }
    fun updateFinanceReserve(context:Context,json:String)=call { val id=catalog(context).ledger().updateReserve(json);financialRecovery(context);mapOf("transactionId" to id,"snapshot" to financeState(context)) }
    fun createFinanceCard(context:Context,json:String)=call { val id=catalog(context).ledger().createCard(json);financialRecovery(context);mapOf("id" to id,"snapshot" to financeState(context)) }
    fun createFinanceCommitment(context:Context,json:String)=call { val id=catalog(context).ledger().createCommitment(json);financialRecovery(context);mapOf("id" to id,"snapshot" to financeState(context)) }
    fun createCardPurchase(context:Context,json:String)=call { val id=catalog(context).ledger().cardPurchase(json);financialRecovery(context);mapOf("transactionId" to id,"snapshot" to financeState(context)) }
    fun payFinanceCommitment(context:Context,json:String)=call { val id=catalog(context).ledger().payCommitment(json);financialRecovery(context);mapOf("transactionId" to id,"snapshot" to financeState(context)) }
    fun listFinanceRules(context:Context)=call { mapOf("status" to "ok","rules" to catalog(context).ledger().listMetadataRules()) }
    fun saveFinanceRule(context:Context,json:String)=call { val rule=catalog(context).ledger().saveMetadataRule(json);financialRecovery(context);mapOf("status" to "ok","rule" to rule) }
    fun setFinanceRuleEnabled(context:Context,id:String,enabled:Boolean)=call { val rule=catalog(context).ledger().setMetadataRuleEnabled(id,enabled);financialRecovery(context);mapOf("status" to "ok","rule" to rule) }
    fun previewFinanceRule(context:Context,id:String)=call { mapOf("status" to "ok","matches" to catalog(context).ledger().previewMetadataRule(id)) }
    fun reclassifyFinanceTransactions(context:Context,json:String)=call { val updated=catalog(context).ledger().reclassifyMetadata(json)["updated"];financialRecovery(context);mapOf("status" to "ok","updated" to updated,"snapshot" to financeState(context)) }
    fun recordFinanceBalance(context:Context,json:String)=call { catalog(context).ledger().recordBalance(json);financeState(context) }
    fun cancelFinanceTransaction(context:Context,json:String)=call { catalog(context).ledger().cancel(json);financeState(context) }
    fun restartFinance(context:Context)=call {
        financeBoxes(context).restartBook();try { diagnostic(context).stop() } catch (_:Exception) { };financeState(context)
    }
    fun exportFinanceBackup(context:Context):ByteArray=call { catalog(context).exportFinance() }
    fun restoreFinanceBackup(context:Context,plain:ByteArray):Int=call {
        val count=financeBoxes(context).restore(plain);try { diagnostic(context).stop() } catch (_:Exception) { };financialRecovery(context);count
    }
    fun financeRecoveryEntries(context:Context)=call { financeRecovery(context).entries().map { mapOf("id" to it.id,"createdAt" to it.createdAt,"count" to it.count) } }
    fun setFinanceRecoveryEnabled(context:Context,enabled:Boolean)=call { financeRecovery(context).setEnabled(enabled);financeRecoveryError=null }
    fun clearFinanceRecovery(context:Context)=call { financeRecovery(context).clearAndDisable();financeRecoveryError=null }
    fun restoreFinanceRecovery(context:Context,id:String):Int=call {
        val plain=financeRecovery(context).loadPayload(id)
        try { val count=financeBoxes(context).restore(plain);try { diagnostic(context).stop() } catch (_:Exception) { };financialRecovery(context);count } finally { plain.fill(0) }
    }
    fun switchPreviousFinance(context:Context)=call { financeBoxes(context).switchPrevious();try { diagnostic(context).stop() } catch (_:Exception) { };financeState(context) }
    fun discardPreviousFinance(context:Context)=call { financeBoxes(context).discardPrevious();financeState(context) }
    private fun diagnostic(context:Context)=diagnostic ?: DiagnosticCapture(context.applicationContext).also { diagnostic=it }
    private var boxes: CaptureBoxes? = null
    private var recovery:LocalRecoveryStore?=null
    private var recoveryError:String?=null
    @Volatile private var securityReport:Map<String,Any?>?=null
    @Volatile var connected = false
    @Volatile var lastError: String? = null
    private fun preferences(context: Context) = context.getSharedPreferences("capture-consent",Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean("enabled",false)
    private fun boxes(context:Context):CaptureBoxes=boxes ?: CaptureBoxes(context.applicationContext).also { boxes=it }
    private fun database(context: Context): CaptureStore = boxes(context).current()
    private fun recovery(context:Context):LocalRecoveryStore=recovery ?: LocalRecoveryStore(context.applicationContext).also { recovery=it }
    private fun recoveryState(context:Context):Map<String,Any?> {
        val state=recovery(context).status()
        return recoveryError?.let { state+mapOf("status" to "blocked","failureCode" to it) } ?: state
    }
    private fun automaticRecovery(context:Context,db:CaptureStore) {
        try {
            val local=recovery(context)
            if(local.needsSnapshot()) local.maybeSave(db.exportBackup())
            recoveryError=null
        } catch(error:Exception) { recoveryError=if(error is LocalRecoveryFailure) error.code else "LOCAL_RECOVERY_FAILED" }
    }
    private fun retention(context:Context,forcePrevious:Boolean=true):RetentionStatus {
        val result=boxes(context).maintainRetention(forcePrevious)
        if(result.blocked) {
            check(preferences(context).edit().putBoolean("enabled",false).commit())
            lastError=RETENTION_WARNING
        } else if(lastError==RETENTION_WARNING) lastError=null
        return result
    }
    fun <T> call(block: () -> T): T = executor.submit<T> { block() }.get()
    fun enqueue(context: Context, block: (CaptureStore) -> Unit) {
        executor.execute {
            try {
                // The observation's durable commit comes before retained-box maintenance.
                val db=database(context)
                block(db)
                lastError=null
                retention(context,false)
                automaticRecovery(context,db)
            }
            catch (error: Exception) { lastError=storageFailureMessage(error) }
        }
    }
    fun state(context: Context): Map<String,Any?> = call {
        val maintenance=try { retention(context).toMap() } catch (_:Exception) { mapOf("blocked" to true) }
        val manager = context.getSystemService(NotificationManager::class.java)
        val component = ComponentName(context,SyntheticNotificationListener::class.java)
        val allowed = if(Build.VERSION.SDK_INT >= 27) manager.isNotificationListenerAccessGranted(component)
            else Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners")
                ?.split(":")?.contains(component.flattenToString()) == true
        val common = mapOf("enabled" to enabled(context),"authorized" to allowed,"connected" to connected,
            "notificationsAllowed" to manager.areNotificationsEnabled(),"sourcePackage" to context.packageName,
            "lastError" to lastError,"syntheticOnly" to true,
            "previousBoxAvailable" to (preferences(context).getString("previous-box",null)!=null),"retention" to maintenance,
            "securityReport" to securityReport,"diagnostic" to diagnosticStatus(context))
        try {
            val db=database(context); db.processPending()
            automaticRecovery(context,db)
            common + db.snapshot() + mapOf("storageReady" to true,"localRecovery" to recoveryState(context))
        } catch (error: Exception) {
            common + mapOf("storageReady" to false,"lastError" to storageFailureMessage(error),"localRecovery" to recoveryState(context))
        }
    }
    fun setEnabled(context:Context,value:Boolean): Map<String,Any?> {
        call {
            if(value) { check(!retention(context).blocked) { "RETENTION_BLOCKED" };database(context).processPending() }
            check(preferences(context).edit().putBoolean("enabled",value).commit())
        }
        if(value) NotificationListenerService.requestRebind(ComponentName(context,SyntheticNotificationListener::class.java))
        return state(context)
    }
    fun replay() { SyntheticNotificationListener.instance?.replayActive() }
    fun recover(context:Context): Map<String,Any?> { call { database(context).processPending() }; return state(context) }
    fun verify(context:Context): Boolean = call { database(context).verifyEncrypted() }
    fun exportBackup(context:Context):ByteArray=call { database(context).exportBackup().encode() }
    fun restoreBackup(context:Context,backup:InboxBackup):Int=call {
        val count=boxes(context).restore(backup)
        SyntheticEmitter.cancel(context);lastError=null
        count
    }
    fun switchPrevious(context:Context)=call { boxes(context).switchPrevious();SyntheticEmitter.cancel(context);lastError=null }
    fun discardPrevious(context:Context)=call { boxes(context).discardPrevious() }
    fun maintainRetention(context:Context):Map<String,Any?> = call { retention(context).toMap() }
    fun localRecoveryEntries(context:Context):List<Map<String,Any>> = call {
        recovery(context).entries().map { mapOf("id" to it.id,"createdAt" to it.createdAt,"count" to it.count) }
    }
    fun setLocalRecoveryEnabled(context:Context,value:Boolean)=call { recovery(context).setEnabled(value);recoveryError=null }
    fun clearLocalRecovery(context:Context)=call { recovery(context).clearAndDisable();recoveryError=null }
    fun restoreLocalRecovery(context:Context,id:String):Int=call {
        val data=recovery(context).load(id)
        val count=boxes(context).restore(data)
        SyntheticEmitter.cancel(context);lastError=null;count
    }
    fun runSecurityChecks(context:Context):Map<String,Any?> = call {
        check(preferences(context).edit().putBoolean("enabled",false).commit())
        SyntheticEmitter.cancel(context)
        diagnostic(context).stop()
        NativeSecurityRunner.run(context).also { securityReport=it }
    }
    private fun authorized(context:Context):Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        val component=ComponentName(context,SyntheticNotificationListener::class.java)
        return if(Build.VERSION.SDK_INT>=27) manager.isNotificationListenerAccessGranted(component)
            else Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners")?.split(":")?.contains(component.flattenToString())==true
    }
    private fun diagnosticStatus(context:Context):Map<String,Any?> {
        val source=DiagnosticSource.inspect(context)
        val allowed=authorized(context)
        if(!allowed) try { diagnostic(context).stop() } catch (_:Exception) { }
        return diagnostic(context).status(DiagnosticSource.moment(context),source)+mapOf("source" to source,"authorized" to allowed,"connected" to connected,"securityChecksPassed" to NativeSecurityRunner.approved(securityReport))
    }
    fun diagnosticSnapshot(context:Context)=call { diagnosticStatus(context) }
    fun startDiagnostic(context:Context,signer:String)=call {
        if(!NativeSecurityRunner.approved(securityReport)) throw DiagnosticFailure("SECURITY_CHECKS_REQUIRED")
        if(!authorized(context) || !connected) throw DiagnosticFailure("ACCESS_REQUIRED")
        if(retention(context).blocked) throw DiagnosticFailure("RETENTION_BLOCKED")
        diagnostic(context).start(signer,DiagnosticSource.inspect(context),DiagnosticSource.moment(context))
    }
    fun stopDiagnostic(context:Context)=call { diagnostic(context).stop() }
    fun clearDiagnostic(context:Context)=call { diagnostic(context).clear() }
    fun diagnosticSamples(context:Context)=call {
        val samples=diagnostic(context).samples(DiagnosticSource.moment(context));val confirmed=catalog(context).ledger().confirmedEvents()
        samples.map { it+mapOf("transactionId" to confirmed[it["id"]]) }
    }
    fun diagnosticSample(context:Context,id:String)=call { diagnostic(context).sample(id,DiagnosticSource.moment(context)) }
    fun reviewDiagnostic(context:Context,id:String,json:String,confirmed:Boolean)=call { diagnostic(context).review(id,json,confirmed,DiagnosticSource.moment(context)) }
    fun excludeDiagnosticReview(context:Context,id:String)=call { diagnostic(context).excludeReview(id) }
    fun classifications(context:Context)=call { catalog(context).snapshot() }
    fun createClassification(context:Context,kind:String,name:String)=call { catalog(context).create(kind,name);catalog(context).snapshot() }
    fun renameClassification(context:Context,id:String,name:String)=call { catalog(context).rename(id,name);catalog(context).snapshot() }
    fun archiveClassification(context:Context,id:String,archived:Boolean)=call { catalog(context).setArchived(id,archived);catalog(context).snapshot() }
    fun diagnosticCandidate(context:Context,id:String,force:Boolean=false)=call {
        candidateWithFinance(context,diagnostic(context).candidate(id,DiagnosticSource.moment(context),force))
    }
    @Suppress("UNCHECKED_CAST")
    private fun candidateWithFinance(context:Context,candidate:Map<String,Any?>):Map<String,Any?> {
        val ledger=catalog(context).ledger()
        val confirmed=ledger.transactionForEvent(candidate["id"] as String)
        val parsed=candidate["parsed"] as Map<String,Any?>
        val originalDraft=parsed["draft"] as Map<String,Any?>
        val review=candidate["review"] as? Map<String,Any?>
        val suggestion=if(confirmed==null&&review==null) (originalDraft["description"] as? String)?.let { ledger.suggestMetadata(it) } else null
        val draft=if(suggestion!=null) originalDraft+mapOf("categoryId" to suggestion["categoryId"],"category" to suggestion["category"],"tagIds" to suggestion["tagIds"]) else originalDraft
        val describedCandidate=if(suggestion==null) candidate else candidate+
            mapOf("parsed" to (parsed+mapOf("draft" to draft)),"ruleExplanation" to mapOf(
                "ruleId" to suggestion["ruleId"],"description" to suggestion["description"],"text" to "Classificado pela sua regra."))
        val effectiveFields=review ?: draft
        val amount=(effectiveFields["amountCents"] as? Number)?.toLong()
        return ClassificationReview.detail(describedCandidate,catalog(context).snapshot())+mapOf("transactionId" to confirmed?.get("id"),"financialReview" to confirmed?.get("fields"),
            "possibleMatches" to if(confirmed==null) (ledger.matching(amount,effectiveFields["effectiveDate"] as? String)+(if(candidate["origin"]=="statement") ledger.statementMatches(candidate["sourceKey"] as String) else emptyList())).distinctBy { it["id"] } else emptyList<Map<String,Any?>>())
    }
    @Suppress("UNCHECKED_CAST")
    private fun candidateSource(candidate:Map<String,Any?>):Map<String,Any?> {
        val parsed=candidate["parsed"] as Map<String,Any?>
        return mapOf("eventId" to candidate["id"],"packageName" to if(candidate["origin"]=="statement") "org.tique.statement" else DiagnosticPolicy.PACKAGE,
            "sourceKey" to candidate["sourceKey"],"revision" to candidate["revision"],"publishedAt" to candidate["publishedAt"],"capturedAt" to candidate["capturedAt"],
            "parserVersion" to parsed["parserVersion"],"fieldEvidence" to parsed["fieldEvidence"],"confidence" to parsed["confidence"])
    }
    fun linkObserved(context:Context,id:String,transactionId:String)=call {
        val candidate=diagnostic(context).candidate(id,DiagnosticSource.moment(context))
        catalog(context).ledger().linkObservation(id,transactionId,candidateSource(candidate));financeState(context)
    }
    fun confirmDiagnosticCandidate(context:Context,id:String,json:String)=call {
        val candidate=diagnostic(context).candidate(id,DiagnosticSource.moment(context))
        val root=try { org.json.JSONObject(json) } catch (_:Exception) { throw FinanceFailure("INVALID_FIELDS") }
        if(root.opt("affectsAccount")!=true) throw FinanceFailure("ACCOUNT_CONFIRMATION_REQUIRED")
        root.remove("affectsAccount")
        catalog(context).ledger().startTracking()
        val transactionId=catalog(context).ledger().post(root.toString(),candidateSource(candidate))
        mapOf("transactionId" to transactionId,"snapshot" to financeState(context))
    }
    fun confirmObservedBatch(context:Context,json:String)=call {
        if(json.length>2*1024*1024) throw FinanceFailure("BATCH_LIMIT")
        val root=try { org.json.JSONObject(json) } catch (_:Exception) { throw FinanceFailure("INVALID_FIELDS") }
        if(root.keys().asSequence().toSet()!=setOf("items")) throw FinanceFailure("INVALID_FIELDS")
        val array=root.optJSONArray("items") ?: throw FinanceFailure("INVALID_FIELDS")
        if(array.length() !in 1..StatementParser.MAX_ROWS) throw FinanceFailure("BATCH_LIMIT")
        val now=DiagnosticSource.moment(context)
        val ledger=catalog(context).ledger()
        val seen=mutableSetOf<String>()
        val posts=mutableListOf<ObservedBatchPost>()
        for(index in 0 until array.length()) {
            val item=array.optJSONObject(index) ?: throw FinanceFailure("INVALID_FIELDS")
            if(item.keys().asSequence().toSet()!=setOf("id","kind","categoryId","category","tagIds")) throw FinanceFailure("INVALID_FIELDS")
            val id=item.optString("id")
            if(runCatching { java.util.UUID.fromString(id).toString()==id }.getOrDefault(false)!=true || !seen.add(id)) throw FinanceFailure("INVALID_FIELDS")
            val kind=item.optString("kind")
            if(kind !in setOf("income","expense")) throw FinanceFailure("UNSUPPORTED_MOVEMENT")
            val categoryId=if(item.isNull("categoryId")) null else item.opt("categoryId") as? String ?: throw FinanceFailure("INVALID_CLASSIFICATION")
            val category=item.opt("category") as? String ?: throw FinanceFailure("INVALID_CLASSIFICATION")
            FinanceRules.text(category,80)
            val tagArray=item.optJSONArray("tagIds") ?: throw FinanceFailure("INVALID_CLASSIFICATION")
            if(tagArray.length()>20) throw FinanceFailure("INVALID_CLASSIFICATION")
            val tagIds=(0 until tagArray.length()).map { tagArray.opt(it) as? String ?: throw FinanceFailure("INVALID_CLASSIFICATION") }
            if(tagIds.distinct().size!=tagIds.size) throw FinanceFailure("INVALID_CLASSIFICATION")

            val candidate=diagnostic(context).candidate(id,now)
            if(candidate["id"]!=id) throw DiagnosticFailure("SAMPLE_MISSING")
            val alreadyPosted=ledger.hasPostedEvent(id)
            if(ledger.hasObservationLink(id) && !alreadyPosted) throw FinanceFailure("OBSERVATION_ALREADY_LINKED")
            if(!alreadyPosted && (candidate["relatedReviewExists"]==true || candidate["hasLaterRevision"]==true || candidate["previousSampleId"]!=null)) throw FinanceFailure("OBSERVATION_CONFLICT")
            val review=candidate["review"] as? Map<String,Any?>
            if(review?.get("status")=="dismissed" && !alreadyPosted) throw FinanceFailure("OBSERVATION_DISMISSED")
            if(review?.get("kind") in setOf("transfer","other")) throw FinanceFailure("UNSUPPORTED_MOVEMENT")
            val parsed=candidate["parsed"] as? Map<String,Any?> ?: throw FinanceFailure("OBSERVATION_INVALID")
            val draft=parsed["draft"] as? Map<String,Any?> ?: throw FinanceFailure("OBSERVATION_INVALID")
            val confidence=parsed["confidence"] as? Map<String,Any?> ?: throw FinanceFailure("OBSERVATION_INVALID")
            if(!alreadyPosted && review?.get("status")!="reviewed" &&
                listOf("movement","amount","date").any { confidence[it]!="certain" }) throw FinanceFailure("OBSERVATION_REVIEW_REQUIRED")
            val values=review ?: draft
            val amount=when(val raw=values["amountCents"]) { is Int -> raw.toLong();is Long -> raw;else -> throw FinanceFailure("INVALID_AMOUNT") }
            FinanceRules.cents(amount)
            val date=values["effectiveDate"] as? String ?: throw FinanceFailure("INVALID_DATE")
            FinanceRules.date(date)
            val description=values["description"] as? String ?: throw FinanceFailure("INVALID_FIELDS")
            FinanceRules.text(description,500)
            val source=candidateSource(candidate)
            posts.add(ObservedBatchPost(id,amount,kind,date,description,category,categoryId,tagIds,source))
        }
        val result=ledger.confirmObservedBatch(posts)
        financialRecovery(context)
        result
    }
    @Suppress("UNCHECKED_CAST")
    fun saveDiagnosticCandidate(context:Context,id:String,json:String)=call {
        val now=DiagnosticSource.moment(context)
        val items=catalog(context).snapshot()
        val previous=diagnostic(context).candidate(id,now)["review"] as? Map<String,Any?>
        val checked=ClassificationReview.prepare(json,items,previous)
        candidateWithFinance(context,diagnostic(context).saveCandidate(id,checked,now))
    }
    fun exportDiagnostic(context:Context)=call { diagnostic(context).exportReviewed(DiagnosticSource.moment(context)) }
    fun observeDiagnostic(context:Context,sbn:android.service.notification.StatusBarNotification,received:DiagnosticMoment) {
        if(sbn.packageName!=DiagnosticPolicy.PACKAGE || sbn.user!=android.os.Process.myUserHandle()) return
        executor.execute {
            try {
                if(!authorized(context)) { diagnostic(context).stop();return@execute }
                diagnostic(context).observe(sbn,received,DiagnosticSource.moment(context))
            } catch (_:Exception) { try { diagnostic(context).stop() } catch (_:Exception) { } }
        }
    }
    fun reset(context:Context) {
        call {
            check(preferences(context).edit().putBoolean("enabled",false).commit())
            SyntheticEmitter.cancel(context)
            boxes(context).reset()
            lastError=null
        }
    }
}
