package org.ffortuna.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.UUID
import java.time.Instant

class SyntheticNotificationListener : NotificationListenerService() {
    companion object { @Volatile var instance: SyntheticNotificationListener? = null }
    override fun onListenerConnected() {
        instance=this; CaptureRuntime.connected=true
        CaptureRuntime.enqueue(this) { it.processPending() }
        CaptureRuntime.recoverObserved(this)
        replayActive()
    }
    override fun onListenerDisconnected() { instance=null; CaptureRuntime.connected=false }
    override fun onDestroy() { instance=null; CaptureRuntime.connected=false; super.onDestroy() }
    fun replayActive() {
        if(!CaptureRuntime.connected) return
        try { activeNotifications?.forEach { observe(it) } }
        catch (_: Exception) { CaptureRuntime.lastError="Conexão de notificações indisponível; reabra as configurações de acesso." }
    }
    override fun onNotificationPosted(sbn:StatusBarNotification) { observe(sbn) }
    // Dismissing a notification deliberately has no effect on persistent observations.
    override fun onNotificationRemoved(sbn:StatusBarNotification) { }
    private fun observe(sbn:StatusBarNotification) {
        // The real diagnostic allowlist is separate from the synthetic capture switch.
        if(sbn.packageName==DiagnosticPolicy.PACKAGE) {
            if(sbn.user!=android.os.Process.myUserHandle()) return
            CaptureRuntime.observeDiagnostic(this,sbn,DiagnosticSource.moment(this));return
        }
        if(!CaptureIdentity.accepts(sbn.packageName,packageName,
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,CaptureRuntime.enabled(this))) return
        // Source and summary are filtered BEFORE reading notification content/extras.
        val extras=sbn.notification.extras
        val generation=extras.getString("ffortuna.synthetic.generation") ?: return
        val payload=extras.getString("ffortuna.synthetic.payload") ?: return
        if(payload.length>4096) return
        try {
            UUID.fromString(generation)
            if(JSONObject(payload).optString("marker")!="ffortuna.synthetic.v1") return
        } catch (_: Exception) { return }
        val key=CaptureIdentity.sourceKey(sbn.key,generation)
        val capturedAt=Instant.now().toString()
        CaptureRuntime.enqueue(this) { db ->
            if(CaptureRuntime.enabled(this)) { db.observe(key,payload,sbn.postTime,capturedAt); db.processPending() }
        }
    }
}
