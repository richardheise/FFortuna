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
    private lateinit var openBackup:AppContextActivityResultLauncher<String,Uri?>
    private val backupBusy=AtomicBoolean(false)
    private fun context() = requireNotNull(appContext.reactContext).applicationContext
    private suspend fun backupAction(block:suspend () -> Map<String,Any>):Map<String,Any> {
        if(!backupBusy.compareAndSet(false,true)) return mapOf("status" to "error","code" to "BUSY")
        return try { block() }
        catch(error:Exception) { mapOf("status" to "error","code" to when(error) { is BackupFailure -> error.problem.name; is LocalRecoveryFailure -> error.code; is DiagnosticFailure -> error.code; else -> "STORAGE" }) }
        finally { backupBusy.set(false) }
    }
    private fun diagnosticAction(block:()->Map<String,Any?>):Map<String,Any?> = try { block() }
        catch(error:Exception) { mapOf("status" to "error","code" to DiagnosticCapture.safeCode(error)) }
    override fun definition() = ModuleDefinition {
        Name("FFortunaCapture")
        RegisterActivityContracts {
            createBackup=registerForActivityResult(BackupDocumentContract(true))
            openBackup=registerForActivityResult(BackupDocumentContract(false))
        }
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
