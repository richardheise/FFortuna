package org.ffortuna.capture

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.provider.DocumentsContract
import expo.modules.kotlin.activityresult.AppContextActivityResultLauncher
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class FFortunaCaptureModule:Module() {
    private lateinit var createBackup:AppContextActivityResultLauncher<String,Uri?>
    private lateinit var openStatement:AppContextActivityResultLauncher<String,Uri?>
    private lateinit var openBackup:AppContextActivityResultLauncher<String,Uri?>
    private val backupBusy=AtomicBoolean(false)
    private fun context() = requireNotNull(appContext.reactContext).applicationContext
    private suspend fun backupAction(block:suspend () -> Map<String,Any>):Map<String,Any> {
        if(!backupBusy.compareAndSet(false,true)) return mapOf("status" to "error","code" to "BUSY")
        return try { block() }
        catch(error:Exception) {
            var cause:Throwable=error
            while((cause is java.util.concurrent.ExecutionException || cause is java.util.concurrent.CompletionException) && cause.cause!=null) cause=cause.cause!!
            mapOf("status" to "error","code" to when(cause) { is BackupFailure -> cause.problem.name;is LocalRecoveryFailure -> cause.code;is FinanceFailure -> cause.code;is DiagnosticFailure -> cause.code;is StatementFailure -> cause.code;else -> "STORAGE" })
        }
        finally { backupBusy.set(false) }
    }
    private fun diagnosticAction(block:()->Map<String,Any?>):Map<String,Any?> = try { block() }
        catch(error:Exception) { mapOf("status" to "error","code" to DiagnosticCapture.safeCode(error)) }
    override fun definition() = ModuleDefinition {
        Name("FFortunaCapture")
        RegisterActivityContracts {
            createBackup=registerForActivityResult(BackupDocumentContract(true))
            openBackup=registerForActivityResult(BackupDocumentContract(false))
            openStatement=registerForActivityResult(StatementDocumentContract())
        }
        AsyncFunction("reconnectCapture") {
            android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(context(),SyntheticNotificationListener::class.java))
        }
        AsyncFunction("openBankApp") {
            val launch=context().packageManager.getLaunchIntentForPackage(DiagnosticPolicy.PACKAGE) ?: throw DiagnosticFailure("SOURCE_MISSING")
            context().startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        AsyncFunction("dailyState") { diagnosticAction { CaptureRuntime.dailyState(context()) } }
        AsyncFunction("startTracking") { diagnosticAction { mapOf("status" to "ok","snapshot" to CaptureRuntime.startTracking(context())) } }
        AsyncFunction("startDailyCapture") { signer:String -> diagnosticAction { CaptureRuntime.startDailyCapture(context(),signer);mapOf("status" to "ok") } }
        AsyncFunction("linkObserved") { id:String,transactionId:String -> diagnosticAction { mapOf("status" to "ok","snapshot" to CaptureRuntime.linkObserved(context(),id,transactionId)) } }
        AsyncFunction("dismissSharedStatement") { StatementIntake.clear() }
        AsyncFunction("importSharedStatement") Coroutine { -> backupAction { withContext(Dispatchers.IO) { StatementIntake.importPending(context()) as Map<String,Any> } } }
        AsyncFunction("chooseStatement") Coroutine { -> backupAction {
            val uri=withContext(Dispatchers.Main) { openStatement.launch("open") }
            if(uri==null) mapOf("status" to "cancelled") else withContext(Dispatchers.IO) { StatementIntake.read(context(),uri) as Map<String,Any> }
        } }
        AsyncFunction("financeSnapshot") { month:String -> diagnosticAction { mapOf("status" to "ok","snapshot" to CaptureRuntime.financeSnapshot(context(),month)) } }
        AsyncFunction("setupFinance") { json:String -> diagnosticAction { mapOf("status" to "ok","snapshot" to CaptureRuntime.setupFinance(context(),json)) } }
        AsyncFunction("createFinanceTransaction") { json:String -> diagnosticAction { mapOf("status" to "ok")+CaptureRuntime.createFinanceTransaction(context(),json) } }
        AsyncFunction("recordFinanceBalance") { json:String -> diagnosticAction { mapOf("status" to "ok","snapshot" to CaptureRuntime.recordFinanceBalance(context(),json)) } }
        AsyncFunction("cancelFinanceTransaction") { json:String -> diagnosticAction { mapOf("status" to "ok","snapshot" to CaptureRuntime.cancelFinanceTransaction(context(),json)) } }
        AsyncFunction("restartFinance") { diagnosticAction { mapOf("status" to "ok","snapshot" to CaptureRuntime.restartFinance(context())) } }
        AsyncFunction("confirmDiagnosticCandidate") { id:String,json:String -> diagnosticAction { mapOf("status" to "ok")+CaptureRuntime.confirmDiagnosticCandidate(context(),id,json) } }
        AsyncFunction("confirmObservedBatch") { json:String -> diagnosticAction { mapOf("status" to "ok")+CaptureRuntime.confirmObservedBatch(context(),json) } }
        AsyncFunction("switchPreviousFinance") { diagnosticAction { mapOf("status" to "ok","snapshot" to CaptureRuntime.switchPreviousFinance(context())) } }
        AsyncFunction("discardPreviousFinance") { diagnosticAction { mapOf("status" to "ok","snapshot" to CaptureRuntime.discardPreviousFinance(context())) } }
        AsyncFunction("financeRecoveryEntries") { CaptureRuntime.financeRecoveryEntries(context()) }
        AsyncFunction("setFinanceRecoveryEnabled") { enabled:Boolean -> CaptureRuntime.setFinanceRecoveryEnabled(context(),enabled) }
        AsyncFunction("clearFinanceRecovery") { CaptureRuntime.clearFinanceRecovery(context()) }
        AsyncFunction("restoreFinanceRecovery") Coroutine { id:String -> backupAction {
            withContext(Dispatchers.IO) { mapOf("status" to "restored","count" to CaptureRuntime.restoreFinanceRecovery(context(),id)) }
        } }
        AsyncFunction("exportFinanceBackup") Coroutine { password:String -> backupAction {
            val chars=password.toCharArray()
            val encrypted=try { withContext(Dispatchers.IO) {
                BackupCodec.validatePassword(chars)
                val plain=CaptureRuntime.exportFinanceBackup(context())
                try { BackupCodec.encrypt(plain,chars) } finally { plain.fill(0) }
            } } finally { chars.fill('\u0000') }
            try {
                val uri=withContext(Dispatchers.Main) { createBackup.launch("Tique-financeiro-${java.time.LocalDate.now()}.ffortuna") }
                if(uri==null) mapOf("status" to "cancelled")
                else withContext(Dispatchers.IO) {
                    try { context().contentResolver.openOutputStream(uri,"w")?.use { it.write(encrypted);it.flush() } ?: throw BackupFailure(BackupProblem.IO) }
                    catch (_:Exception) { try { DocumentsContract.deleteDocument(context().contentResolver,uri) } catch (_:Exception) { };throw BackupFailure(BackupProblem.IO) }
                    mapOf("status" to "saved")
                }
            } finally { encrypted.fill(0) }
        } }
        AsyncFunction("restoreFinanceBackup") Coroutine { password:String -> backupAction {
            val chars=password.toCharArray()
            try {
                BackupCodec.validatePassword(chars)
                val uri=withContext(Dispatchers.Main) { openBackup.launch("open") }
                if(uri==null) mapOf("status" to "cancelled")
                else withContext(Dispatchers.IO) {
                    val encrypted=try { context().contentResolver.openInputStream(uri)?.use(BackupCodec::readBounded) ?: throw BackupFailure(BackupProblem.IO) }
                        catch(error:BackupFailure) { throw error } catch (_:Exception) { throw BackupFailure(BackupProblem.IO) }
                    val plain=try { BackupCodec.decrypt(encrypted,chars) } finally { encrypted.fill(0) }
                    try { mapOf("status" to "restored","count" to CaptureRuntime.restoreFinanceBackup(context(),plain)) } finally { plain.fill(0) }
                }
            } finally { chars.fill('\u0000') }
        } }
        AsyncFunction("exportBackup") Coroutine { password:String -> backupAction {
            val chars=password.toCharArray()
            val bytes=try { withContext(Dispatchers.IO) {
                BackupCodec.validatePassword(chars)
                val plain=CaptureRuntime.exportBackup(context())
                try { BackupCodec.encrypt(plain,chars) } finally { plain.fill(0) }
            } } finally { chars.fill('\u0000') }
            try {
                val uri=withContext(Dispatchers.Main) { createBackup.launch("Tique-sintetico-${java.time.LocalDate.now()}.ffortuna") }
                if(uri==null) mapOf("status" to "cancelled")
                else withContext(Dispatchers.IO) {
                    try {
                        context().contentResolver.openOutputStream(uri,"w")?.use { it.write(bytes);it.flush() }
                            ?: throw BackupFailure(BackupProblem.IO)
                    } catch (_:Exception) {
                        // A partial document contains ciphertext only; best-effort removal on provider failure.
                        try { DocumentsContract.deleteDocument(context().contentResolver,uri) } catch (_:Exception) { }
                        throw BackupFailure(BackupProblem.IO)
                    }
                    mapOf("status" to "saved")
                }
            } finally { bytes.fill(0) }
        } }
        AsyncFunction("restoreBackup") Coroutine { password:String -> backupAction {
            val chars=password.toCharArray()
            try {
                BackupCodec.validatePassword(chars)
                val uri=withContext(Dispatchers.Main) { openBackup.launch("open") }
                if(uri==null) mapOf("status" to "cancelled")
                else withContext(Dispatchers.IO) {
                    val bytes=try { context().contentResolver.openInputStream(uri)?.use(BackupCodec::readBounded)
                        ?: throw BackupFailure(BackupProblem.IO) }
                        catch(error:BackupFailure) { throw error }
                        catch (_:Exception) { throw BackupFailure(BackupProblem.IO) }
                    val plain=try { BackupCodec.decrypt(bytes,chars) } finally { bytes.fill(0) }
                    val backup=try { InboxBackup.decode(plain) } finally { plain.fill(0) }
                    mapOf("status" to "restored","count" to CaptureRuntime.restoreBackup(context(),backup),
                        "unavailable" to backup.observations.count { it.state=="pending" })
                }
            } finally { chars.fill('\u0000') }
        } }
        AsyncFunction("classifications") { diagnosticAction { mapOf("status" to "ok","items" to CaptureRuntime.classifications(context())) } }
        AsyncFunction("createClassification") { kind:String,name:String -> diagnosticAction { mapOf("status" to "ok","items" to CaptureRuntime.createClassification(context(),kind,name)) } }
        AsyncFunction("renameClassification") { id:String,name:String -> diagnosticAction { mapOf("status" to "ok","items" to CaptureRuntime.renameClassification(context(),id,name)) } }
        AsyncFunction("archiveClassification") { id:String,archived:Boolean -> diagnosticAction { mapOf("status" to "ok","items" to CaptureRuntime.archiveClassification(context(),id,archived)) } }
        AsyncFunction("diagnosticCandidate") { id:String,force:Boolean -> diagnosticAction {
            mapOf("status" to "ok","candidate" to CaptureRuntime.diagnosticCandidate(context(),id,force))
        } }
        AsyncFunction("saveDiagnosticCandidate") { id:String,json:String -> diagnosticAction {
            mapOf("status" to "ok","candidate" to CaptureRuntime.saveDiagnosticCandidate(context(),id,json))
        } }
        AsyncFunction("diagnosticSnapshot") { CaptureRuntime.diagnosticSnapshot(context()) }
        AsyncFunction("startDiagnostic") { signer:String -> diagnosticAction { CaptureRuntime.startDiagnostic(context(),signer);mapOf("status" to "ok") } }
        AsyncFunction("stopDiagnostic") { diagnosticAction { CaptureRuntime.stopDiagnostic(context());mapOf("status" to "ok") } }
        AsyncFunction("clearDiagnostic") { diagnosticAction { CaptureRuntime.clearDiagnostic(context());mapOf("status" to "ok") } }
        AsyncFunction("diagnosticSamples") { diagnosticAction { mapOf("status" to "ok","samples" to CaptureRuntime.diagnosticSamples(context())) } }
        AsyncFunction("diagnosticSample") { id:String -> diagnosticAction { mapOf("status" to "ok","sample" to CaptureRuntime.diagnosticSample(context(),id)) } }
        AsyncFunction("reviewDiagnostic") { id:String,json:String,confirmed:Boolean -> diagnosticAction {
            CaptureRuntime.reviewDiagnostic(context(),id,json,confirmed);mapOf("status" to "ok")
        } }
        AsyncFunction("excludeDiagnosticReview") { id:String -> diagnosticAction {
            CaptureRuntime.excludeDiagnosticReview(context(),id);mapOf("status" to "ok")
        } }
        AsyncFunction("verifyDiagnosticExport") Coroutine { password:String -> backupAction {
            val chars=password.toCharArray()
            try {
                BackupCodec.validatePassword(chars)
                val uri=withContext(Dispatchers.Main) { openBackup.launch("open") }
                if(uri==null) mapOf("status" to "cancelled")
                else withContext(Dispatchers.IO) {
                    val bytes=try { context().contentResolver.openInputStream(uri)?.use(BackupCodec::readBounded)
                        ?: throw BackupFailure(BackupProblem.IO) } catch(error:BackupFailure) { throw error }
                        catch (_:Exception) { throw BackupFailure(BackupProblem.IO) }
                    val plain=try { BackupCodec.decrypt(bytes,chars) } finally { bytes.fill(0) }
                    val count=try { DiagnosticBundle.validate(plain) } finally { plain.fill(0) }
                    mapOf("status" to "verified","count" to count)
                }
            } finally { chars.fill('\u0000') }
        } }
        AsyncFunction("exportDiagnostic") Coroutine { password:String -> backupAction {
            val chars=password.toCharArray()
            val bytes=try { withContext(Dispatchers.IO) {
                BackupCodec.validatePassword(chars)
                val plain=try { CaptureRuntime.exportDiagnostic(context()) }
                    catch(error:Exception) { throw DiagnosticFailure(DiagnosticCapture.safeCode(error)) }
                try { BackupCodec.encrypt(plain,chars) } finally { plain.fill(0) }
            } } finally { chars.fill('\u0000') }
            try {
                val uri=withContext(Dispatchers.Main) { createBackup.launch("Tique-amostras-revisadas.ffortuna-samples") }
                if(uri==null) mapOf("status" to "cancelled")
                else withContext(Dispatchers.IO) {
                    try {
                        context().contentResolver.openOutputStream(uri,"w")?.use { it.write(bytes);it.flush() }
                            ?: throw BackupFailure(BackupProblem.IO)
                    } catch (_:Exception) {
                        try { DocumentsContract.deleteDocument(context().contentResolver,uri) } catch (_:Exception) { }
                        throw BackupFailure(BackupProblem.IO)
                    }
                    mapOf("status" to "saved")
                }
            } finally { bytes.fill(0) }
        } }
        AsyncFunction("localRecoveryEntries") { CaptureRuntime.localRecoveryEntries(context()) }
        AsyncFunction("setLocalRecoveryEnabled") { enabled:Boolean -> CaptureRuntime.setLocalRecoveryEnabled(context(),enabled) }
        AsyncFunction("clearLocalRecovery") { CaptureRuntime.clearLocalRecovery(context()) }
        AsyncFunction("restoreLocalRecovery") Coroutine { id:String -> backupAction {
            withContext(Dispatchers.IO) {
                mapOf("status" to "restored","count" to CaptureRuntime.restoreLocalRecovery(context(),id))
            }
        } }
        AsyncFunction("maintainRetention") {
            CaptureRuntime.maintainRetention(context());CaptureRuntime.state(context())
        }
        AsyncFunction("runSecurityChecks") { CaptureRuntime.runSecurityChecks(context()) }
        AsyncFunction("switchPreviousBox") { CaptureRuntime.switchPrevious(context()) }
        AsyncFunction("discardPreviousBox") { CaptureRuntime.discardPrevious(context()) }
        AsyncFunction("snapshot") { CaptureRuntime.state(context()) }
        AsyncFunction("setEnabled") { enabled:Boolean -> CaptureRuntime.setEnabled(context(),enabled) }
        AsyncFunction("emit") { scenario:String -> SyntheticEmitter.emit(context(),scenario) }
        AsyncFunction("recover") { CaptureRuntime.recover(context()) }
        AsyncFunction("verify") { CaptureRuntime.verify(context()) }
        AsyncFunction("reset") { CaptureRuntime.reset(context()) }
        Function("replay") { CaptureRuntime.replay() }
        Function("openSettings") {
            context().startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Function("requestPostPermission") {
            if(Build.VERSION.SDK_INT>=33) {
                val activity=requireNotNull(appContext.currentActivity)
                activity.runOnUiThread { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),7892) }
            }
        }
    }
}
