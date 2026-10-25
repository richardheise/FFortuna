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
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ffortuna-capture") }
    private var boxes: CaptureBoxes? = null
    @Volatile var connected = false
    @Volatile var lastError: String? = null
    private fun preferences(context: Context) = context.getSharedPreferences("capture-consent",Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean("enabled",false)
    private fun boxes(context:Context):CaptureBoxes=boxes ?: CaptureBoxes(context.applicationContext).also { boxes=it }
    private fun database(context: Context): CaptureStore = boxes(context).current()
    fun <T> call(block: () -> T): T = executor.submit<T> { block() }.get()
    fun enqueue(context: Context, block: (CaptureStore) -> Unit) {
        executor.execute {
            try { block(database(context)); lastError=null }
            catch (error: Exception) { lastError=storageFailureMessage(error) }
        }
    }
    fun state(context: Context): Map<String,Any?> = call {
        val manager = context.getSystemService(NotificationManager::class.java)
        val component = ComponentName(context,SyntheticNotificationListener::class.java)
        val allowed = if(Build.VERSION.SDK_INT >= 27) manager.isNotificationListenerAccessGranted(component)
            else Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners")
                ?.split(":")?.contains(component.flattenToString()) == true
        val common = mapOf("enabled" to enabled(context),"authorized" to allowed,"connected" to connected,
            "notificationsAllowed" to manager.areNotificationsEnabled(),"sourcePackage" to context.packageName,
            "lastError" to lastError,"syntheticOnly" to true,
            "previousBoxAvailable" to (preferences(context).getString("previous-box",null)!=null))
        try {
            val db=database(context); db.processPending()
            common + db.snapshot() + mapOf("storageReady" to true)
        } catch (error: Exception) {
            common + mapOf("storageReady" to false,"lastError" to storageFailureMessage(error))
        }
    }
    fun setEnabled(context:Context,value:Boolean): Map<String,Any?> {
        call {
            if(value) { database(context).processPending() }
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
    fun reset(context:Context) {
        call {
            check(preferences(context).edit().putBoolean("enabled",false).commit())
            SyntheticEmitter.cancel(context)
            boxes(context).reset()
            lastError=null
        }
    }
}
