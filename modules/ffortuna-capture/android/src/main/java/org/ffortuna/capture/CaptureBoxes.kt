package org.ffortuna.capture

import android.content.Context
import java.util.UUID
import java.time.Clock
import java.time.Instant

/** Used exclusively by the native serial writer. One active box, at most one retained box.
 * A durable pending name recovers interrupted staging; only fully verified boxes become active.
 */
class CaptureBoxes(private val context:Context, preferencesName:String="capture-consent",
    private val defaultName:String="ffortuna-capture-poc",private val clock:Clock=Clock.systemUTC(),
    private val nameFactory:()->String={"ffortuna-restore-${UUID.randomUUID()}"}) : AutoCloseable {
    private val prefs=context.getSharedPreferences(preferencesName,Context.MODE_PRIVATE)
    private var store:CaptureStore?=null
    private var previousCheckedName:String?=null
    private var previousCheck:BoxRetentionStatus?=null
    init { recoverStaging() }
    fun activeName():String=prefs.getString("active-box",defaultName)!!.also { check(it.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }
    private fun previousName():String?=prefs.getString("previous-box",null)?.also { check(it.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }
    fun hasPrevious()=previousName()!=null
    fun current():CaptureStore=store ?: run {
        val fresh=prefs.getBoolean("allow-create-active",false) || (!prefs.contains("active-box") && !prefs.contains("enabled") && !prefs.getBoolean("active-materialized",false))
        val opened=CaptureStore(context,activeName(),clock=clock,allowCreate=fresh)
        try { check(prefs.edit().putBoolean("active-materialized",true).remove("allow-create-active").commit()) }
        catch(error:Exception) { opened.close();throw error }
        opened.also { store=it }
    }
    fun maintainRetention(forcePrevious:Boolean=true):RetentionStatus {
        fun status(block:()->RawRetentionSummary):BoxRetentionStatus = try {
            val r=block();BoxRetentionStatus("ready",r.removed,r.remaining,r.nextExpiry)
        } catch(error:Exception) {
            val phase=if(error is CaptureStorageFailure) error.stage.name else "RETENTION"
            val kind=(if(error is CaptureStorageFailure) error.cause else error)?.javaClass?.simpleName ?: "Unknown"
            BoxRetentionStatus("blocked",failureCode="$phase/$kind")
        }
        val active=status { current().expireRaw() }
        val previous=previousName()
        val cached=previousCheck
        val previousStatus=if(previous==null) BoxRetentionStatus("none") else {
            val due=cached==null || cached.status=="blocked" || (cached.nextExpiry?.let { !clock.instant().isBefore(it) } ?: false)
            if(forcePrevious || previousCheckedName!=previous || due) {
                status { CaptureStore(context,previous,clock=clock,allowCreate=false).use { it.expireRaw() } }.also {
                    previousCheckedName=previous;previousCheck=it
                }
            } else cached!!
        }
        return RetentionStatus(active,previousStatus)
    }
    private fun deleteBox(name:String) {
        check(name.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        val file=context.getDatabasePath("$name.db")
        if(file.exists() || listOf("-wal","-shm","-journal").any { java.io.File(file.path+it).exists() })
            check(context.deleteDatabase(file.name))
        KeyVault(context,name).erase()
    }
    private fun recoverStaging() {
        val pending=prefs.getString("pending-box",null) ?: return
        check(pending!=activeName() && pending!=previousName())
        deleteBox(pending)
        check(prefs.edit().remove("pending-box").commit())
    }
    fun restore(backup:InboxBackup):Int {
        backup.validate()
        recoverStaging()
        if(hasPrevious()) throw BackupFailure(BackupProblem.PREVIOUS_EXISTS)
        val old=activeName();val candidate=nameFactory()
        check(candidate.matches(Regex("[a-zA-Z0-9-]{1,80}")) && candidate!=old)
        check(prefs.edit().putString("pending-box",candidate).commit())
        var activated=false
        try {
            CaptureStore(context,candidate,clock=clock).use { staged -> staged.importBackup(backup);staged.verifyEncrypted() }
            close()
            // Activation and pausing consent are a single durable preference update.
            check(prefs.edit().putString("active-box",candidate).putString("previous-box",old)
                .putBoolean("enabled",false).putBoolean("active-materialized",true).remove("allow-create-active").remove("pending-box").commit())
            previousCheck=null
            activated=true
            return backup.observations.size
        } finally {
            if(!activated) recoverStaging()
        }
    }
    fun switchPrevious() {
        val previous=previousName() ?: throw BackupFailure(BackupProblem.NO_PREVIOUS)
        // Validate the target before changing the active pointer. A missing old key fails closed.
        CaptureStore(context,previous,clock=clock,allowCreate=false).use { it.verifyEncrypted() }
        val old=activeName();close()
        check(prefs.edit().putString("active-box",previous).putString("previous-box",old).putBoolean("enabled",false)
            .putBoolean("active-materialized",true).remove("allow-create-active").commit())
        previousCheck=null
    }
    fun discardPrevious() {
        val previous=previousName() ?: throw BackupFailure(BackupProblem.NO_PREVIOUS)
        check(previous!=activeName())
        deleteBox(previous)
        check(prefs.edit().remove("previous-box").commit())
        previousCheck=null
    }
    fun reset() { close();deleteBox(activeName());check(prefs.edit().putBoolean("enabled",false)
        .putBoolean("active-materialized",false).putBoolean("allow-create-active",true).commit()) }
    override fun close() { val old=store;store=null;old?.close() }
}
