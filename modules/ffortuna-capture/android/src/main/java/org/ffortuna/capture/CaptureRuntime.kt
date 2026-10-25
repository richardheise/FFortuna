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
            "securityReport" to securityReport)
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
        NativeSecurityRunner.run(context).also { securityReport=it }
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
