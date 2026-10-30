package org.ffortuna.capture

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import java.io.File

/** Called exclusively on CaptureRuntime's serial executor. Does not depend on JS or synthetic storage. */
class DiagnosticCapture(private val context:Context,private val name:String="ffortuna-diagnostic-v1") {
    private val prefs=context.getSharedPreferences("$name-state",Context.MODE_PRIVATE)
    private var opened:DiagnosticStore?=null
    private var failure:String?=null
    private var processingFailure:String?=null
    private fun exists()=prefs.getBoolean("created",false) || context.getDatabasePath("$name.db").exists() || KeyVault(context,name).hasStoredMaterial()
    private fun store(create:Boolean=false):DiagnosticStore {
        opened?.let { return it }
        val known=exists()
        if(!known && !create) throw DiagnosticFailure("NOT_PREPARED")
        if(!known) check(prefs.edit().putBoolean("created",true).commit())
        return DiagnosticStore(context,name,allowCreate=create && !known).also { opened=it }
    }
    fun status(now:DiagnosticMoment,source:Map<String,Any?>):Map<String,Any?> {
        val absent=mapOf("status" to "absent","active" to false,"startedAt" to null,"expiresAt" to null,"count" to 0,"reviewedCount" to 0,"limitReached" to false,"failureCode" to null)
        return try {
            if(!exists()) absent else {
                val current=store().state(now)
                if(current["active"]==true) store().validateSource(if(source["installed"]==true && source["enabled"]==true) source["signer"] as? String else null)
                val state=store().state(now)
                (failure?.let { state+mapOf("status" to "blocked","active" to false,"failureCode" to it) } ?: state)+mapOf("processingFailureCode" to processingFailure)
            }
        } catch(error:Exception) { absent+mapOf("status" to "blocked","failureCode" to safeCode(error)) }
    }
    fun start(expectedSigner:String,source:Map<String,Any?>,now:DiagnosticMoment) {
        if(source["installed"]!=true || source["enabled"]!=true || source["signer"]!=expectedSigner) throw DiagnosticFailure("SOURCE_CHANGED")
        store(true).start(expectedSigner,now);failure=null
    }
    fun stop() { if(exists()) store().stop() }
    fun clear() {
        // Explicit destructive action covers only this diagnostic namespace and key.
        opened?.close();opened=null
        val file=context.getDatabasePath("$name.db")
        if(file.exists() || listOf("-wal","-shm","-journal").any { File(file.path+it).exists() }) check(context.deleteDatabase(file.name))
        KeyVault(context,name).erase()
        check(prefs.edit().clear().commit());failure=null;processingFailure=null
    }
    fun observe(sbn:StatusBarNotification,received:DiagnosticMoment,now:DiagnosticMoment) {
        if(sbn.packageName!=DiagnosticPolicy.PACKAGE || !exists() || failure!=null) return
        try {
            val db=store()
            if(db.state(now)["active"]!=true) return
            // No content is read before the currently installed signer is checked.
            val source=DiagnosticSource.inspect(context)
            val signer=if(source["enabled"]==true) source["signer"] as? String else null
            if(signer==null) { db.stop();throw DiagnosticFailure("SOURCE_CHANGED") }
            val notification=sbn.notification
            val added=db.observe(sbn.packageName,signer,sbn.key,sbn.postTime,sbn.groupKey,
                notification.flags and Notification.FLAG_GROUP_SUMMARY!=0,received,now) { extract(notification) }
            if(added) try { db.processCandidates(now);processingFailure=null }
                catch (_:Exception) { processingFailure="CANDIDATE_PROCESSING" }
        } catch(error:Exception) { failure=safeCode(error);try { opened?.stop() } catch (_:Exception) { } }
    }
    fun samples(now:DiagnosticMoment)=if(exists()) store().samples(now) else emptyList()
    fun sample(id:String,now:DiagnosticMoment)=store().sample(id,now)
    fun review(id:String,json:String,confirmed:Boolean,now:DiagnosticMoment)=store().review(id,json,confirmed,now)
    fun excludeReview(id:String)=store().excludeReview(id)
    fun candidate(id:String,now:DiagnosticMoment,force:Boolean=false)=store().candidate(id,now,force)
    fun saveCandidate(id:String,json:String,now:DiagnosticMoment)=store().saveCandidate(id,json,now)
    fun exportReviewed(now:DiagnosticMoment)=store().exportReviewed(now)
    companion object {
        fun safeCode(error:Exception):String {
            var current:Throwable=error
            while((current is java.util.concurrent.ExecutionException || current is java.util.concurrent.CompletionException) && current.cause!=null) current=current.cause!!
            return when(current) {
                is DiagnosticFailure -> current.code
                is ClassificationFailure -> "CLASSIFICATION_${current.code}"
                else -> "DIAGNOSTIC_STORAGE"
            }
        }
        /** Bounded allowlist; never dump extras, images, actions, people or PendingIntents. */
        internal fun extract(notification:Notification):Pair<DiagnosticFields,Boolean> {
            var budget=DiagnosticPolicy.MAX_CHARS;var truncated=false
            fun bounded(value:CharSequence?):String {
                if(value==null) return ""
                val size=minOf(value.length,DiagnosticPolicy.MAX_FIELD,budget)
                if(size<value.length) truncated=true
                budget-=size
                return value.subSequence(0,size).toString()
            }
            val extras=notification.extras
            val title=bounded(extras.getCharSequence(Notification.EXTRA_TITLE))
            val text=bounded(extras.getCharSequence(Notification.EXTRA_TEXT))
            val big=bounded(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            val sub=bounded(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            val originalLines=extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
            if(originalLines.size>32) truncated=true
            val lines=originalLines.take(32).map(::bounded)
            return DiagnosticFields(title,text,big,sub,lines) to truncated
        }
    }
}
