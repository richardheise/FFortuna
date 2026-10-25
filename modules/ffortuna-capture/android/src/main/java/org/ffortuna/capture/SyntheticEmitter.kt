package org.ffortuna.capture

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

object SyntheticEmitter {
    private const val CHANNEL="ffortuna-synthetic"
    private fun prefs(context:Context)=context.getSharedPreferences("synthetic-emitter",Context.MODE_PRIVATE)
    private fun alarm(context:Context): PendingIntent = PendingIntent.getBroadcast(context,7891,
        Intent(context,SyntheticAlarmReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun emit(context:Context,scenario:String) {
        check(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { "Autorize as notificações do emissor sintético." }
        when(scenario) {
            "single" -> post(context,false,false)
            "same" -> { post(context,false,false); post(context,false,false) }
            "update" -> post(context,true,false)
            "group" -> { post(context,false,true); post(context,false,true); summary(context) }
            "delayed" -> context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+15000,alarm(context))
            else -> error("Cenário sintético desconhecido.")
        }
    }
    private fun manager(context:Context)=context.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel(CHANNEL,"FFortuna · notificações fictícias",NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun post(context:Context,update:Boolean,group:Boolean) {
        val prefs=prefs(context)
        val old=prefs.getString("generation",null)
        val generation=if(update && old!=null) old else UUID.randomUUID().toString()
        val id=if(update && old!=null) prefs.getInt("id",100) else prefs.getInt("next-id",100)+1
        val revision=if(update && old!=null) prefs.getInt("revision",1)+1 else 1
        check(prefs.edit().putString("generation",generation).putInt("id",id).putInt("revision",revision).putInt("next-id",maxOf(id,prefs.getInt("next-id",100))).commit())
        val payload=JSONObject().put("marker","ffortuna.synthetic.v1").put("amountCents",if(revision==1) 1990 else 2990)
            .put("kind","expense").put("effectiveDate",LocalDate.now().toString()).put("effectiveAt",JSONObject.NULL)
            .put("description","Café fictício").put("category","Alimentação").put("accountRef","demo-main").put("externalId",generation)
        val extras=Bundle().apply { putString("ffortuna.synthetic.generation",generation); putString("ffortuna.synthetic.payload",payload.toString()) }
        val builder=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("FFortuna · EXEMPLO FICTÍCIO").setContentText("Café fictício · revisão $revision · sem movimentação bancária")
            .setAutoCancel(true).addExtras(extras)
        if(group) builder.setGroup("ffortuna-synthetic-group")
        manager(context).notify(id,builder.build())
    }
    private fun summary(context:Context) {
        manager(context).notify(42,Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("FFortuna · grupo fictício").setContentText("Resumo não representa uma movimentação")
            .setGroup("ffortuna-synthetic-group").setGroupSummary(true).build())
    }
    fun cancel(context:Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context))
        manager(context).cancelAll()
    }
}
class SyntheticAlarmReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        try { SyntheticEmitter.emit(context,"single") }
        catch (_: Exception) { CaptureRuntime.lastError="Emissão adiada não concluída; confira a permissão de notificações." }
    }
}
